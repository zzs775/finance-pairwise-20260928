# 组合 PnL 解释引擎

仅使用 Python 3.13 标准库实现的组合盈亏（PnL）归因/解释引擎。输入为期初/期末持仓、
交易、价格、应计利息与 FX 快照（全部为本地合成数据），输出把期初到期末的价值变化拆分为
**交易、价格、利息、外汇**及显式 **residual**，并支持资产 / 行业 / 组合三层汇总。

## 设计原则

- **Decimal 冻结尺度**：金额 8 位、价格 8 位、数量 6 位、FX 10 位小数，统一银行家舍入
  （`ROUND_HALF_EVEN`），中间乘积累加在 60 位精度上下文内完成，避免二进制浮点与静默溢出。
- **业务时刻 + 快照版本**：每条记录都带 `BusinessTime(iso_ts, seq)` 与 `Snapshot(version, asof)`；
  同一时刻的事件按固定优先级处理：**交易 → 价格 → 利息 → FX**，同刻多笔交易按 `txn_id` 排序。
- **固定归因路径**：逐资产重放整条事件时间线，严格顺序（telescoping）记账。每个分量都列出
  `base`（基数）、`factor_delta`（因子差）、`raw`（高精度未舍入值）、`amount`（冻结尺度金额）
  与 `rounding = amount - raw`。无法解释的差额永远不会被塞进既有分类。
- **精确闭合**：
  `期初价值 + Σ各分量 = 期末价值`。跨币种舍入尾差与快照缺口单列 `residual`，
  并输出最小可追踪差异集（资产 + 时刻 + 类型 + 快照版本）。
- **层级相加**：行业层只由其资产节点相加产生，组合层只由行业节点相加产生；不重算、不重归因。
- **确定性**：所有字典按键排序、集合先排序后遍历、规范 JSON 使用排序键与紧凑分隔符，
  输入记录顺序、字典插入顺序或（假想的）分组并行度变化都不会改变输出或尾差归属。

## 分量口径（折算为基准币种）

对每个事件时刻，按顺序对每个因子变化记账：

- `trade_flow`：`Δqty × 成交价`（投资者净现金流，按当时 FX 折算）。
- `trade_pnl`：`Δqty × (市价 − 成交价)`（交易相对上一盯市价的实现损益）。
- `price`：`qty × Δprice`。
- `interest`：`Δaccrued`（应计变化，逐 tick 差额）。
- `fx`：`本地价值 × Δfx_to_base`，FX 是该时刻最后处理的因子。

本地分量均在“该因子生效时刻的汇率”下折算；顺序记账保证各分量与基数步长精确伸缩，
因此在因子一致的控制夹具上 `residual = 0`。

`residual` 只承载两类显式差异：

- `rounding_tail`：路径中跨币种折算的舍入尾差（一致冻结因子下为 0）。
- `snapshot_gap`：期末持仓快照直接重估值与因子路径回放值之差（例如快照应计与因子 tick 不一致）。

## 目录结构

- `pnl_engine/models.py`：记录类型与汇总数据类。
- `pnl_engine/decimal_utils.py`：冻结尺度、有限性检查、舍入乘法。
- `pnl_engine/validation.py`：五类分类失败
  （`missing_factor` / `duplicate_transaction` / `currency_mismatch` /
  `time_inversion` / `non_finite_value`）。
- `pnl_engine/attribution.py`：固定路径逐资产归因。
- `pnl_engine/aggregation.py`：资产 / 行业 / 组合层级汇总。
- `pnl_engine/direct_reval.py`：直接重估参考器；当头寸 ≤ 8 时枚举全部 24 条冻结因子顺序
  并逐项核对快速流水线，超过 8 个时退化为端点闭合核对。
- `pnl_engine/revision.py`：历史交易修订；受影响资产的增量结果必须与全量重算逐字节一致。
- `pnl_engine/canonical.py`：确定性规范 JSON。
- `pnl_engine/fixtures.py`：本地合成数据构造器（测试专用，无外部数据源）。
- `pnl_engine/cli.py`：命令行入口。
- `examples/control_portfolio.json`：零残差控制夹具。
- `tests/`：unittest 自动化测试。

## CLI 用法

```bash
# 读取夹具并输出规范 JSON
python -m pnl_engine.cli examples/control_portfolio.json

# 控制夹具模式：residual 必须为零，否则以退出码 3 输出最小可追踪差异集
python -m pnl_engine.cli examples/control_portfolio.json --require-zero-residual

# 输出到文件 / 从 stdin 读取
python -m pnl_engine.cli input.json -o result.json
cat input.json | python -m pnl_engine.cli
```

退出码：`0` 成功；`1` 输入分类失败；`2` JSON 非法；`3` 控制夹具残差非零；
`4` 直接重估交叉核对未通过。

## 运行测试

```bash
python -m unittest discover -s tests -v
```

覆盖：买卖跨期、反向（做空）持仓、利息正负与零边界、FX 升值/贬值方向、同刻事件优先级、
层级闭合、记录乱序确定性、≤8 头寸的 24 条冻结路径核对、历史交易修订一致性、
大数量级不静默溢出与非有限值分类失败。

## 输入 JSON 结构

顶层字段：`base_currency`、`period_start`、`period_end`、`assets`、
`start_positions`、`end_positions`、`transactions`、`prices`、`accruals`、`fx`。
金额/价格/数量/FX 均以字符串给出以避免浮点误差；每条记录都需要 `snapshot`。
基准币种的 FX 必须为 1；价格/应计/FX 因子在期初期末都必须存在。
