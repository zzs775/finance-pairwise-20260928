//! 穷举参考器 vs 快算法：随机 <=6 币种夹具逐案比对；
//! 另覆盖输入顺序/分片顺序不变性与套利证据独立复算。

use fx_arb::{
    arbitrage::{best_arbitrage, canonical_rotation, confirm_cycle},
    explain::{explain_arbitrage, explain_path},
    graph::Graph,
    pathfind::{best_path, simulate},
    quote::{validate_quotes, Quote, SCALE},
    reference::{best_arbitrage_reference, best_path_reference},
};

struct Rng(u64);

impl Rng {
    fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn range(&mut self, lo: u64, hi: u64) -> u64 {
        lo + self.next_u64() % (hi - lo + 1)
    }
}

const CODES: [&str; 6] = ["A", "B", "C", "D", "E", "F"];

fn random_quotes(rng: &mut Rng, n: usize) -> Vec<Quote> {
    let pair_count = rng.range(n as u64, n as u64 + 3) as usize;
    let mut out = Vec::new();
    for _ in 0..pair_count {
        let bi = rng.range(0, n as u64 - 1) as usize;
        let mut qi = rng.range(0, n as u64 - 1) as usize;
        if qi == bi {
            qi = (qi + 1) % n;
        }
        let mid = rng.range(500_000, 5_000_000);
        let spread = rng.range(0, 50_000);
        let bid = mid.saturating_sub(spread).max(1);
        let ask = mid + spread;
        out.push(Quote {
            base: CODES[bi].into(),
            quote: CODES[qi].into(),
            bid,
            ask,
            max_qty: rng.range(1, 100 * SCALE),
            source_seq: rng.range(0, 4), // 故意制造重复键
            logical_time: rng.range(0, 20),
        });
    }
    out
}

fn shuffle(rng: &mut Rng, mut v: Vec<Quote>) -> Vec<Quote> {
    for i in (1..v.len()).rev() {
        let j = rng.range(0, i as u64) as usize;
        v.swap(i, j);
    }
    v
}

#[test]
fn fast_matches_reference_on_random_fixtures() {
    let mut rng = Rng(0x1234_5678_9abc_def0);
    for case in 0..80 {
        let n = rng.range(2, 6) as usize;
        let quotes = random_quotes(&mut rng, n);
        let now = 15;
        let max_age = rng.range(3, 20);
        let (accepted, _rejected) = validate_quotes(&quotes, now, max_age);
        let g = Graph::build(&accepted);
        let m = g.currencies.len();
        let amounts = [1u64, SCALE, 100 * SCALE];
        for from in 0..m {
            for to in 0..m {
                for hops in 1..=4usize {
                    for &amt in &amounts {
                        let fast = best_path(&g, from, to, amt, hops, now, max_age);
                        let slow =
                            best_path_reference(&g, from, to, amt, hops, now, max_age);
                        assert_eq!(
                            fast, slow,
                            "case {case}: path {from}->{to} hops {hops} amt {amt}"
                        );
                    }
                }
            }
        }
        for hops in 2..=4usize {
            let fast = best_arbitrage(&g, hops, now, max_age);
            let slow = best_arbitrage_reference(&g, hops, now, max_age);
            assert_eq!(fast, slow, "case {case}: arb hops {hops}");
            // 任何套利证据都必须能由独立定点逐边模拟复算。
            if let Some(cyc) = &fast {
                assert_eq!(canonical_rotation(&cyc.edges), cyc.edges);
                let (out, steps) = simulate(&g, &cyc.edges, cyc.amount_in)
                    .expect("evidence must simulate");
                assert_eq!(out, cyc.amount_out);
                assert_eq!(steps, cyc.steps);
                assert!(out > cyc.amount_in);
                // 公开 API confirm_cycle 也必须独立复算通过。
                assert!(confirm_cycle(&g, &cyc.edges).is_some());
            }
        }
    }
}

#[test]
fn results_are_invariant_under_input_and_shard_order() {
    let mut rng = Rng(0xdead_beef_cafe_0001);
    let quotes = random_quotes(&mut rng, 5);
    let now = 15;
    let max_age = 8;

    let (a1, r1) = validate_quotes(&quotes, now, max_age);
    let shuffled = shuffle(&mut rng, quotes.clone());
    let (a2, r2) = validate_quotes(&shuffled, now, max_age);
    assert_eq!(a1, a2);
    assert_eq!(r1, r2);

    // 两种不同的三分片切法。
    let s1 = vec![
        quotes[0..quotes.len() / 3].to_vec(),
        quotes[quotes.len() / 3..2 * quotes.len() / 3].to_vec(),
        quotes[2 * quotes.len() / 3..].to_vec(),
    ];
    let other = shuffle(&mut rng, quotes.clone());
    let s2 = vec![
        other[0..other.len() / 3].to_vec(),
        other[other.len() / 3..2 * other.len() / 3].to_vec(),
        other[2 * other.len() / 3..].to_vec(),
    ];
    let (m1, _) = fx_arb::quote::merge_shards(&s1, now, max_age);
    let (m2, _) = fx_arb::quote::merge_shards(&s2, now, max_age);
    assert_eq!(m1, m2);
    assert_eq!(m1, a1);

    let g1 = Graph::build(&a1);
    let g2 = Graph::build(&a2);
    let p1 = best_path(&g1, 0, 1, SCALE, 4, now, max_age);
    let p2 = best_path(&g2, 0, 1, SCALE, 4, now, max_age);
    assert_eq!(p1, p2);
    assert_eq!(explain_path(&g1, p1.as_ref()), explain_path(&g2, p2.as_ref()));
    let c1 = best_arbitrage(&g1, 4, now, max_age);
    let c2 = best_arbitrage(&g2, 4, now, max_age);
    assert_eq!(
        explain_arbitrage(&g1, c1.as_ref()),
        explain_arbitrage(&g2, c2.as_ref())
    );
}
