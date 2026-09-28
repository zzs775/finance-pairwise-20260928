# Discrete Portfolio Rebalancer

A C++20, standard-library-only discrete portfolio rebalancer. Given a local
synthetic book (holdings, cash, prices), integer target weights, lot sizes,
tiered fees and per-asset / group bounds, it produces a reproducible set of
trades by solving an integer branch-and-bound search. A full-enumeration
oracle for small instances independently certifies the answer in the tests.

## Fixed-point arithmetic

All amounts, prices, weights and rates are integers (`std::int64_t`):

- prices and cash/notionals are scaled by `1_000_000` (`kValueScale`),
- weights are basis points (`10_000` = 100%, `kWeightScale`),
- fee rates are parts per million (`1_000_000` = 100%, `kFeeRateScale`).

Every multiply/add that can leave the 64-bit domain is checked: multiplications
are widened to 128-bit intermediate values and post-trade math uses 128-bit
accumulators. An overflow is a classified input/evaluation failure
(`ErrorKind::Overflow` / `EvalResult::overflow`), never silent wraparound.

## Constraints and slacks

For each candidate signed trade vector (multiples of each asset's lot):

- post-trade holdings must be non-negative and deltas lot-aligned;
- post-trade cash must be non-negative:
  `cash' = cash0 + sells - buys - fee(buy) - fee(sell) >= 0`, i.e. total buys
  plus fees never exceed sells plus initial cash;
- each asset's post weight must be inside `[min_weight, max_weight]`;
- each declared group's aggregate post weight must be inside its limits.

The result reports a recomputable slack for every constraint: per-asset
`post_weight - min` / `max - post_weight`, the same for groups, plus
`cash_slack` and `funding_slack`. All slacks are non-negative for a feasible
reported trade.

## Input validation failures

The following are rejected up front with classified `ErrorKind` values:
illegal target-weight sum (must be exactly 10000 bps), negative or zero
prices, duplicate/empty asset ids, non-positive lots, negative holdings,
bad asset/group bounds, groups referenced without limits (and vice versa),
duplicate groups, malformed fee tiers, overlapping fee-tier interiors,
negative cash/budget, and multiply/add overflow.

Fee tiers are half-open marginal intervals `[lo, hi)` that must partition
`[0, +inf)` (the last tier ends at `kOpenEnd`); touching boundaries are fine.

## Objective (strictly lexicographic, never a weighted sum)

1. total post-weight deviation (`sum |weight_bps - target_bps|`),
2. total fees,
3. total turnover (buy notional + sell notional),
4. canonical trade vector: signed lot deltas in ascending asset-id order.

The first three components are compared one at a time; there is no scalar
weighted combination. Final ties are resolved by the lexicographically
smallest canonical vector, which is stable under input reordering because the
normalized problem always sorts assets by id. All iteration is single
threaded, so thread counts cannot affect the output.

## Branch and bound

`Rebalancer::solve` branches asset by asset (assets sorted by id), assigning
each asset a lot count in `[lo_lots, hi_lots]`. `lo_lots` never sells more
than the holding; `hi_lots` never exceeds what the funding and total value
can support. Children are expanded in ascending canonical order so the first
minimum found is the stable tie-break winner.

Each node carries a provable lower bound:

- **deviation**: per undecided asset, the distance of the attainable weight
  interval `[w_lo, w_hi]` to the target. The upper weight uses the smallest
  possible post-fee denominator (`t0` minus the worst-case fee), so fees can
  never make a pruned node actually optimal.
- **fees**: fees on notional already bought/sold (marginal tier rates are
  non-negative, so later decisions cannot reduce them);
- **turnover**: notional already committed on either side.

Infeasibility rules prune a partial prefix provably: fixed asset above its
max weight, fixed asset below its min weight measured against the largest
possible denominator `t0`, group bounds relaxed over the maximum attainable
group value, and a cash relaxation (sell every remaining lot, pay no further
fee). Full feasibility is re-checked at every leaf by `evaluate_trade`.

**State dominance / tie-break pruning:** when a node's lower bound equals the
incumbent objective, the smallest canonical key reachable below it is its
prefix extended by the minimum remaining lot counts. If that key is not
strictly smaller than the incumbent's key, the subtree cannot contain a
better or tie-winning solution and is pruned (`fate = dominated`).

Every expanded node is reported (`NodeReport`) with depth, partial prefix,
its lower-bound triple and fate (`branched`, `leaf`, `pruned-bound`,
`pruned-infeasible`, `dominated`). Search statistics count nodes, leaves and
each pruning class.

### Node budget

With `node_budget > 0` the search stops once the node count reaches it and
returns `budget_exhausted`: it reports the current incumbent, the
lexicographic minimum lower bound over the unexplored frontier, and all
stats. Such a run is never marked proved, and it never overwrites the last
fully proved result kept by the `Rebalancer` instance (`last_proven()`).

## Enumeration oracle

`enumerate_oracle(problem, range_lots)` enumerates every lot combination in
`[-range_lots, +range_lots]` per asset (mixed-radix canonical order), checks
feasibility with the same evaluator and keeps the strict lexicographic
minimum. With no pruning at all it is a certified optimum inside the
candidate window. The test suite runs 100+ deterministic synthetic instances
(seeded LCG) and requires solver and oracle to agree on objective **and**
exact trade vector, and that permuting the input assets changes nothing.

## CLI

Line-oriented input (blank lines and `#` comments ignored):

```
cash <scaled>
budget <nodes>            # optional, 0/omitted = unlimited
window <lots>             # optional candidate half-width
oracle <lots>             # optional: run the oracle instead of B&B
asset <id> <price> <holding> <lot> <target_bps> <min_bps> <max_bps>
groupof <id> <group>
grouplimit <group> <min_bps> <max_bps>
fee <lo_notional> <hi_notional|inf> <rate_ppm>
```

```sh
rebalancer_cli examples/two_assets.txt
cat examples/lots_and_groups.txt | rebalancer_cli
```

Output begins with `status` (`proved_optimal` / `budget_exhausted` /
`infeasible`), `proven`, the objective triple, the lower-bound triple and
search counters, followed by per-asset trades, weights, slacks and group /
cash slacks.

## Build and test

```sh
cmake -S . -B build
cmake --build build
ctest --test-dir build --output-on-failure
```

Tests cover: tiered/step fees, cash-tight funding, lot conflicts, symmetric
equal-objective tie-breaking, infeasibility evidence, budget exhaustion,
classification of every validation failure, and solver/oracle agreement over
hundreds of randomly generated but locally seeded synthetic books.

## Layout

- `include/rebalancer/types.hpp` — fixed-point types, errors, results
- `src/validation.cpp` — classification and normalization
- `src/fees.cpp` — overlapping-tier check and marginal tiered fee
- `src/model.cpp` — feasibility, objective and slack evaluator
- `src/solver.cpp` — integer branch-and-bound with bounds and dominance
- `src/oracle.cpp` — exhaustive enumeration oracle
- `src/io.cpp` / `src/cli_main.cpp` — parser, renderer and CLI
- `tests/` — C++ test suites and a CLI end-to-end script
- `examples/` — local synthetic input fixtures
