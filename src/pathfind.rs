//! 限定跳数内最佳简单路径（快算法）。
//!
//! DFS 枚举简单路径，用 Bellman-Ford 求出的对数域最大可达乘积做上界剪枝；
//! 取整与容量只会让实际金额变小，因此剪枝不可能漏掉更优路径。

use crate::graph::Graph;

/// 单边执行明细。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Step {
    pub edge: usize,
    /// 到达该边时的投入金额（可能超过容量）。
    pub amount_in: u64,
    /// 实际被容量接受的投入金额。
    pub amount_in_used: u64,
    pub amount_out: u64,
    /// 取整损耗（定点最小单位的整数倍）。
    pub rounding_loss: u128,
}

/// 最佳路径结果。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathResult {
    pub amount_in: u64,
    pub amount_out: u64,
    pub hops: usize,
    pub edges: Vec<usize>,
    pub steps: Vec<Step>,
}

/// 在 max_hops 内寻找 from -> to 的最佳简单路径；无可达路径返回 None。
///
/// 比较顺序：最终金额降序、跳数升序、规范边序列字典序。
pub fn best_path(
    graph: &Graph,
    from: usize,
    to: usize,
    amount_in: u64,
    max_hops: usize,
    now: u64,
    max_age: u64,
) -> Option<PathResult> {
    let n = graph.currencies.len();
    if from >= n || to >= n {
        return None;
    }
    // 零跳：同币种即得，作为初始在位者（任何产出更高的路径可击败它）。
    let mut best: Option<PathResult> = None;
    if from == to {
        best = Some(PathResult {
            amount_in,
            amount_out: amount_in,
            hops: 0,
            edges: Vec::new(),
            steps: Vec::new(),
        });
    }

    // 各跳数下“到目标的最大对数乘积”上界，只使用存活边。
    let neg = f64::NEG_INFINITY;
    let mut ub = vec![neg; n];
    ub[to] = 0.0;
    let mut bounds: Vec<Vec<f64>> = vec![ub.clone()];
    for _ in 1..=max_hops {
        let mut next = ub.clone();
        for e in &graph.edges {
            if !graph.is_edge_live(e.id, now, max_age) {
                continue;
            }
            if ub[e.to] != neg {
                let cand = log_rate(e) + ub[e.to];
                if cand > next[e.from] {
                    next[e.from] = cand;
                }
            }
        }
        ub = next;
        bounds.push(ub.clone());
    }

    let mut visited = vec![false; n];
    visited[from] = true;
    let mut path: Vec<usize> = Vec::new();
    let mut steps: Vec<Step> = Vec::new();
    dfs(
        graph,
        DfsCtx {
            to,
            amount_in,
            max_hops,
            now,
            max_age,
            bounds: &bounds,
            visited: &mut visited,
            path: &mut path,
            steps: &mut steps,
            best: &mut best,
            log_sum: 0.0,
        },
        from,
        0,
        amount_in,
    );
    best
}

/// 边的对数汇率：BaseToQuote 为 ln(bid/SCALE)，QuoteToBase 为 ln(SCALE/ask)。
pub(crate) fn log_rate(e: &crate::graph::Edge) -> f64 {
    match e.kind {
        crate::graph::EdgeKind::BaseToQuote => {
            (e.price as f64 / crate::quote::SCALE_F).ln()
        }
        crate::graph::EdgeKind::QuoteToBase => {
            (crate::quote::SCALE_F / e.price as f64).ln()
        }
    }
}

/// 严格按边序逐边模拟；任意一步结果溢出返回 None。
pub fn simulate(graph: &Graph, edge_ids: &[usize], amount_in: u64) -> Option<(u64, Vec<Step>)> {
    let mut cur = amount_in;
    let mut steps = Vec::with_capacity(edge_ids.len());
    for &id in edge_ids {
        let e = graph.edges.get(id)?;
        let a = e.apply(cur)?;
        steps.push(Step {
            edge: id,
            amount_in: cur,
            amount_in_used: a.amount_in_used,
            amount_out: a.amount_out,
            rounding_loss: a.rounding_loss,
        });
        cur = a.amount_out;
    }
    Some((cur, steps))
}

/// 候选路径是否严格优于在位路径。
pub(crate) fn is_better(
    out: u64,
    hops: usize,
    edges: &[usize],
    best: &PathResult,
) -> bool {
    if out != best.amount_out {
        return out > best.amount_out;
    }
    if hops != best.hops {
        return hops < best.hops;
    }
    edges < best.edges.as_slice()
}

struct DfsCtx<'a> {
    to: usize,
    amount_in: u64,
    max_hops: usize,
    now: u64,
    max_age: u64,
    bounds: &'a [Vec<f64>],
    visited: &'a mut Vec<bool>,
    path: &'a mut Vec<usize>,
    steps: &'a mut Vec<Step>,
    best: &'a mut Option<PathResult>,
    log_sum: f64,
}

