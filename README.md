# 保险合同组 CSM 滚动引擎（题内冻结规则）

仅依赖 JDK 21 标准库的 IFRS 17 风格合同服务边际（CSM）滚动引擎。
输入期初余额、现金流现值变动、风险调整、覆盖单位、折现展开与规则版本，
按题内固定顺序滚动 CSM、亏损组成（loss component）与履约现金流（FCF），
并由机械 oracle 校验逐项闭合与借贷平衡。

## 复现命令

需要 JDK 21（`javac`/`java` 在 PATH）。

PowerShell：

```powershell
.\run-tests.ps1   # 干净构建 + 自动化测试
.\run-cli.ps1     # 干净构建 + 运行合成 demo CLI
```

bash（Linux/macOS）：

```bash
chmod +x *.sh
./run-tests.sh
./run-cli.sh
```

手动构建（Windows 用 `;`，类 Unix 用 `:` 分隔 classpath）：

```powershell
javac -d build/classes      (Get-ChildItem -Recurse -Filter *.java src/main | %% FullName)
javac -cp build/classes -d build/test-classes (Get-ChildItem -Recurse -Filter *.java src/test | %% FullName)
java -cp "build/classes;build/test-classes" csm.TestRunner
java -cp "build/classes" csm.cli.Main
```

## 处理顺序（每步都保留 期初 / 变动 / 期末）

1. `INTEREST_ACCRETION` 折现展开：按期初 CSM 与 FCF、按事件冻结版本的利率计息
   （V1 = 5%，V2 = 6%）。
2. `FUTURE_SERVICE_CHANGE` 未来服务变动（现金流现值变动与风险调整变动同属本步）：
   - 不利变动：先冲减 CSM，超过 CSM 的超额转入亏损组成，CSM 恒非负。
   - 有利变动：先转回亏损组成，剩余部分才恢复 CSM。
3. `CURRENT_SERVICE_RELEASE` 当期服务释放：
   `释放额 = 该步期初 CSM × 当期覆盖单位 / 剩余总覆盖单位`。
   覆盖单位为零、负数，或 `当期 > 剩余合计` 时分类失败（拒绝）。
4. `CASH_SETTLEMENT` 现金流结算：仅在 FCF 与现金间移动，不影响 CSM / 亏损组成；
   结算超过可用 FCF 时拒绝。

任何一步都不会用净额跳过中间状态；oracle 校验
`期初 + 变动 = 期末`、相邻步骤链接、相邻期间结转、余额非负。

## 符号表（统一 BigDecimal，尺度 8，HALF_UP）

余额（`Balances`，均非负）：

| 余额 | 含义 |
| --- | --- |
| `csm` | 合同服务边际，贷方性质的递延利润 |
| `lossComponent` | 亏损组成（onerous 部分的亏损层） |
| `fulfillmentCashFlows` | 履约现金流负债（FCF，正向为负债） |

事件金额（`MovementEvent.amount`）：

| 事件类型 | 方向 | 金额符号 | 含义 |
| --- | --- | --- | --- |
| `FUTURE_SERVICE_CHANGE` | `ADVERSE` | `> 0` | 不利：FCF 增加，先冲 CSM 再提亏损 |
| `FUTURE_SERVICE_CHANGE` | `FAVORABLE` | `< 0` | 有利：FCF 减少，先转回亏损再恢复 CSM |
| `RISK_ADJUSTMENT_CHANGE` | `ADVERSE` / `FAVORABLE` | 同上 | 风险调整变动，同瀑布规则 |
| `INTEREST_ACCRETION` | `NEUTRAL` | 由引擎按冻结利率计算 | 不接受外部金额 |
| `CURRENT_SERVICE_RELEASE` | 不提供事件 | 按覆盖单位计算 | 始终为 CSM 的减少 |
| `CASH_SETTLEMENT` | `NEUTRAL` | `>= 0` | 当期支付现金，减少 FCF |

符号冲突（方向标记与金额符号不一致）、未知（`null`）规则版本、
覆盖单位非法、结算超额、期初余额为负、CSM 与亏损组成期初同时为正等
一律抛 `CsmValidationException` 拒绝。

## 借贷分录

每个业务变动至少生成一组借贷相等的分录；CSM 与亏损组成的再分类另出
配平的备忘分录。代表性分录：

| 业务 | 借 | 贷 |
| --- | --- | --- |
| CSM 利息展开 | CSM | 保险财务费用 |
| FCF 利息展开 | 保险财务费用 | FCF |
| 不利变动（损益） | 保险服务费用 | FCF |
| 不利变动被 CSM 吸收（备忘） | CSM | 保险服务费用 |
| 超额确认亏损（备忘） | 保险服务费用 | 亏损组成 |
| 有利变动（损益） | FCF | 亏损转回收益 |
| 有利转回亏损（备忘） | 亏损转回收益 | 亏损组成 |
| 有利恢复 CSM（备忘） | CSM | 亏损转回收益 |
| 当期服务释放 | 保险收入 | CSM |
| 现金结算 | FCF | 现金 |

oracle 对每张凭证验证借贷相等，并验证：逐步三态闭合、跨期结转闭合、
各科目逐组汇总 == 逐张分录明细汇总。

## 规则版本与冻结重放

每个事件自带 `ruleVersion`。某期间的版本由该期间事件决定，
同一期间内版本不一致即拒绝；无事件期间可通过 `ReplayEngine` 的
`版本时间线 (periodIndex -> RuleVersion)` 指定生效版本。
换版只影响换版生效期间及之后的事件（如 V2 利率 6% 只对 V2 期间计息）；
若把历史事件重标为新版本、与时间线冲突，重放直接拒绝，
从而保证历史期间始终可按原版本确定性重放。

## 代码布局

```
src/main/java/csm/
  model/      枚举与不可变记录（余额、事件、覆盖单位、分录、滚动结果等）
  util/       DecimalScale（统一尺度）
  engine/     RuleBook / StepProcessor / CsmRollEngine / JournalFactory
              / RollOracle / ReplayEngine
  cli/Main.java
src/test/java/csm/
  TestRunner.java              零依赖断言测试入口
  fixture/SyntheticFixtures.java 合成 fixture
```

## 覆盖的测试场景

正常组、亏损切换（不利超额）、亏损转回（先亏后利）、
零覆盖单位、负覆盖单位、符号冲突、未知版本、规则换版、
冻结重放一致性、历史事件被重标新版本的拒绝、结算超额拒绝。
