# fx-arb：外汇可执行报价图与套利环（Rust，仅 std）

从本地不可变报价快照构建有向图，在限定跳数内计算最佳交叉汇率，
并用定点逐边模拟产出可验证的正收益套利环。零外部依赖，稳定 Rust 即可构建。

## 构建

```
cargo build --release
cargo test
```

## 定点模型

- 所有价格与金额都是尺度 `SCALE = 1_000_000` 的定点整数（6 位小数）。
- 合法价格 `1 ..= 1_000_000_000_000_000`（最高 1e9.000000）；
  合法数量 `0 ..= 1_000_000_000_000_000_000`。
- 逐边乘法使用 `u128`；任何中间结果超过 `u64` 的路径一律无效，
  不产生静默截断（见测试 `compound_overflow_invalidates_path...`）。

## 报价与五类拒绝原因

每条快照报价包含：币对 `base/quote`、`bid`、`ask`、数量上限 `max_qty`、
源序号 `source_seq`、逻辑时间 `logical_time`。校验按固定优先级分类：

| 错误 | 触发条件 |
| --- | --- |
| `ReversedPair` | 币种代码为空/非 ASCII 字母数字/超过 8 字符，或 `base == quote` |
| `ScaleOverflow` | `bid==0`、`ask==0`、价格或数量超出定点界 |
| `BidAboveAsk` | `bid > ask` |
| `Expired` | `logical_time < now - max_age`；边界 `age == max_age` 仍有效 |
| `Duplicate` | 同 `(base, quote, source_seq)` 的额外报价 |

接受集先按规范键排序再去重（同键保留规范序最小者），因此结果与输入顺序无关。

## 边展开与逐边复合

每条报价展开为两条有向边：

- `base -> quote`（卖 base 买 quote）：`out = floor(in * bid / SCALE)`，容量 `max_qty`（base）。
- `quote -> base`（卖 quote 买 base）：`out = floor(in * SCALE / ask)`，
  容量 `floor(max_qty * ask / SCALE)`（quote）。

规则：

- 保守取整（floor），每步记录 `amount_in`（到达投入）、`amount_in_used`
  （容量截断后的实际投入）、`amount_out`、`rounding_loss`（理想乘积余数）。
- 路径不得重复币种（简单路径）；超过最大跳数或经过过期边的结果无效。

## 最佳路径选择

比较顺序（确定性平局规则）：

1. 最终可得金额降序；
2. 跳数升序；
3. 规范边 id 序列字典序升序。

快算法为带剪枝的简单路径 DFS：用 Bellman-Ford 风格反向松弛预计算
“剩余跳数内的最大对数乘积”作为上界，取整与容量只会减少实际金额，
故剪枝不可能漏掉更优路径。币种按字典序编号、边按
`(from,to,kind,price,source_seq,logical_time)` 规范排序赋 id，
使图容器、报价输入顺序、并发分片合并顺序变化时，选路结果与解释 JSON 完全不变
（`quote::merge_shards` 模拟分片汇总）。

## 套利环

1. 对数域筛选：候选环的理想对数乘积上界 `<= 0` 时直接剪掉。
2. 候选环旋转为边序列字典序最小的规范表示后去重。
3. 定点确认：沿环反向精确反推“使每边投入都不超过容量”的最大起点投入
   （对 `f(x)=floor(x*num/den)`，满足 `f(x) <= L` 的最大 `x`
   为 `floor(((L+1)*den-1)/num)`），再逐边正向 floor 模拟；
   只有 `amount_out > amount_in`（严格为正）才算证据。
4. 环按利润降序、跳数升序、规范边序列升序选优；输出每步投入、产出与取整损耗。

因此对数域只是筛选器，最终判定完全由定点模拟给出，伪套利（取整后收益归零）
会被拒绝。

## 快算法 vs 穷举参考器

- `reference::best_path_reference` / `best_arbitrage_reference` 不做任何剪枝，
  穷举全部简单路径与简单环。
- `tests/equivalence.rs` 用确定性 xorshift 生成 80 组、2~6 个币种的合成夹具，
  对所有 `(from,to,amount,hops)` 组合逐案比对两种算法结果；
  每条套利证据再由独立的 `simulate`/`confirm_cycle` 复算通过。
- `tests/edge_cases.rs` 覆盖 bid/ask 方向、时效边界、跳数限制、容量、
  平局、伪套利舍入、尺度溢出与复合溢出。

## CLI

快照文件每行一条报价，空白分隔：

```
BASE QUOTE BID ASK MAX_QTY SOURCE_SEQ LOGICAL_TIME
```

`#` 开头为注释，全部数字为定点整数。

```
fx-arb best-path  <file> --from A --to C --amount 1000000 \
                        [--now N] [--max-age N] [--max-hops N] [--reference]
fx-arb arbitrage  <file> [--now N] [--max-age N] [--max-hops N] [--reference]
fx-arb validate   <file> [--now N] [--max-age N]
fx-arb gen        [--fixture triangular|none|pseudo]
```

`--reference` 切换到穷举参考器，便于独立复核；结果 JSON 键序固定，
大整数与 `u128` 损耗用字符串输出以避免精度歧义。

复现示例（全部为本地合成数据）：

```
fx-arb arbitrage examples/triangular.txt --max-hops 4
fx-arb best-path examples/triangular.txt --from A --to C --amount 1000000 --max-hops 3
fx-arb arbitrage examples/pseudo.txt --max-hops 3      # found=false
fx-arb validate  examples/errors.txt --now 10 --max-age 0
```

## 模块

- `src/quote.rs`：定点常量、报价校验与分类、顺序无关去重、分片合并。
- `src/graph.rs`：双向边展开、保守取整应用、规范化图容器。
- `src/pathfind.rs`：对数上界剪枝 DFS、逐边模拟、确定性平局。
- `src/arbitrage.rs`：简单环枚举、容量反推、定点确认、字典序旋转。
- `src/reference.rs`：穷举参考器。
- `src/explain.rs`：手写确定性 JSON（仅 std）。
- `src/main.rs`：CLI。
