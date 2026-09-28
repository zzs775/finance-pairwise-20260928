# cce — 版本化交易对手信用暴露引擎

按 netting set（净额结算组）聚合本地合成交易 MtM、抵押品与题内协议参数的信用暴露引擎。
仅使用 Go 标准库，Go 1.26，无外部依赖。所有金额为带币种标签的定点整数（最小货币单位），
算术全部为受检查运算（溢出即分类失败）。

## 核心公式

对每个 netting set，在估值时点 `t` 选定**唯一完整协议版本**后，按题内顺序逐步计算：

```
net              = Σ 交易 MtM                       （交易净额）
afterIndependent = net + independentAmount          （独立金额）
afterThreshold   = max(0, afterIndependent - threshold)   （阈值）
afterMTA         = max(0, afterThreshold - MTA)     （MTA）
exposure         = max(0, afterMTA - collateral)    （抵押品抵扣）
```

每一步的中间值都在快照中输出（`Steps`）。发布前，引擎用**独立的单一全量公式**
`IndependentExposure` 重算并对每一项做等价断言；任何一项不符或出现负暴露都会导致
本次提交整体失败，不发布部分结果。`max(0, …)` 保证暴露永不为负。

## 1. 定点整数与失败分类

- 金额、threshold、MTA、independent amount 均为 `Fixed{value int64, currency}`；
  加、减、取反均做 int64 边界检查（`money.go`）。
- 币种随金额携带；跨币种相加/相减/比较、协议与抵押币种不符均被拒绝。
- 每笔交易唯一归属一个 netting set：同一交易 ID 改挂到别的 netting set 属于
  `trade` 类失败；缺失协议、币种不符、重复版本、溢出各自分类（`errors.go`）。

失败分类：`overflow` / `currency` / `agreement` / `version` / `trade` / `state`，
可通过 `cce.ClassOf(err)` 获取，并可用 `errors.Is(err, cce.ErrOverflow)` 等判断。

## 2. 协议版本与生效区间

- `AgreementVersion` 在半开区间 `[Start, End)` 内有效；`End` 为零值表示开放结尾。
- 同一 netting set 的全部版本必须：币种一致、版本号不重复、按开始时间严格排序、
  前一版本 `End` 恰等于后一版本 `Start`（**重叠或空洞都拒绝**），且只能有一个开放结尾。
- `VersionAt(t)` 读取 `t` 对应的完整版本；早于首个窗口或落在空洞内返回 `agreement` 类失败。
- 输入顺序不影响结果：内部按开始时间规范化排序，报告按 netting set ID 规范化输出。
- 估值只读取交易时间所属版本：`Commit(t)` 时，只有「交易 MtM 时间所在版本 == 估值时点
  所选版本」的交易参与净额，未来时间点的 MtM 不可见。因此规则换版时，旧版本下的交易
  不会被混入新版本估值。

## 3. 分步输出与全量公式等价

`Steps` 依次记录：净额、加独立金额后、减阈值后、减 MTA 后、减抵押品后及最终暴露，
另含所选版本号、交易笔数与估值时间。`IndependentExposure` 用一条完整表达式重算最终值，
`assertEquivalent` 在每次提交时对五个阶段逐项校验。

## 4. 显式状态机与 closeout

状态：`open` → `margining` → `defaulted` → `closed`（合法迁移表见 `state.go`）。

- 允许：open↔margining，open/margining→defaulted，open/margining/defaulted→closed。
- `closed` 为终态，任何迁出都非法；`defaulted → open/margining` 非法。
- 非法迁移立即返回 `state` 类失败，**不改变当前状态、不入队、不产生部分审计**；
  预检会先把同 netting set 已暂存迁移按时间应用到副本上。
- closeout 在 `Commit` 时冻结快照：估值时间固定为 close 时间，冻结后再次提交始终读到
  同一份冻结 `Steps`；向已关闭 netting set 追加 MtM 直接拒绝，因此后续 MtM 不可能混入。

## 5. 不可变快照、并发与幂等

- `Snapshot` 一经发布不再可变（map/slice 均在构造时冻结，访问返回防御性副本），
  因此并发查询无需加锁；一次查询固定读取单个快照。
- `Store` 用 RWMutex 支持并发读、串行发布；序列号单调递增。
- 固定调度等价性：同一输入、同一估值时点，无论提交几次，快照内容都与「按提交顺序从
  空引擎全量重算」逐项相等；重复更新保持幂等（内容不变，仅序列号前进）。

## 目录

```
money.go      定点整数、币种标签、受检查算术
errors.go     失败哨兵、分类与构造器
trade.go      合成交易类型
agreement.go  版本链校验与 VersionAt
exposure.go   分步公式、独立全量公式、等价断言
state.go      状态机与 closeout 冻结
store.go      不可变快照、规范排序、并发读/串行发布
engine.go     暂存/校验/全量重算/提交编排
cmd/cce       CLI：demo 与 run
testdata/     本地合成夹具 scenario.json
*_test.go     28 个表覆盖下列场景
```

## CLI

```sh
go run ./cmd/cce demo            # 内置合成场景：规则换版 + 双币种 netting set
go run ./cmd/cce run testdata/scenario.json
```

场景 JSON 字段：`as_of`（估值时点 RFC3339）、`agreements`（版本与 threshold/mta/
independent_amount）、`trades`（id、netting_set、currency、mtm、at）、`collateral`、
`events`（状态迁移：open/margining/defaulted/closed 及时间）。输出为规范排序的快照 JSON。

## 测试

```sh
go test ./...      # 28 个用例
go vet ./...
gofmt -w .
```

覆盖范围：

- 协议边界：半开区间端点、早于首窗口、重复版本、重叠、空洞、开放结尾后再有版本。
- 跨币种：协议币种不一致、交易币种不符、抵押币种不符。
- MTA 临界：减 MTA 后恰为 0 时暴露为 0；负净额 + 足额抵押暴露被地板为 0。
- closeout：冻结估值时间、冻结后拒绝追加 MtM、重复提交不漂移。
- 规则换版：同一时点序列在 v1/v2 下分别选到正确版本与交易集。
- 并发快照：多 goroutine 并发查询固定单个快照；旧快照在新发布后保持不变。
- 幂等与顺序无关：重复提交内容相等、与独立全量重算相等；打乱输入批次结果一致。

所有夹具均为仓库内本地合成数据，无网络与外部服务。
