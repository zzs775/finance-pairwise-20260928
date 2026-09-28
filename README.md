# 规则驱动的预期信用损失（ECL）引擎

仅使用 Python 3.13 标准库，对本地匿名合成合约的 PD / LGD / EAD
期限结构与情景权重计算分阶段（stage 1/2/3）预期信用损失。

## 规则手册

1. **固定尺度 Decimal**：所有概率、权重、折现因子按 `1e-8` 量化，
   金额按 `0.01` 量化，逐期明细乘子按 `1e-6` 量化，统一使用
   `ROUND_HALF_UP`；拒绝 `float`、`bool`、`NaN`、`Infinity`。
   累计 PD 必须有限、位于 `[0, 1]` 且逐期不下降；负 EAD、负权重、
   权重不闭合（容差 `1e-8`）、期限缺口一律分类失败（`ValidationError`）。
2. **边际 PD 与阶段范围**：累计 PD 转换为逐期边际 PD
   （`m1=c1, mt=ct-c(t-1)`，望远镜求和严格等于期末累计 PD）。
   Stage 1 使用题内 12 期窗口（到期不足 12 期则截断）；Stage 2/3 使用
   剩余全部期限。阶段迁移时按新 stage 重算，期间既不重复也不遗漏。
3. **逐期 ECL**：每期输出 `marginal_pd`、`lgd`、`ead`、
   `discount_factor`、`weight`、`period_ecl`、`weighted_ecl` 与
   `rounding_delta` 全部乘数和舍入项；合约 `total_ecl` 恒等于
   其全部明细行 `weighted_ecl` 的精确相加（末行吸收最终一分钱舍入）。
4. **版本化假设集**：假设集带版本号与生效日期；一次估值绑定同一不可变
   版本。发布前先校验，失败版本不会进入注册表，也不能替换已发布结果。
   PD/LGD/EAD/折现/权重修订时按结构指纹识别受影响合约。
5. **参考器与一致性**：`reference.py` 用独立的朴素嵌套循环逐期枚举
   边际概率、stage 范围与情景加权，逐行对账引擎。增量批次与全量重算
   输出规范 JSON 字节一致；合约输入重排不改变结果（按 contract id 排序）。
6. 数据全部为确定性本地匿名合成记录，无外部文件与网络。

## 运行

```powershell
python -m unittest discover -s tests -v
python -m ecl_engine.cli price --version v1
python -m ecl_engine.cli revise --new-version v2
python -m ecl_engine.cli reference --contract ANON-001 --version v1
```

`price` / `revise` 输出规范（键排序、紧凑分隔）UTF-8 JSON，可直接
`diff` 对比全量与增量结果；校验失败时进程以退出码 2 结束。

## 模块

| 文件 | 职责 |
| --- | --- |
| `ecl_engine/scales.py` | Decimal 解析、固定尺度、有限值与精确求和 |
| `ecl_engine/models.py` | 不可变数据模型（合约、曲线、情景、明细行、结果） |
| `ecl_engine/validation.py` | 边界/单调/闭合/缺口等分类失败规则 |
| `ecl_engine/marginal.py` | 累计 PD → 逐期边际 PD |
| `ecl_engine/stages.py` | stage 1（12 期截断）/ stage 2、3（剩余期限） |
| `ecl_engine/engine.py` | 逐期展开、乘数明细、情景汇总与舍入恒等式 |
| `ecl_engine/assumptions.py` | 版本注册表、原子发布、受影响合约指纹 |
| `ecl_engine/valuation.py` | 全量/增量估值、原子替换、字节一致性校验 |
| `ecl_engine/reference.py` | 直接期限展开参考器（独立对账） |
| `ecl_engine/synthetic.py` | 本地匿名合成合约与 v1/v2 假设集 |
| `ecl_engine/cli.py` | `price` / `revise` / `reference` 命令 |
