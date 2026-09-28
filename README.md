# 交易前多维风险预占引擎（pre-trade risk reservation engine）

纯 Go 1.26、仅标准库实现的本地合成预占引擎。对一笔报单**原子**预占三个
维度的额度——现金、持仓、风险限额——并管理 `reserved →
confirmed/released/expired` 的终结状态机。所有账户与报单均为本地合成数据，
无网络、无外部依赖。

## 定点数与受检查算术

- `Amount int64`：现金/价格/风险限额的定点整数，刻度 10⁻³（milli）。
- `Qty int64`：数量定点整数，刻度 10⁻⁶（micro）。
- 名义金额 = `Qty × Price`，由 `arith.go` 的 `mul64`/`add64` 用受检查算术
  计算；任何 int64 溢出都分类为 `ErrOverflow`，不发生静默回绕。
- 负值（数量、价格、额度、持仓、时钟回拨）统一分类为
  `ErrNegativeValue`。

## 错误分类

| 代码 | 触发条件 |
| --- | --- |
| `ErrNegativeValue` | 空键/空账户/空证券、非正数量价格、负值、非法方向、时钟回拨 |
| `ErrUnknownAccount` | 账户不存在 |
| `ErrDuplicateConflict` | 同一幂等键绑定了不同参数（账户/证券/方向/数量/价格/到期时刻） |
| `ErrOverflow` | 金额乘法或加法越过 int64 范围 |
| `ErrInsufficientCash` | 买入 `committed + reserved + notional > cashLimit` |
| `ErrInsufficientPosition` | 卖出 `committed + reserved + qty > 可用持仓` |
| `ErrInsufficientLimit` | 任意方向 `committed + reserved + notional > riskLimit` |
| `ErrInvalidTransition` | 对已终结预占再次终结、到期时刻已过等 |
| `ErrUnknownOrder` | 终结操作引用未知幂等键 |

## 三维原子扣减

`Reserve` 在校验阶段**先检查全部维度、后写状态**：

1. 参数与幂等键校验、未知账户、名义金额溢出；
2. 买入检查现金维度（卖出不占用现金）；
3. 卖出检查持仓维度（买入不占用持仓）；
4. 买卖都检查风险限额维度。

任一维度不足立即返回分类错误，三维状态均不改变。校验全部通过后才一次性
写入：买入增加 `reservedCash`，卖出增加 `reservedPosition`，两者都增加
`reservedRisk`。任何时刻都保证

```
committed + reserved <= limit   （现金、风险）
committed + reserved <= 持仓     （卖出方向）
```

## 状态机与幂等

```
                confirm
   reserved ───────────────► confirmed   (终态)
      │  ├ release
      │  └──────────────► released       (终态，资源返还)
      └ expire(tick)
         └─────────────► expired         (终态，资源返还)
```

- 每个预占**只能终结一次**。
- 相同幂等键 + 相同参数的重复 `Reserve` 返回**首个结果**（同一指针、同一
  审计序号），不二次占用额度。
- 相同键但参数不同返回 `ErrDuplicateConflict`。
- 对终态重复同一动作（如再次 `Confirm`）幂等返回首个结果；试图切换到另一
  终态返回 `ErrInvalidTransition`。

## 逻辑时钟、稳定最小堆与同刻裁决

- 时间来自可注入的 `Clock` 接口（`ManualClock` / `FuncClock`），引擎从不
  读取墙上时间。
- 到期项放在按 `(expiresAt, seq)` 排序的稳定最小堆（`heap.go`）：先按到期
  时刻，再按预占被冻结的审计序号。
- 裁决规则只依赖注入时钟：在到期 tick 本身（`now == expiresAt`），更早冻结
  的预占仍可 `Confirm`；只有 `expiresAt < now` 才会被 `ExpireDue` 回收。同刻
  多个到期项按冻结序号稳定回收。因此 goroutine 调度与墙上时间都无法改变
  最终状态。

## 并发模型与线性化点

引擎内部用单把互斥令牌（容量 1 的 channel）保护全部状态。**每次被接纳的
操作**（成功或被分类拒绝）都在临界区内在唯一的线性化点上分配单调审计序号
`seq`，并追加一条 `AuditEntry`。审计序号无空洞、不重复，构成该历史唯一的
全序。

`model` 包提供：

- `VerifyLinearizable`：把并发历史按审计序号串行重放到串行参考引擎，要求
  复现每个观测到的结果（含错误分类），并产出**完全一致**的规范快照；同时
  校验每客户端的程序顺序。
- 固定并发屏障测试（`model/concurrent_test.go`）：所有 worker 在
  `start` barrier 处同时释放争抢额度，并额外压测 confirm/expire 同键竞态；
  每个预占必须恰好终结一次。

## 规范快照与恢复

- `Snapshot()` 产生确定性规范快照：账户、持仓、订单按 ID/序号排序，空切片
  归一为 `nil`，与 map 迭代顺序无关。
- `Encode/DecodeSnapshot` 只使用基础整型 wire 结构经 `encoding/gob` 序列化，
  跨版本稳定；两次编码逐字节相同。
- `Restore` 从快照重建引擎，包括到期堆与审计序号；恢复后审计序号继续单调
  递增，到期回收照常工作。

## 目录

```
types.go               定点类型、错误分类、状态/方向枚举、请求结果
arith.go               受检查 int64 算术
clock.go               Clock 接口与 ManualClock
heap.go                (expiresAt, seq) 稳定最小堆
engine.go              引擎：原子预占、状态机、到期回收、审计序号
snapshot.go            规范快照、gob 编解码、恢复
model/ref.go           串行参考模型与线性化校验器
model/concurrent_test.go 屏障并发测试、confirm/expire 竞态
cmd/riskcli/main.go    本地合成演示 CLI
```

## 运行

```
go test ./...          # 全部自动化测试
go test -race ./...    # 竞态检测（需要可用的 cgo 工具链）
go run ./cmd/riskcli   # 脚本化演示
```

## 测试覆盖

- 三维不足：现金、持仓、风险限额分别被正确分类，且拒绝不改变任何维度。
- 边界：`committed + reserved == limit` 恰好通过。
- 幂等：重复返回首结果；参数冲突报告 `ErrDuplicateConflict`。
- 状态机：重复确认幂等、确认后释放被拒、释放后资源可再次占用、未知键。
- 到期竞态：到期 tick 的冻结优先级、过 tick 回收、重复 sweep 无副作用。
- 溢出：名义金额乘法与加法溢出分类为 `ErrOverflow`。
- 并发争抢：固定屏障 16 worker × 25 报单，仅风险限额允许的子集被接纳，
  历史可映射到合法串行顺序，总额度、审计序号与规范快照完全一致。
- 快照：确定性、gob 往返、恢复后堆与审计序号正确。
