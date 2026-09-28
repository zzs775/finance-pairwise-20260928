# 组合 PnL 解释引擎

Python 3.13、仅标准库。用本地合成的期初期末持仓、交易、价格、应计与 FX
快照，把组合价值变化拆分为交易、价格、利息、外汇四类分量与单列残差。

## 运行

```powershell
# 全量归因，输出规范 JSON
python -m pnl_engine run fixtures/sample_bundle.json

# 历史交易修订：增量结果与全量重算逐位比对
python -m pnl_engine revise fixtures/sample_bundle.json fixtures/revisions_sample.json

# 允许非零 residual（默认题内控制夹具必须为零）
python -m pnl_engine run path/to/bundle.json --allow-residual

# 测试
python -m unittest discover -s tests
```

所有夹具均为仓库内本地合成数据（`fixtures/`、`tests/`），无网络、无第三方依赖。

## 数据与尺度

- 金额/价格/数量/汇率一律用 `Decimal`，量化到固定尺度
  （金额 `0.01`，价格/数量/FX `1e-8`），中间计算使用 50 位精度上下文。
- 每条记录携带业务时刻（ISO 8601 字符串）与快照版本 `SnapshotVersion`。
- 数值以字符串进入 JSON 加载器，杜绝二进制浮点。

## 失败分类

校验在归因前执行，错误分类互不混用：

| category | 触发条件 |
| --- | --- |
| `missing_factor` | 缺期末价格、缺期初/期末 FX、引用未知资产、零数量交易、非正 FX |
| `duplicate_trade` | 重复 `trade_id`、重复资产 |
| `currency_mismatch` | 交易/应计币种与资产币种不符、本币 FX 不等于 1 |
| `time_inversion` | 窗口倒置、事件超出窗口、同资产价格/FX 序列未严格递增、时刻不可解析 |
| `non_finite` | `NaN`/`Infinity` 进入任何数值字段 |

## 归因路径

每个资产独立跑一条冻结的事件流。同一业务时刻的固定优先级为：
**交易(1) → 价格(2) → 利息(3) → FX(4)**，同刻再按快照版本、稳定 ID 排序。

- `trade`：成交名义量 `Δq × 成交价`（按当时 FX 换算）；成交价与最新盯市价
  的滑点也归入 `trade`，不会静默混入价格分量。
- `price`：当前持仓量 × 相邻价格差。
- `interest`：当前持仓量 × 单位应计（支持空头负应计、空仓不应计）。
- `fx`：汇率跳动时点上，对当时本币价值（持仓市值加累计应计现金）
  计提 `本币价值 × Δfx`，望远镜式累积。

每个分量同时列出 `base`、`factor_delta`、`rounding` 与 `value`：
`value = freeze(base × factor_delta × fx)`，`rounding` 记录精确值与冻结值之差。
无法解释的差额永远不会塞进既有分类，只能进入单列 `residual`。

## 闭合与残差

对每个资产严格保证：

```
期初价值 + Σ 分量值 + residual = 期末价值
```

题内控制夹具的 `residual` 必须为零，否则 `build_report(strict=True)` 抛
`ClosureError`；非严格模式下 `residual_diffs` 给出按资产排序的最小可追踪
差异集（资产、分类、期望值、实际值）。

## 层级汇总

资产 → 行业 → 组合三级。父层的所有数值（期初、期末、分类分量、残差）
只能由直接子层相加产生；节点按规范键排序，输入顺序、字典遍历顺序或
分组并行度的变化都不会改变规范 JSON（`sort_keys` + 紧凑分隔符）。

## 参考器与修订

- `reference.direct_revalue` 是独立的直接重估实现：在起止边界取最新
  价格/FX 直接估值；`enumerate_paths` 冻结每资产的有序事件路径。
- 被归因的持仓数超过 8 时拒绝枚举，防止路径核对悄悄失效。
- `cross_check` 用参考器逐项核对快速流水线的起止价值。
- `revision.incremental_report` 只重算被历史修订触及的资产，父层重新相加；
  CLI `revise` 断言增量结果的规范 JSON 与全量重算完全一致。

## 模块

`types` 数据模型 · `decimalx` 尺度冻结 · `validation` 失败分类 ·
`attribution` 事件流归因 · `residual` 闭合与差异集 · `aggregate` 层级汇总 ·
`reference` 直接重估/路径枚举 · `revision` 增量等价 · `canonical` 规范 JSON ·
`loader` 夹具加载 · `cli` 命令行。
