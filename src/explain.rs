//! 确定性的 JSON 解释输出（手写，仅 std）。
//!
//! 所有键按字典序排列；定点金额与 u128 损耗用字符串编码，避免 JSON 数字精度歧义。

use crate::{
    arbitrage::ArbitrageCycle, graph::Graph, pathfind::PathResult,
};

/// 最佳路径的解释 JSON（键序固定）。
pub fn explain_path(graph: &Graph, result: Option<&PathResult>) -> String {
    let mut s = String::from("{\n");
    s.push_str(&format!(
        "  \"amount_in\": {},\n",
        result.map(|r| r.amount_in.to_string()).unwrap_or_else(|| "null".into())
    ));
    s.push_str(&format!(
        "  \"amount_out\": {},\n",
        result.map(|r| r.amount_out.to_string()).unwrap_or_else(|| "null".into())
    ));
    s.push_str("  \"found\": ");
    s.push_str(if result.is_some() { "true" } else { "false" });
    s.push_str(",\n");
    s.push_str(&format!("  \"hops\": {},\n", result.map(|r| r.hops).unwrap_or(0)));
    s.push_str(&format!("  \"scale\": {},\n", crate::quote::SCALE));
    s.push_str("  \"steps\": [");
    if let Some(r) = result {
        for (i, st) in r.steps.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            s.push('\n');
            s.push_str(&step_json(graph, st, 4));
        }
        if !r.steps.is_empty() {
            s.push_str("\n  ");
        }
    }
    s.push_str("]\n}\n");
    s
}

/// 套利环的解释 JSON（键序固定）。
pub fn explain_arbitrage(graph: &Graph, result: Option<&ArbitrageCycle>) -> String {
    let mut s = String::from("{\n");
    s.push_str(&format!(
        "  \"amount_in\": {},\n",
        result.map(|r| r.amount_in.to_string()).unwrap_or_else(|| "null".into())
    ));
    s.push_str(&format!(
        "  \"amount_out\": {},\n",
        result.map(|r| r.amount_out.to_string()).unwrap_or_else(|| "null".into())
    ));
    s.push_str("  \"found\": ");
    s.push_str(if result.is_some() { "true" } else { "false" });
    s.push_str(",\n");
    s.push_str(&format!("  \"hops\": {},\n", result.map(|r| r.hops).unwrap_or(0)));
    s.push_str(&format!(
        "  \"profit\": {},\n",
        result.map(|r| r.profit.to_string()).unwrap_or_else(|| "null".into())
    ));
    s.push_str(&format!("  \"scale\": {},\n", crate::quote::SCALE));
    s.push_str(&format!(
        "  \"start\": {},\n",
        result
            .map(|r| json_str(&graph.currencies[r.start]))
            .unwrap_or_else(|| "null".into())
    ));
    s.push_str("  \"steps\": [");
    if let Some(r) = result {
        for (i, st) in r.steps.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            s.push('\n');
            s.push_str(&step_json(graph, st, 4));
        }
        if !r.steps.is_empty() {
            s.push_str("\n  ");
        }
    }
    s.push_str("]\n}\n");
    s
}

fn json_str(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len() + 2);
    out.push('"');
    for c in raw.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn step_json(graph: &Graph, st: &crate::pathfind::Step, indent: usize) -> String {
    let pad = " ".repeat(indent + 2);
    let close = " ".repeat(indent);
    let e = &graph.edges[st.edge];
    let kind = match e.kind {
        crate::graph::EdgeKind::BaseToQuote => "base_to_quote",
        crate::graph::EdgeKind::QuoteToBase => "quote_to_base",
    };
    let mut s = String::new();
    s.push_str(&format!("{close}{{\n"));
    s.push_str(&format!(
        "{pad}  \"amount_in\": \"{}\",\n",
        st.amount_in
    ));
    s.push_str(&format!(
        "{pad}  \"amount_in_used\": \"{}\",\n",
        st.amount_in_used
    ));
    s.push_str(&format!(
        "{pad}  \"amount_out\": \"{}\",\n",
        st.amount_out
    ));
    s.push_str(&format!("{pad}  \"edge\": {},\n", st.edge));
    s.push_str(&format!("{pad}  \"from\": {},\n", json_str(&graph.currencies[e.from])));
    s.push_str(&format!("{pad}  \"kind\": {},\n", json_str(kind)));
    s.push_str(&format!("{pad}  \"price\": \"{}\",\n", e.price));
    s.push_str(&format!(
        "{pad}  \"rounding_loss\": \"{}\",\n",
        st.rounding_loss
    ));
    s.push_str(&format!("{pad}  \"to\": {}\n", json_str(&graph.currencies[e.to])));
    s.push_str(&format!("{close}}}"));
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::quote::SCALE;

    #[test]
    fn null_output_is_deterministic() {
        let g = Graph::default();
        let j = explain_path(&g, None);
        assert!(j.contains("\"found\": false"));
        assert!(j.contains("\"scale\": 1000000"));
        assert_eq!(j, explain_path(&g, None));
        let a = explain_arbitrage(&g, None);
        assert!(a.contains("\"found\": false"));
        assert!(a.contains("\"profit\": null"));
    }

    #[test]
    fn path_json_lists_steps() {
        use crate::quote::Quote;
        let qs = [Quote {
            base: "USD".into(),
            quote: "EUR".into(),
            bid: 900_000,
            ask: 900_001,
            max_qty: 1_000 * SCALE,
            source_seq: 1,
            logical_time: 5,
        }];
        let g = Graph::build(&qs);
        let usd = g.currency_index("USD").unwrap();
        let eur = g.currency_index("EUR").unwrap();
        let r = crate::pathfind::best_path(&g, usd, eur, SCALE, 1, 5, 100).unwrap();
        let j = explain_path(&g, Some(&r));
        assert!(j.contains("\"found\": true"));
        assert!(j.contains("\"from\": \"USD\""));
        assert!(j.contains("\"to\": \"EUR\""));
        assert!(j.contains("\"amount_out\": \"900000\""));
        assert!(j.contains("\"kind\": \"base_to_quote\""));
    }
}
