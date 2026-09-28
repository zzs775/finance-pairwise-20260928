//! 套利环检测：对数域筛选候选 + 定点逐边确认。

use std::collections::HashSet;

use crate::{
    graph::{EdgeKind, Graph},
    pathfind::{log_rate, simulate, Step},
    quote::SCALE,
};

/// 正收益套利环（已旋转为字典序最小表示）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ArbitrageCycle {
    pub start: usize,
    pub edges: Vec<usize>,
    pub hops: usize,
    pub amount_in: u64,
    pub amount_out: u64,
    pub profit: u64,
    pub steps: Vec<Step>,
}

/// 寻找最佳（利润最大，平局按跳数、规范边序列）正收益简单环。
pub fn best_arbitrage(
    graph: &Graph,
    max_hops: usize,
    now: u64,
    max_age: u64,
) -> Option<ArbitrageCycle> {
    best_arbitrage_impl(graph, max_hops, now, max_age, true)
}

pub(crate) fn best_arbitrage_impl(
    graph: &Graph,
    max_hops: usize,
    now: u64,
    max_age: u64,
    prune: bool,
) -> Option<ArbitrageCycle> {
    let n = graph.currencies.len();
    let mut raw: Vec<Vec<usize>> = Vec::new();
    for start in 0..n {
        // U[h][v]：从 v 出发 <=h 条边回到 start 的最大对数乘积（允许重复点，
        // 仅作为简单路径上界使用）。
        let bounds = if prune {
            Some(log_bounds_to(graph, start, max_hops, now, max_age))
        } else {
            None
        };
        let mut visited = vec![false; n];
        visited[start] = true;
        let mut path = Vec::new();
        enumerate_from(
            graph,
            EnumCtx {
                start,
                max_hops,
                now,
                max_age,
                bounds: bounds.as_deref(),
                visited: &mut visited,
                path: &mut path,
                out: &mut raw,
                log_sum: 0.0,
            },
            start,
            0,
        );
    }

    // 旋转去重：同一几何环只保留字典序最小的边序列。
    let mut seen: HashSet<Vec<usize>> = HashSet::new();
    let mut best: Option<ArbitrageCycle> = None;
    for cyc in raw {
        let key = canonical_rotation(&cyc);
        if !seen.insert(key.clone()) {
            continue;
        }
        if let Some(cand) = evaluate_rotated(graph, &key) {
            if best
                .as_ref()
                .map(|b| cycle_is_better(&cand, b))
                .unwrap_or(true)
            {
                best = Some(cand);
            }
        }
    }
    best
}

/// 比较环：利润降序、跳数升序、规范边序列字典序。
pub(crate) fn cycle_is_better(a: &ArbitrageCycle, b: &ArbitrageCycle) -> bool {
    if a.profit != b.profit {
        return a.profit > b.profit;
    }
    if a.hops != b.hops {
        return a.hops < b.hops;
    }
    a.edges < b.edges
}

/// Bellman-Ford 风格反向松弛：<=h 跳回到 target 的最大对数乘积。
fn log_bounds_to(
    graph: &Graph,
    target: usize,
    max_hops: usize,
    now: u64,
    max_age: u64,
) -> Vec<Vec<f64>> {
    let n = graph.currencies.len();
    let neg = f64::NEG_INFINITY;
    let mut ub = vec![neg; n];
    ub[target] = 0.0;
    let mut bounds = vec![ub.clone()];
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
    bounds
}

struct EnumCtx<'a> {
    start: usize,
    max_hops: usize,
    now: u64,
    max_age: u64,
    bounds: Option<&'a [Vec<f64>]>,
    visited: &'a mut Vec<bool>,
    path: &'a mut Vec<usize>,
    out: &'a mut Vec<Vec<usize>>,
    log_sum: f64,
}

fn enumerate_from(graph: &Graph, ctx: EnumCtx, cur: usize, hops: usize) {
    // 先考虑回到 start 的闭边，构成 hops+1 跳的简单环。
    if hops > 0 && hops < ctx.max_hops {
        for &id in &graph.adj[cur] {
            let e = &graph.edges[id];
            if e.to == ctx.start && graph.is_edge_live(id, ctx.now, ctx.max_age) {
                let mut cyc = ctx.path.clone();
                cyc.push(id);
                ctx.out.push(cyc);
            }
        }
    }
    if hops + 1 >= ctx.max_hops {
        return;
    }
    // 对数上界剪枝：理想最佳乘积 <= 1 时不可能有严格正收益。
    if let Some(bounds) = ctx.bounds {
        let remain = ctx.max_hops - hops;
        if ctx.log_sum + bounds[remain][cur] <= 0.0 {
            return;
        }
    }
    for &id in &graph.adj[cur] {
        let e = &graph.edges[id];
        if e.to == ctx.start
            || ctx.visited[e.to]
            || !graph.is_edge_live(id, ctx.now, ctx.max_age)
        {
            continue;
        }
        ctx.visited[e.to] = true;
        ctx.path.push(id);
        let next = EnumCtx {
            start: ctx.start,
            max_hops: ctx.max_hops,
            now: ctx.now,
            max_age: ctx.max_age,
            bounds: ctx.bounds,
            visited: &mut *ctx.visited,
            path: &mut *ctx.path,
            out: &mut *ctx.out,
            log_sum: ctx.log_sum + log_rate(e),
        };
        enumerate_from(graph, next, e.to, hops + 1);
        ctx.path.pop();
        ctx.visited[e.to] = false;
    }
}

