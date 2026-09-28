//! 报价图：买/卖方向展开为不同有向边。

use crate::quote::Quote;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EdgeKind {
    /// 卖出 base 买入 quote，使用 bid。
    BaseToQuote,
    /// 卖出 quote 买入 base，使用 1/ask。
    QuoteToBase,
}

/// 一条有向可执行边。
#[derive(Clone, Copy, Debug)]
pub struct Edge {
    pub id: usize,
    pub from: usize,
    pub to: usize,
    pub kind: EdgeKind,
    /// BaseToQuote 时为 bid，QuoteToBase 时为 ask。
    pub price: u64,
    /// 起点币种的最大可投入数量（定点）。
    pub cap_in: u64,
    pub source_seq: u64,
    pub logical_time: u64,
}

/// 应用边后的结果。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Applied {
    /// 实际投入（受 cap_in 截断）。
    pub amount_in_used: u64,
    pub amount_out: u64,
    /// 向下取整损失：理想乘积减去实际结果，单位为输出币种定点最小单位之下。
    pub rounding_loss: u128,
}

impl Edge {
    /// 按交易方向保守取整（向下取整）地应用边；结果超出 u64 返回 None。
    pub fn apply(&self, amount_in: u64) -> Option<Applied> {
        let used = amount_in.min(self.cap_in);
        let (num, den): (u128, u128) = match self.kind {
            // 卖 base 买 quote：out = in * bid / SCALE
            EdgeKind::BaseToQuote => ((used as u128) * (self.price as u128), crate::quote::SCALE as u128),
            // 卖 quote 买 base：out = in * SCALE / ask
            EdgeKind::QuoteToBase => ((used as u128) * (crate::quote::SCALE as u128), self.price as u128),
        };
        let out = num / den;
        if out > u64::MAX as u128 {
            return None;
        }
        Some(Applied {
            amount_in_used: used,
            amount_out: out as u64,
            rounding_loss: num % den,
        })
    }
}

/// 规范化报价图：币种与边均按规范顺序排列。
#[derive(Clone, Debug, Default)]
pub struct Graph {
    pub currencies: Vec<String>,
    pub edges: Vec<Edge>,
    pub adj: Vec<Vec<usize>>,
}

impl Graph {
    pub fn build(quotes: &[Quote]) -> Graph {
        let mut currencies: Vec<String> = quotes
            .iter()
            .flat_map(|q| [q.base.clone(), q.quote.clone()])
            .collect();
        currencies.sort();
        currencies.dedup();

        // 先为每条报价生成两条方向边，再按规范键排序赋 id，
        // 使边序列与输入顺序、并发分片顺序无关。
        let mut raw: Vec<Edge> = Vec::new();
        for q in quotes {
            let bi = currencies.iter().position(|c| c == &q.base).unwrap();
            let qi = currencies.iter().position(|c| c == &q.quote).unwrap();
            // 反向边容量：quote 侧最大投入 = floor(max_qty * ask / SCALE)，
            // 超过 u64 时钳到 u64::MAX（金额本身也无法超过它）。
            let reverse_cap = ((q.max_qty as u128) * (q.ask as u128)
                / crate::quote::SCALE as u128)
                .min(u64::MAX as u128) as u64;
            raw.push(Edge {
                id: 0,
                from: bi,
                to: qi,
                kind: EdgeKind::BaseToQuote,
                price: q.bid,
                cap_in: q.max_qty,
                source_seq: q.source_seq,
                logical_time: q.logical_time,
            });
            raw.push(Edge {
                id: 0,
                from: qi,
                to: bi,
                kind: EdgeKind::QuoteToBase,
                price: q.ask,
                cap_in: reverse_cap,
                source_seq: q.source_seq,
                logical_time: q.logical_time,
            });
        }
        raw.sort_by_key(|e| {
            (
                e.from,
                e.to,
                e.kind as u8,
                e.price,
                e.source_seq,
                e.logical_time,
            )
        });
        let n = currencies.len();
        let mut graph = Graph {
            currencies,
            edges: raw,
            adj: vec![Vec::new(); n],
        };
        for (id, e) in graph.edges.iter_mut().enumerate() {
            e.id = id;
            graph.adj[e.from].push(id);
        }
        graph
    }

    pub fn currency_index(&self, code: &str) -> Option<usize> {
        self.currencies.iter().position(|c| c == code)
    }

    /// 当前逻辑时间，供调用方过滤过期边。
    pub fn is_edge_live(&self, edge_id: usize, now: u64, max_age: u64) -> bool {
        self.edges
            .get(edge_id)
            .map(|e| e.logical_time >= now.saturating_sub(max_age))
            .unwrap_or(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::quote::{Quote, SCALE};

    fn q(base: &str, quote: &str, bid: u64, ask: u64, qty: u64, seq: u64) -> Quote {
        Quote {
            base: base.into(),
            quote: quote.into(),
            bid,
            ask,
            max_qty: qty,
            source_seq: seq,
            logical_time: 0,
        }
    }

    #[test]
    fn applies_both_directions_with_floor() {
        let g = Graph::build(&[q("USD", "EUR", 900_000, 900_001, 100 * SCALE, 1)]);
        // USD -> EUR 用 bid 0.9
        let usd = g.currency_index("USD").unwrap();
        let eur = g.currency_index("EUR").unwrap();
        let fwd = g
            .edges
            .iter()
            .find(|e| e.kind == EdgeKind::BaseToQuote && e.from == usd && e.to == eur)
            .unwrap();
        let a = fwd.apply(2 * SCALE).unwrap();
        assert_eq!(a.amount_out, 1_800_000);
        assert_eq!(a.rounding_loss, 0);
        // 非整除时向下取整并记录损耗
        let a = fwd.apply(SCALE + 1).unwrap();
        assert_eq!(a.amount_out, 900_000);
        assert_eq!(a.rounding_loss, 900_000);
        // EUR -> USD 用 1/ask
        let rev = g
            .edges
            .iter()
            .find(|e| e.kind == EdgeKind::QuoteToBase)
            .unwrap();
        let a = rev.apply(SCALE).unwrap();
        assert_eq!(a.amount_out, 1_111_109); // floor(1e6 * 1e6 / 900001)
        assert_eq!(a.rounding_loss, 788_891);
    }

    #[test]
    fn capacity_truncates_input() {
        let g = Graph::build(&[q("USD", "EUR", SCALE, SCALE + 1, 10 * SCALE, 1)]);
        let fwd = g
            .edges
            .iter()
            .find(|e| e.kind == EdgeKind::BaseToQuote)
            .unwrap();
        let a = fwd.apply(100 * SCALE).unwrap();
        assert_eq!(a.amount_in_used, 10 * SCALE);
        assert_eq!(a.amount_out, 10 * SCALE);
    }

    #[test]
    fn build_is_order_independent() {
        let a = q("USD", "EUR", SCALE, SCALE + 1, 10 * SCALE, 1);
        let b = q("EUR", "JPY", 160 * SCALE, 160 * SCALE + 1, 10 * SCALE, 2);
        let g1 = Graph::build(&[a.clone(), b.clone()]);
        let g2 = Graph::build(&[b, a]);
        assert_eq!(g1.currencies, vec!["EUR", "JPY", "USD"]);
        let key = |g: &Graph| {
            g.edges
                .iter()
                .map(|e| (e.from, e.to, e.kind as u8, e.price))
                .collect::<Vec<_>>()
        };
        assert_eq!(key(&g1), key(&g2));
    }
}
