# 期权行权责任分配引擎（Option Exercise Assignment Engine）

纯 JDK 21 标准库实现（无任何第三方依赖）的确定性引擎：消费本地合成的行权通知，
把合约数量按合格空头确定性地分配给账户，并生成现金、实物或复合交割义务。

## 构建与运行

```powershell
# 编译到 out/
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 运行全部自动化测试（411 项）
powershell -ExecutionPolicy Bypass -File .\test.ps1

# CLI 内置演示（比例余数 / 复合交割 / 重复拒绝 / 故障回滚 / 检查点恢复）
java -cp out exer.cli.Main demo
java -cp out exer.cli.Main selftest
```

## 领域模型（全部为不可变 record）

- `Contract`：合约 ID、标的、到期日（epoch day）。
- `Account`：账户 ID 与目标合约上的可分配空头（`BigInteger`）。
- `ExerciseNotice`：幂等键、合约 ID、行权数量（`BigInteger`）。
- `Deliverable`：每份合约的证券腿 + 现金腿。每条腿以
  `unscaled * 10^-scale` 的定标整数表示比率；`settleScale` 为结算精度，
  允许结算粗于比率精度，由此产生的尾差逐账户进入台账。
- `Obligation`：某条通知下某账户的证券/现金交割义务。
- `TailLedgerEntry`：尾差台账，恒等式
  `exact == delivered * 10^(scale-settleScale) + remainder` 可逐账户追踪。

数量一律使用 `BigInteger`，金额/证券比率使用定标整数，全程不使用浮点。

## 分配规则（`Allocator`）

1. 账户按 `accountId` 字典序规范化，账户输入顺序不影响结果。
2. 基础配额：`base_i = floor(Q * short_i / totalShort)`。
3. 整数余数：从字典序首个仍有空位的账户起做稳定轮转，逐份发放直到余数为 0。
   余数轨迹记录在 `AllocationPlan.cursorTrail()`。
4. 恒等式：`sum(share.quantity) == notice.quantity`，且
   `share.quantity_i <= short_i`。
5. 同一账户在同一合约上的已分配数量跨批次累计；后续批次只看到剩余空头。

## 交割生成（`ObligationGenerator`）

- 现金、实物、复合三种组合，每条腿独立精确汇总。
- 义务总量恒等于 `分配数量 * deliverable`（在腿的比率精度下）；
  当结算精度较粗时，未交付的尾差写入 `TailLedgerEntry` 台账，来源精确到
  通知、账户、腿。

## 幂等、暂存与原子提交（`ExerciseEngine` + `CheckpointStore`）

- 通知以幂等键去重：批次内重复、跨批次重放均分类为 `DUPLICATE_NOTICE`。
- 批次内通知必须属于同一合约；处理前按键排序，因此无论提交顺序如何，
  最终状态完全一致。
- 所有校验、分配、义务生成都发生在暂存副本上，只有全部成功后才一次替换状态。
- `FailureInjector` 可在 `ALLOCATE` / `GENERATE_OBLIGATIONS` / `WRITE_LOG`
  三点注入异常；任一点失败后对外状态与调用前逐字节相同（测试以快照断言）。
- `CheckpointStore` 用确定性的 UTF-8 文本快照导出/导入全部状态
  （合约、deliverable、累计分配、幂等键、义务）。重复重放与检查点恢复后
  再次提交，快照与首次成功提交逐字节一致。

## 分类拒绝（`RejectReason`）

`DUPLICATE_NOTICE`、`OVER_EXERCISE`、`EXPIRED_CONTRACT`、
`ILLEGAL_DELIVERABLE`、`ZERO_ELIGIBLE_SHORT`、`UNKNOWN_CONTRACT`、
`INVALID_NOTICE`、`INJECTED_FAILURE`。

## 朴素 Oracle（`NaiveOracle`）

独立的逐份展开实现：逐份决定归属、逐份累加每条交割腿。测试在所有小批次
组合上差分对比引擎与 oracle 的份额、余数轨迹、轮转游标、义务与尾差台账。

所有账户与合约均为本地虚构数据（`ACC-ALPHA`、`OPT-FICT-001` 等）。

## 测试覆盖（`test/exer/Tests.java`）

- 比例余数、穷举 1..N 的分配恒等式与不超空头
- 账户输入重排结果不变、规范化余数游标跨通知
- 零空头、负空头、零数量拒绝
- 现金 / 实物 / 复合交割总量、尾差台账恒等式
- 批次内与跨批次重复通知、单次与累计过量行权
- 失效合约、未知合约、非法比率/精度/空 deliverable
- 三个注入点的故障回滚、失败后重试成功
- oracle 差分、任意顺序重放与检查点恢复的逐字节一致性