/// 旋转到字典序最小的边序列。
pub fn canonical_rotation(edges: &[usize]) -> Vec<usize> {
    if edges.is_empty() {
        return Vec::new();
    }
    let m = edges.len();
    let mut best = 0usize;
    for r in 1..m {
        let mut k = 0;
        while k < m && edges[(r + k) % m] == edges[(best + k) % m] {
            k += 1;
        }
        if k < m && edges[(r + k) % m] < edges[(best + k) % m] {
            best = r;
        }
    }
    (0..m).map(|k| edges[(best + k) % m]).collect()
}

/// 反推使每边投入都不超过 cap_in 的最大起点投入（精确界）。
pub fn max_feasible_input(graph: &Graph, edges: &[usize]) -> Option<u64> {
    // lim：对当前边之后续链路的产出上限（下一币种计）。
    let mut lim: u128 = u64::MAX as u128;
    for &id in edges.iter().rev() {
        let e = graph.edges.get(id)?;
        // f(in)=floor(in*num/den)：保证 f(in) <= L 的最大 in 为
        // floor(((L+1)*den - 1)/num)。
        let (num, den): (u128, u128) = match e.kind {
            EdgeKind::BaseToQuote => (e.price as u128, SCALE as u128),
            EdgeKind::QuoteToBase => (SCALE as u128, e.price as u128),
        };
        let bound = (((lim + 1) * den - 1) / num).min(u64::MAX as u128);
        lim = bound.min(e.cap_in as u128);
        if lim == 0 {
            return Some(0);
        }
    }
    Some(lim as u64)
}

/// 对候选环做定点逐边模拟；收益不严格为正返回 None。
///
/// 输入先按规范旋转，再取容量允许的最大投入；模拟结果 out > in 才算证据。
pub fn confirm_cycle(
    graph: &Graph,
    edges: &[usize],
) -> Option<ArbitrageCycle> {
    let key = canonical_rotation(edges);
    evaluate_rotated(graph, &key)
}

/// 在旋转后的环上完成容量反推与逐边确认。
pub(crate) fn evaluate_rotated(
    graph: &Graph,
    edges: &[usize],
) -> Option<ArbitrageCycle> {
    let amount_in = max_feasible_input(graph, edges)?;
    if amount_in == 0 || edges.is_empty() {
        return None;
    }
    let (amount_out, steps) = simulate(graph, edges, amount_in)?;
    // 环的首尾币种必须一致，且所有容量约束未被突破。
    let first = graph.edges[edges[0]];
    let last = graph.edges[*edges.last()?];
    if first.from != last.to {
        return None;
    }
    if amount_out <= amount_in {
        return None;
    }
    Some(ArbitrageCycle {
        start: first.from,
        edges: edges.to_vec(),
        hops: edges.len(),
        amount_in,
        amount_out,
        profit: amount_out - amount_in,
        steps,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::quote::Quote;

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
    fn finds_triangular_arbitrage() {
        let g = Graph::build(&[
            q("A", "B", 1_100_000, 2_000_000, 1),
            q("B", "C", 1_100_000, 2_000_000, 2),
            q("C", "A", 830_000, 2_000_000, 3),
        ]);
        let cyc = best_arbitrage(&g, 4, 10, 100).expect("arb exists");
        assert_eq!(cyc.hops, 3);
        assert!(cyc.profit > 0);
        assert_eq!(cyc.amount_out - cyc.amount_in, cyc.profit);
        assert_eq!(cyc.edges[0], *cyc.edges.iter().min().unwrap());
        assert_eq!(cyc.steps.len(), 3);
    }

    #[test]
    fn rejects_pseudo_arbitrage_after_floor() {
        // 理想乘积略大于 1，但容量把投入压到 1 个最小单位，逐边取整后收益归零。
        let mut e1 = q("A", "B", 1_000_100, 2_000_000, 1);
        e1.max_qty = 1;
        let e2 = q("A", "B", 1_000_000, 1_000_051, 2);
        let g = Graph::build(&[e1, e2]);
        assert!(best_arbitrage(&g, 3, 10, 100).is_none());
    }

    #[test]
    fn rotated_representation_is_lex_min() {
        assert_eq!(canonical_rotation(&[3, 1, 2]), vec![1, 2, 3]);
        assert_eq!(canonical_rotation(&[5, 4, 5, 1]), vec![1, 5, 4, 5]);
    }
}
