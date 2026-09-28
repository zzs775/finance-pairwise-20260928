# discurve — 单币种贴现曲线引导器

仅使用 Python 3.13 标准库实现。从本地**存款、FRA、固定对浮动互换**报价逐段
引导贴现因子，对曲线进行 loglinear 插值，并重定价一组债券现金流。所有报价、
现金流和贴现因子都以 `Decimal` 表示。

## 快速开始

```powershell
python -m unittest discover -s tests          # 运行全部测试
python -m discurve.cli build examples/quotes.json
python -m discurve.cli build examples/quotes.json -o report.json
```

输出为**规范 JSON**（键排序、紧凑分隔符、Decimal 为规范化字符串、日期为
ISO-8601）。无论输入工具的排列顺序如何，曲线节点、诊断顺序与 JSON 字节完全相同。

## 模块结构

| 文件 | 职责 |
| --- | --- |
| `discurve/errors.py` | 分类异常层级 |
| `discurve/calendar.py` | 假日日历、营业日调整、日计数、短/长桩排期 |
| `discurve/instruments.py` | 不可变报价/现金流类型与结构校验 |
| `discurve/interpolator.py` | 节点严格递增的 loglinear 贴现曲线 |
| `discurve/pricing.py` | 工具残差、模型现值、独立现金流 PV oracle |
| `discurve/bootstrap.py` | 稳定排序逐段引导 + 带括区间的二分法 |
| `discurve/versioning.py` | 不可变版本与原子发布 |
| `discurve/serialization.py` | 确定性规范 JSON |
| `discurve/cli.py` | 命令行入口 |

## 公式

- **日计数**：`ACT/360`、`ACT/365F`、`30/360`（含 31 日债券惯例规则）。
- **存款**（起息日支付 1，到期收取本息）：
  `R = DF(start) − (1 + r·τ)·DF(maturity)`。
- **FRA**（到期交割的收固定付浮动方）：
  `R = (1 + r·τ)·DF(end) − DF(start)`。
- **面值互换**（固定腿 vs. 浮动腿，浮动腿现值 `N·(DF₀ − DFₙ)`）：
  `R = N·(Σ K·τᵢ·DFᵢ + DFₙ − DF₀)`。
- **loglinear 插值**：区间 `[t₁,t₂]` 内
  `ln DF(t) = ln DF(t₁) + (t−t₁)/(t₂−t₁) · (ln DF(t₂) − ln DF(t₁))`，
  权重使用日历天数。区间端点查询接受，**区间外查询显式拒绝**
  （`ExtrapolationError`；引导期内引用未建成期限映射为 `MissingTenorError`）。
- **债券 PV**（独立参考函数 `cashflows_pv`）：
  `PV = Σ CFᵢ · DF(tᵢ)`。

## 尺度与精度

- 所有金额与比率使用 `Decimal`（默认语境精度 28，引导期间局部提升到 40 位）。
- 名义本金尺度 1（可通过 `notional` 字段缩放）；贴现因子无量纲。
- 贴现因子必须严格为正，节点日期必须严格递增，曲线首节点必须等于估值日且
  `DF = 1`。

## 引导流程

1. 校验快照（非法日期、非有限 Decimal、非正月息等直接报错）。
2. 以**调整后到期日**作为节点（互换取排期最后一个调整后支付日），按到期日
   稳定排序（Python 稳定排序，同日重复节点抛 `DuplicateNodeError`）。
3. 逐节点二分求解未知 DF：
   - 以上一节点 DF 为锚点，交替向下（×0.5）和向上（×1.5）扩展，最多 64 次
     括区间求值，找不到异号区间抛 `RootBracketingError`；
   - 二分上限 200 次迭代，残差精确为 0 或区间宽度 ≤ `1E-20` 时收敛，
     超限抛 `IterationLimitError`；
   - 任何中间残差非有限抛 `NonFiniteValueError`。
4. 节点解出后重定价，残差必须满足 `|R| ≤ 1E-10`
   （`bootstrap.PRICING_TOLERANCE`，固定容差），否则整次构建失败。
5. **全有/全无**：构建仅在内存草稿中进行；成功后通过单次引用赋值原子替换
   当前版本。失败时不发布任何内容，旧版本仍可完整查询。

每个工具输出：模型现值、市场目标（面值工具为 0）、残差、二分迭代数。

## 错误分类

| 异常 | 触发条件 |
| --- | --- |
| `IllegalDateError` | 非日期、到期不晚于起息、节点非递增 |
| `DuplicateNodeError` | 两个工具映射到同一节点日期 |
| `MissingTenorError` | 工具引用当前曲线范围外的期限 |
| `NonPositiveDiscountError` | DF 非正或插值出非正结果 |
| `RootBracketingError` | 括区间阶段无法包住异号根 |
| `IterationLimitError` | 二分超过迭代上限 |
| `NonFiniteValueError` | 中间值为 NaN/Infinity |
| `ExtrapolationError` | 已发布曲线的区间外查询 |
| `PricingToleranceError` | 重定价残差超过固定容差 |

所有异常都继承自 `CurveError`。

## 报价 JSON

见 `examples/quotes.json`：`calendar.holidays` 为题内假日列表；工具支持
`day_count`、`period_months`、`business_day_convention`
（`unadjusted`/`following`/`preceding`/`modified_following`）与 `stub`
（`short_front`/`long_front`/`short_back`/`long_back`）。

## 测试覆盖

- 周末/假日、四种营业日调整、三种日计数、短/长前桩与短/长后桩；
- 插值节点精确返回、几何中点、边界接受、区间外拒绝、非法节点；
- 解析存款夹具与可手算两期互换（oracle 数值精确到 `1E-18`）；
- FRA 远期再定价、负利率下 DF 为正；
- 重复节点、非法日期、未建成期限、无括根残差、非有限输入；
- 失败时旧版本仍可查询、成功时原子切换；
- 输入乱序下节点/诊断/规范 JSON 完全一致；
- 债券重定价与 CLI 成功/失败退出码。