fn dfs(graph: &Graph, ctx: DfsCtx, cur: usize, hops: usize, amount: u64) {
    if cur == ctx.to && hops > 0 {
        if ctx
            .best
            .as_ref()
            .map(|b| is_better(amount, hops, ctx.path, b))
            .unwrap_or(true)
        {
            *ctx.best = Some(PathResult {
                amount_in: ctx.amount_in,
                amount_out: amount,
                hops,
                edges: ctx.path.clone(),
                steps: ctx.steps.clone(),
            });
        }
    }

    if hops == ctx.max_hops {
        return;
    }

    // 对数上界剪枝：剩余预算内的理想最佳仍严格小于在位产出即剪。
    let remain = ctx.max_hops - hops;
    let upper = ctx.log_sum + ctx.bounds[remain][cur];
    if upper == f64::NEG_INFINITY {
        return;
    }
    if let Some(b) = ctx.best.as_ref() {
        if b.amount_out > 0 {
            let inc_log = (b.amount_out as f64 / ctx.amount_in.max(1) as f64).ln();
            if upper + 1e-9 < inc_log {
                return;
            }
        }
    }

    let neighbors: Vec<usize> = graph.adj[cur]
        .iter()
        .copied()
        .filter(|&id| {
            let e = &graph.edges[id];
            graph.is_edge_live(id, ctx.now, ctx.max_age) && !ctx.visited[e.to]
        })
        .collect();
    for id in neighbors {
        let e = &graph.edges[id];
        let applied = match e.apply(amount) {
            Some(a) => a,
            None => continue,
        };
        ctx.visited[e.to] = true;
        ctx.path.push(id);
        ctx.steps.push(Step {
            edge: id,
            amount_in: amount,
            amount_in_used: applied.amount_in_used,
            amount_out: applied.amount_out,
            rounding_loss: applied.rounding_loss,
        });
        let next_log_sum = ctx.log_sum + log_rate(e);
        let next = DfsCtx {
            to: ctx.to,
            amount_in: ctx.amount_in,
            max_hops: ctx.max_hops,
            now: ctx.now,
            max_age: ctx.max_age,
            bounds: ctx.bounds,
            visited: &mut *ctx.visited,
            path: &mut *ctx.path,
            steps: &mut *ctx.steps,
            best: &mut *ctx.best,
            log_sum: next_log_sum,
        };
        dfs(graph, next, e.to, hops + 1, applied.amount_out);
        ctx.steps.pop();
        ctx.path.pop();
        ctx.visited[e.to] = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::graph::Graph;
    use crate::quote::{Quote, SCALE};

    fn q(base: &str, quote: &str, bid: u64, ask: u64, seq: u64) -> Quote {
        Quote {
            base: base.into(),
            quote: quote.into(),
            bid,
            ask,
            max_qty: 1_000 * SCALE,
            source_seq: seq,
            logical_time: 10,
        }
    }

    #[test]
    fn direct_and_two_hop_paths() {
        let g = Graph::build(&[
            q("USD", "EUR", 900_000, 900_001, 1),
            q("EUR", "JPY", 160 * SCALE, 160 * SCALE + 1, 2),
            q("USD", "JPY", 140 * SCALE, 141 * SCALE, 3),
        ]);
        let usd = g.currency_index("USD").unwrap();
        let jpy = g.currency_index("JPY").unwrap();
        // 直连 140；两跳 0.9*160 = 144，两跳更优。
        let r = best_path(&g, usd, jpy, SCALE, 3, 10, 100).unwrap();
        assert_eq!(r.amount_out, 144 * SCALE);
        assert_eq!(r.hops, 2);
    }

    #[test]
    fn respects_hop_limit() {
        let g = Graph::build(&[
            q("USD", "EUR", 900_000, 900_001, 1),
            q("EUR", "JPY", 160 * SCALE, 160 * SCALE + 1, 2),
        ]);
        let usd = g.currency_index("USD").unwrap();
        let jpy = g.currency_index("JPY").unwrap();
        assert!(best_path(&g, usd, jpy, SCALE, 1, 10, 100).is_none());
        let r = best_path(&g, usd, jpy, SCALE, 2, 10, 100).unwrap();
        assert_eq!(r.hops, 2);
    }

    #[test]
    fn expired_edges_are_invalid() {
        let g = Graph::build(&[q("USD", "EUR", 900_000, 900_001, 1)]);
        let usd = g.currency_index("USD").unwrap();
        let eur = g.currency_index("EUR").unwrap();
        assert!(best_path(&g, usd, eur, SCALE, 1, 200, 100).is_none());
        // 边界：age == max_age 仍存活
        assert!(best_path(&g, usd, eur, SCALE, 1, 110, 100).is_some());
    }

    #[test]
    fn capacity_caps_the_flow() {
        let mut small = q("USD", "EUR", SCALE, SCALE + 1, 1);
        small.max_qty = 5 * SCALE;
        let g = Graph::build(&[small]);
        let usd = g.currency_index("USD").unwrap();
        let eur = g.currency_index("EUR").unwrap();
        let r = best_path(&g, usd, eur, 100 * SCALE, 1, 10, 100).unwrap();
        assert_eq!(r.amount_out, 5 * SCALE);
        assert_eq!(r.steps[0].amount_in_used, 5 * SCALE);
        assert_eq!(r.steps[0].amount_in - r.steps[0].amount_in_used, 95 * SCALE);
    }

    #[test]
    fn tie_breaks_by_hops_then_sequence() {
        // 两条直连边产出相同：跳数相同，取边 id 较小者。
        let g = Graph::build(&[
            q("USD", "EUR", 900_000, 900_001, 1),
            q("USD", "EUR", 900_000, 900_002, 2),
        ]);
        let usd = g.currency_index("USD").unwrap();
        let eur = g.currency_index("EUR").unwrap();
        let r = best_path(&g, usd, eur, SCALE, 2, 10, 100).unwrap();
        assert_eq!(r.amount_out, 900_000);
        assert_eq!(r.hops, 1);
        assert_eq!(r.edges, vec![2]); // 规范序最小的正向边
    }
}
