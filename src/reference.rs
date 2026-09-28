//! 穷举简单路径 / 简单环参考器（不做任何对数剪枝）。

use crate::{
    arbitrage::{best_arbitrage_impl, ArbitrageCycle},
    graph::Graph,
    pathfind::{is_better, simulate, PathResult},
};

/// 穷举所有简单路径，按与快算法相同的规则选优。
pub fn best_path_reference(
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
    let mut visited = vec![false; n];
    visited[from] = true;
    let mut path = Vec::new();
    enumerate_paths(
        graph,
        RefCtx {
            to,
            amount_in,
            max_hops,
            now,
            max_age,
            visited: &mut visited,
            path: &mut path,
            best: &mut best,
        },
        from,
        0,
        amount_in,
    );
    best
}

/// 穷举所有简单环（字典序最小旋转各一次），选最佳正收益环。
pub fn best_arbitrage_reference(
    graph: &Graph,
    max_hops: usize,
    now: u64,
    max_age: u64,
) -> Option<ArbitrageCycle> {
    best_arbitrage_impl(graph, max_hops, now, max_age, false)
}

struct RefCtx<'a> {
    to: usize,
    amount_in: u64,
    max_hops: usize,
    now: u64,
    max_age: u64,
    visited: &'a mut Vec<bool>,
    path: &'a mut Vec<usize>,
    best: &'a mut Option<PathResult>,
}

fn enumerate_paths(graph: &Graph, ctx: RefCtx, cur: usize, hops: usize, amount: u64) {
    if cur == ctx.to && hops > 0 {
        let (_, steps) = simulate(graph, ctx.path, ctx.amount_in).unwrap();
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
                steps,
            });
        }
    }
    if hops == ctx.max_hops {
        return;
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
        let e = graph.edges[id];
        let applied = match e.apply(amount) {
            Some(a) => a,
            None => continue,
        };
        ctx.visited[e.to] = true;
        ctx.path.push(id);
        let next = RefCtx {
            to: ctx.to,
            amount_in: ctx.amount_in,
            max_hops: ctx.max_hops,
            now: ctx.now,
            max_age: ctx.max_age,
            visited: &mut *ctx.visited,
            path: &mut *ctx.path,
            best: &mut *ctx.best,
        };
        enumerate_paths(graph, next, e.to, hops + 1, applied.amount_out);
        ctx.path.pop();
        ctx.visited[e.to] = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
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
    fn exhaustive_matches_small_fixture() {
        let g = Graph::build(&[
            q("A", "B", 900_000, 900_001, 1),
            q("B", "C", 1_200_000, 1_200_001, 2),
            q("A", "C", 1_050_000, 1_060_000, 3),
            q("C", "D", 950_000, 950_001, 4),
        ]);
        let a = g.currency_index("A").unwrap();
        let d = g.currency_index("D").unwrap();
        for hops in 1..=4 {
            let fast = crate::pathfind::best_path(&g, a, d, 10 * SCALE, hops, 10, 100);
            let slow = best_path_reference(&g, a, d, 10 * SCALE, hops, 10, 100);
            assert_eq!(
                fast.map(|r| (r.amount_out, r.hops, r.edges)),
                slow.map(|r| (r.amount_out, r.hops, r.edges)),
                "hops={hops}"
            );
        }
    }
}
