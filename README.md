# 单市场多级熔断状态机

纯 Go 1.26 标准库实现：依据本地合成成交、版本化参考价与滚动窗口跌幅，
驱动单一市场的暂停、冷静期与恢复。无外部依赖、无真实行情，全部输入均为
逻辑时间驱动的合成事件。

## 状态与迁移

五种显式状态（见 `state.go`）：

| 状态 | 可交易 | 含义 |
| --- | --- | --- |
| `open` | 是 | 正常交易 |
| `halted_level1` | 否 | 一档暂停，到期后进入冷静期 |
| `halted_level2` | 否 | 二档暂停（暂停期间可由一档升级），到期后进入冷静期 |
| `closed` | 否 | 三档（最严重），市场关闭，只能由恢复命令重置入冷静期 |
| `cooldown` | 否 | 冷静期，到期自动回到 `open` |

合法迁移：

- `open → halted_level1 / halted_level2`：滚动窗口最低价相对当前规则版本的
  参考价跌幅达到一/二档阈值（**阈值等号即触发**）。
- `halted_level1 → halted_level2`：暂停期间跌幅扩大到二档，暂停**延长**并
  在解释中记录升级原因。
- 任意暂停态 → `closed`：跌幅达到三档；窗口同时清空。
- `halted_level1/2 → cooldown`：暂停计时器到期。
- `closed → cooldown`：仅由 `recover` 恢复命令触发。
- `cooldown → open`：冷静期计时器到期，或最新窗口最低价使跌幅严格小于
  恢复阈值后由成交/恢复命令确认。
- 暂停期间跌幅回落到恢复阈值以下时，下一笔成交即可解除暂停并发布重开解释。

任何非 `open` 状态发布的通知都不可能声称可交易（`publish` 内置断言）。

## 定点数与规则

- 价格、参考价为整数（`Price`）；阈值单位为基点（`BasisPoints`，
  1 bp = 万分之一），跌幅用 64 位整数乘除计算，溢出分类为
  `arithmetic_overflow` 拒绝。
- 一套 `RuleSet` 包含：滚动窗口长度 `Horizon`、三档阈值（必须严格递增）、
  恢复阈值（必须小于一档阈值）、一/二档暂停时长（二档不短于一档）与冷静期
  时长；非法区间以 `illegal_rules` 拒绝。

## 事件与拒绝分类

事件字段：逻辑时间 `Time`、源序号 `Source`、规则版本 `RuleVersion`、
价格 `Price`、规则载荷 `Rules`。事件种类：`trade` / `reference` /
`rules` / `recover`。

拒绝分类（`RejectReason`）：

- `time_regression`：逻辑时间倒退（时钟和事件均拒绝倒退）。
- `duplicate_event`：相同源序号且逐字段相同的重复输入，幂等生效一次。
- `duplicate_conflict`：相同源序号但载荷冲突。
- `missing_reference`：当前规则版本在事件时刻没有生效参考价。
- `illegal_rules`：规则版本回退、未知版本或非法阈值/时长区间。
- `arithmetic_overflow`：跌幅计算溢出。
- `illegal_event`：其余非法输入（如 `open` 状态下的恢复命令）。

## 同刻冻结优先级与确定性计时

- 同一逻辑时间的事件按固定优先级应用，与到达顺序、goroutine 调度无关：
  **计时器到期 → 参考价 → 规则切换 → 成交 → 恢复命令**（见 `batch.go`
  的 `ApplyBatch`，稳定排序）。
- 计时堆为稳定最小堆（`timerheap.go`，基于 `container/heap`），按
  （到期时间，单调插入序号）排序；计时器被取消即标记失效，重复到期扫描
  最多生效一次。
- 参考价与规则均带版本：换版只影响生效时刻之后、且声明该版本的事件；
  旧版本事件继续按旧参考价评估。

## 检查点

`MarshalCheckpoint` / `RestoreCheckpoint` 保存滚动窗口、五种状态、计时器、
版本化参考价、全部规则版本、输入摘要（接受/拒绝计数、最大源序号）与去重
表。编码为 gob + 版本头 + SHA-256 校验和，map 类内容排序后编码，保证
**确定性**：从检查点恢复后继续重放，输出通知流与全量重放逐字节一致；
恢复得到的检查点字节也与全量重放完全相同。

- 损坏、截断或校验和不符的检查点返回 `ErrCorruptCheckpoint`，不会启动。
- `SaveCheckpoint` 先写临时文件、`Sync` 后原子 `Rename`；编码或写入失败
  不会覆盖既有完整检查点。
- 状态迁移先暂存可变状态，**发布成功才提交**；发布失败回滚，不会留下
  半成品状态，也不会覆盖最近的完整状态。

## CLI

```sh
go build ./...
go run ./cmd/cb -init testdata/init.json -checkpoint state.ckpt < testdata/scenario.jsonl
```

- `-init`：初始参考价与规则版本 JSON（示例见 `testdata/init.json`）。
- `-checkpoint`：存在则启动时恢复，处理结束后原子保存最新完整状态。
- stdin：每行一个 JSON 事件（见 `testdata/scenario.jsonl`）。
- stdout：每行一条状态通知 JSON；拒绝事件写入 stderr 并继续处理。

## 测试

```sh
go test ./...
```

覆盖：阈值等号触发、窗口边界淘汰、同刻冻结优先级、规则换版与参考价生效
边界、一档→二档升级与暂停延长、暂停到期→冷静期→重开、三档关闭、重复/
冲突/时间倒退拒绝、计时堆稳定顺序与单次触发、检查点损坏检测、失败发布
回滚、检查点恢复与全量重放字节一致，以及 CLI 端到端夹具运行。
