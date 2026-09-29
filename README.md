# 时间加权收益引擎（TWR Engine）

仅使用 Python 3.13 标准库的时间加权收益（Time-Weighted Return）计算引擎。
围绕**外部申赎现金流**切分估值区间，输出账户级与复合组合（composite）级收益，
并提供区间查询、规范化 JSON 与机械 oracle 复算。所有金额与收益均使用
`decimal.Decimal`，数据为本地确定性合成数据。

## 时点口径（冻结的同日顺序）

每个估值日内，事件顺序固定为：

1. 期初估值（opening valuation）
2. 期初现金流（opening cash flow，申购为正 / 赎回为负）
3. 期间损益（period P&L）
4. 期末现金流（closing cash flow，申购为正 / 赎回为负）
5. 期末估值（closing valuation，期末现金流之后的 NAV）

会计恒等式：

```text
期末估值 = 期初估值 + 期初现金流 + 期间损益 + 期末现金流
期间损益 = 期末估值 - 期初估值 - 期初现金流 - 期末现金流
```

当日实际投入资本（子期收益分母）为 `期初估值 + 期初现金流`。

## 子期切分规则

- 期初现金流到达日：在该日**开始**一个新子期，现金流计入新子期期初资本。
- 期末现金流到达日：该日**单独**成为一个子期（其损益按当日投入资本计量），
  现金流发生在当日损益之后，不影响该子期分母。
- 估值断层（上一日期末 NAV ≠ 当日期初估值）同样作为子期边界。
- 期初投入资本为零或负时，该子期不产生收益率，返回显式状态
  `zero_base` / `negative_base`；账户或组合层面因此不声称整体 TWR。
- 全额赎回：赎回日按投入资本计入收益（零损益日收益率为 0），其后的
  零资本日单独标记为 `zero_base`。

## 账户与复合组合收益

- 账户 TWR：几何链接 `TWR = ∏(1 + rᵢ) - 1`，其中 `rᵢ = 子期损益 / 期初投入资本`。
- 复合组合：在每个组合子期内，成员集合与期初资本保持恒定；
  按**可复算的期初资本权重**聚合：
  `R = Σ wₖ rₖ`，`wₖ = 期初资本ₖ / Σ 期初资本`，再几何链接各子期。
- 成员进出只在生效区间内生效：成员在 `start_day` 当日起计入，
  `end_day` 当日仍计入（闭区间）；既往组合收益永不回写。

## 幂等与确定性

- 输入允许乱序、分块和携带完全重复事件；规范化后按业务键去重：
  估值键 `(账户, 日期)`（冲突重复直接拒绝），
  现金流键 `(账户, 日期, 时点, 金额)`，同日同时点金额聚合。
- 无账户归属的现金流抛出 `UnattributedCashFlowError`；
  倒置成员区间（`start_day > end_day`）抛出 `InvertedMembershipError`。
- 规范化输出按键排序；规范 JSON 中 Decimal 以定点文本输出、
  映射按键排序。账户顺序或文件分块改变，区间、收益与 JSON 字节完全一致。

## Oracle 校验

`twr_engine/oracle.py` 用独立代码路径机械复算：

- `verify_account`：逐子期核对状态与 `r = pnl / 期初资本`，
  并验证 `1 + TWR == ∏(1 + rᵢ)`。
- `recompute_composite`：直接从原始日度估值与现金流，用会计恒等式
  重建每日损益、独立切分组合子期并重算权重与收益，与引擎结果逐项对比。
- 零收益外部现金流（插入一个无损益的申赎日）不改变 TWR，测试已覆盖。

## 复现命令

```bash
# 运行全部自动化测试（仅标准库 unittest）
python -m unittest discover -s tests -v

# 运行内置确定性合成数据，输出规范 JSON
python -m twr_engine sample

# 区间查询：查询 alpha 在 2026-01-06 至 2026-01-08 的链接收益
python -m twr_engine sample --account alpha --query 2026-01-06:2026-01-08

# 字节级稳定性检查（两次输出应完全相同）
python -m twr_engine sample > a.json
python -m twr_engine sample > b.json
# PowerShell:
# (Get-FileHash a.json).Hash -eq (Get-FileHash b.json).Hash
```

合成数据覆盖：同日多事件、全额赎回至零、负资本、成员中途换入、
重复/乱序输入与 Decimal 尾差。

## 代码结构

- `twr_engine/models.py`：领域类型（金额字段全部为 `Decimal`）
- `twr_engine/normalize.py`：校验、按业务键幂等去重与确定性排序
- `twr_engine/intervals.py`：冻结时点口径下的子期切分与资本状态
- `twr_engine/account.py`：子期收益率与账户几何链接
- `twr_engine/composite.py`：期初资本权重的复合组合聚合
- `twr_engine/query.py`：任意日期区间的收益链接查询
- `twr_engine/oracle.py`：独立机械复算
- `twr_engine/jsonio.py`：Decimal 安全的规范 JSON
- `twr_engine/data.py`：本地确定性合成数据
- `twr_engine/cli.py` / `__main__.py`：命令行入口
