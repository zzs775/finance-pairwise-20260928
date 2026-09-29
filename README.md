# CSM 滚动引擎（题内冻结规则）

纯 JDK 21 标准库实现的保险合同组合同服务边际（CSM）滚动引擎。无任何
第三方依赖，数据全部来自合成 fixture。

## 复现命令

PowerShell：

```powershell
./build.ps1        # 编译 src/ 与 test/ 到 out/
./run-tests.ps1    # 自动化测试（5 个场景）
./run-cli.ps1      # 打印逐组逐步骤滚动、分录与 oracle 校验
```

等价的手动命令（Windows PowerShell）：

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java src,test | ForEach-Object FullName)
java -cp out csm.AllTests
java -cp out csm.cli.CommandLine
```

bash / Linux / macOS：

```bash
./build.sh
./run-tests.sh
./run-cli.sh
```

要求：JDK 21（`javac`/`java` 在 PATH 上）。

## 符号表（金额口径）

所有余额按"负债方向正数"持有，`Amount` 统一为 `BigDecimal`，尺度 6 位、
四舍五入（`HALF_UP`）；利率尺度 10 位。

| 名称 | 含义 | 符号约定 |
| --- | --- | --- |
| FCF | 履约现金流负债（未来现金流量现值+风险调整） | `>= 0`，增加为正 |
| CSM | 合同服务边际负债 | 始终 `>= 0`，增加为正 |
| LC | 亏损组成（loss component，FCF 的备忘记名） | 始终 `>= 0`，增加为正 |
| `futureServiceChange` | 未来服务变动输入 | **正=有利**，负=不利 |
| FCF 当期变动 | `-futureServiceChange` | 有利变动减少 FCF |
| `currentRate` | 当期折现/展开利率 | `>= 0`，FCF 始终使用 |
| `lockedRate` | 组级锁定利率 | `>= 0`，V1 下 CSM 展开使用 |
| `currentCoverageUnits` | 当期释放覆盖单位 | 必须严格为正，否则分类失败 |
| `remainingCoverageUnits` | 期初剩余覆盖单位 | 必须严格为正，且 `>=` 当期单位 |
| `settlement` | 当期现金结算流出 | `>= 0`，减少 FCF |

未知规则版本、负的期初余额/利率/结算额、覆盖单位零或负数等，均在入口
校验阶段拒绝（`ValidationException` / `ClassificationException`），
不会进入滚动。

## 处理顺序（不得用净额跳过中间状态）

每个期间严格按以下四步处理，每步都保留 FCF/CSM/LC 的
期初（opening）/变动（movement）/期末（closing）三元组，上一步期末即
下一步期初：

1. **利息展开 `INTEREST_ACCRETION`**
   - FCF 展开 = 期初 FCF × `currentRate`
   - CSM 展开 = 期初 CSM × 版本利率（V1=`lockedRate`，V2=`currentRate`）
2. **未来服务变动 `FUTURE_SERVICE_CHANGE`**
   - 不利变动：先冲减 CSM（下限 0），超额进入 LC
   - 有利变动：先转回已存在 LC，剩余部分恢复 CSM
   - FCF 变动恒为输入变动的相反数
3. **当期服务释放 `SERVICE_RELEASE`**
   - 释放比例 = 当期覆盖单位 / 期初剩余覆盖单位
   - CSM 释放额 = 期初 CSM × 比例；LC 释放额 = 期初 LC × 比例
   - CSM 释放只减少 CSM；LC 释放同步减少 FCF 与 LC
4. **现金流结算 `SETTLEMENT`**：现金流出减少 FCF，CSM/LC 不变

## 规则冻结与重放

- 规则版本戳记在事件（期间输入）上：`V1`、`V2`。
- 版本差异：CSM 展开利率，V1 用锁定利率、V2 用当期利率；FCF 展开口径
  两版一致（当期利率）。
- 换版只影响生效后的事件；历史期间始终按其戳记版本处理。
- `ReplayEngine` 按 `sequence` 排序，自动把上一期期末结转为下期期初，
  对同一输入序列重放两次结果逐字段一致（确定性）。

## 机械 oracle

`MechanicalOracle` 在每个期间上机械校验：

1. 四步类型与题内顺序完全一致，一步不少；
2. 每个三元组 `期初 + 变动 = 期末`，相邻步骤首尾相接；
3. CSM、LC 期末恒非负；
4. 每笔业务变动生成一笔借贷平衡分录（`isBalanced`）；
5. 组级 FCF/CSM/LC 滚动净变动 = 分录明细按科目（贷方-借方）汇总净额；
6. 全部分录借方合计 = 贷方合计。

## 分录科目

| 科目 | 说明 |
| --- | --- |
| `FulfillmentCashFlow` | FCF 负债（贷方增加） |
| `ContractualServiceMargin` | CSM 负债（贷方增加） |
| `LossComponent` | LC 备忘记名（贷方增加） |
| `LossComponent.Contra` | LC 自平衡对冲备忘记名 |
| `PnL.InsuranceFinance` | 保险财务损益（利息展开借方费用） |
| `PnL.InsuranceService` | 保险服务损益（未来服务变动/释放） |
| `Cash` | 现金（结算贷记） |

LC 的备忘分录恒为 `LossComponent` 与 `LossComponent.Contra` 的成对
借贷，因此自身平衡且不污染财务报表科目；FCF/CSM 的财务分录各自借贷
平衡。

## 测试场景

`test/csm` 下无框架测试（`csm.AllTests` 聚合，退出码 0/1）：

- `NormalGroupTest`：正常组四步滚动与期末闭合
- `LossSwitchTest`：不利变动超 CSM，超额进 LC 且 CSM 贴零
- `LossRecoveryTest`：有利变动先转回 LC 再恢复 CSM
- `ZeroCoverageTest`：零/负覆盖单位、未知版本、符号冲突均被拒绝
- `RuleChangeReplayTest`：V1→V2 换版只影响后续期间，历史可确定性重放

## 代码地图

```
src/csm/model      Amount、输入/余额/步骤/分录类型、异常与规则版本枚举
src/csm/engine     FrozenRules、InputValidator、四个 Step、
                   CsmEngine、ReplayEngine、MechanicalOracle
src/csm/fixture    SyntheticFixtures 合成数据
src/csm/cli        CommandLine、ReportWriter
test/csm           自动化测试
```
