//! 手工构造的边界案例：方向、时效、跳数、容量、平局、伪套利、尺度溢出。

use fx_arb::{
    arbitrage::best_arbitrage,
    graph::Graph,
    pathfind::{best_path, simulate},
    quote::{validate_quotes, Quote, QuoteError, MAX_PRICE, SCALE},
};

fn quote(base: &str, quote: &str, bid: u64, ask: u64, qty: u64, seq: u64, t: u64) -> Quote {
    Quote {
        base: base.into(),
        quote: quote.into(),
        bid,
        ask,
        max_qty: qty,
        source_seq: seq,
        logical_time: t,
    }
}

#[test]
fn bid_ask_pick_correct_direction() {
    let qs = [quote("USD", "EUR", 900_000, 950_000, 1000 * SCALE, 1, 10)];
    let (ok, _) = validate_quotes(&qs, 10, 100);
    let g = Graph::build(&ok);
    let usd = g.currency_index("USD").unwrap();
    let eur = g.currency_index("EUR").unwrap();
    // USD -> EUR 用 bid=0.9
    let sell = best_path(&g, usd, eur, SCALE, 1, 10, 100).unwrap();
    assert_eq!(sell.amount_out, 900_000);
    // EUR -> USD 用 1/ask，floor
    let buy = best_path(&g, eur, usd, SCALE, 1, 10, 100).unwrap();
    assert_eq!(buy.amount_out, 1_052_631); // floor(1e12/950000)
}

#[test]
fn expiry_boundary_excludes_stale_edges() {
    let qs = [
        quote("A", "B", SCALE, SCALE + 1, SCALE, 1, 5),
        quote("B", "C", SCALE, SCALE + 1, SCALE, 2, 4),
    ];
    let (ok, bad) = validate_quotes(&qs, 10, 5); // age==5 存活，6 过期
    assert_eq!(ok.len(), 1);
    assert_eq!(bad.len(), 1);
    assert_eq!(bad[0].1, QuoteError::Expired);
    let g = Graph::build(&ok);
    let a = g.currency_index("A").unwrap();
    assert!(g.currency_index("C").is_none());
    assert!(best_path(&g, a, g.currency_index("B").unwrap(), SCALE, 1, 10, 5).is_some());
}

#[test]
fn hop_limit_cuts_longer_paths() {
    let qs = [
        quote("A", "B", SCALE, SCALE + 1, SCALE, 1, 10),
        quote("B", "C", SCALE, SCALE + 1, SCALE, 2, 10),
        quote("C", "D", SCALE, SCALE + 1, SCALE, 3, 10),
    ];
    let g = Graph::build(&qs);
    let (a, d) = (0, 3);
    assert!(best_path(&g, a, d, SCALE, 2, 10, 100).is_none());
    let r = best_path(&g, a, d, SCALE, 3, 10, 100).unwrap();
    assert_eq!(r.hops, 3);
    assert_eq!(r.amount_out, SCALE);
}

#[test]
fn capacity_chokes_each_edge_independently() {
    let qs = [
        quote("A", "B", SCALE, SCALE + 1, 2 * SCALE, 1, 10),
        quote("B", "C", SCALE, SCALE + 1, SCALE, 2, 10),
    ];
    let g = Graph::build(&qs);
    let (a, c) = (0, 2);
    let r = best_path(&g, a, c, 10 * SCALE, 2, 10, 100).unwrap();
    assert_eq!(r.amount_out, SCALE); // 瓶颈在 B->C 的 1 单位容量
    assert_eq!(r.steps[1].amount_in, 2 * SCALE);
    assert_eq!(r.steps[1].amount_in_used, SCALE);
}

#[test]
fn equal_amount_prefers_fewer_hops_then_lex_sequence() {
    // 一跳 1.0 与两跳 1.0*1.0 金额相同：一跳胜出。
    let qs = [
        quote("A", "C", SCALE, SCALE + 1, SCALE, 1, 10),
        quote("A", "B", SCALE, SCALE + 1, SCALE, 2, 10),
        quote("B", "C", SCALE, SCALE + 1, SCALE, 3, 10),
    ];
    let g = Graph::build(&qs);
    let (a, c) = (0, 2);
    let r = best_path(&g, a, c, SCALE, 3, 10, 100).unwrap();
    assert_eq!(r.hops, 1);
}

#[test]
fn pseudo_arbitrage_rejected_after_rounding() {
    // 理想乘积 = 1.0001*1/1.000051 ≈ 1.000049，但容量压到 1 微单位后取整归零。
    let mut e1 = quote("A", "B", 1_000_100, 2_000_000, 1, 1, 10);
    e1.max_qty = 1;
    let e2 = quote("A", "B", 1_000_000, 1_000_051, 1000 * SCALE, 2, 10);
    let g = Graph::build(&[e1, e2]);
    assert!(best_arbitrage(&g, 3, 10, 100).is_none());
}

#[test]
fn scale_overflow_quotes_are_classified() {
    let qs = [
        quote("A", "B", MAX_PRICE + 1, MAX_PRICE + 2, SCALE, 1, 10),
        quote("A", "B", SCALE, SCALE + 1, u64::MAX, 2, 10),
    ];
    let (ok, bad) = validate_quotes(&qs, 10, 100);
    assert!(ok.is_empty());
    assert!(bad.iter().all(|(_, e)| *e == QuoteError::ScaleOverflow));
}

#[test]
fn compound_overflow_invalidates_path_but_simulate_reports_none() {
    // 先按 1:1 放大到 1e12，再遇 1e9 的巨价：中间结果超过 u64。
    let qs = [
        quote("A", "B", SCALE, SCALE + 1, 1_000_000 * SCALE, 1, 10),
        quote("B", "C", MAX_PRICE, MAX_PRICE, 1_000_000 * SCALE, 2, 10),
    ];
    let g = Graph::build(&qs);
    let (a, c) = (0, 2);
    // 两跳溢出，不能作为结果；一跳 A->B 仍合法。
    let r = best_path(&g, a, c, 1_000_000 * SCALE, 2, 10, 100);
    assert!(r.is_none());
    let two_edges: Vec<usize> = g
        .edges
        .iter()
        .filter(|e| e.kind == fx_arb::graph::EdgeKind::BaseToQuote)
        .map(|e| e.id)
        .collect();
    assert!(simulate(&g, &two_edges, 1_000_000 * SCALE).is_none());
}

#[test]
fn arbitrage_evidence_step_amounts_chain_consistently() {
    let qs = [
        quote("A", "B", 1_100_000, 2_000_000, 1000 * SCALE, 1, 10),
        quote("B", "C", 1_100_000, 2_000_000, 1000 * SCALE, 2, 10),
        quote("C", "A", 830_000, 2_000_000, 1000 * SCALE, 3, 10),
    ];
    let g = Graph::build(&qs);
    let cyc = best_arbitrage(&g, 4, 10, 100).unwrap();
    let mut cur = cyc.amount_in;
    for st in &cyc.steps {
        assert_eq!(st.amount_in, cur);
        assert!(st.amount_in_used <= st.amount_in);
        cur = st.amount_out;
    }
    assert_eq!(cur, cyc.amount_out);
    assert_eq!(cyc.amount_out - cyc.amount_in, cyc.profit);
    // 每步损耗都小于当步分母（1e6 或价格）。
    for st in &cyc.steps {
        let e = &g.edges[st.edge];
        let den = match e.kind {
            fx_arb::graph::EdgeKind::BaseToQuote => SCALE,
            fx_arb::graph::EdgeKind::QuoteToBase => e.price,
        } as u128;
        assert!(st.rounding_loss < den);
    }
}
