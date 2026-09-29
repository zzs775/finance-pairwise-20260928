# Life Reserve Engine

年频定期寿险准备金引擎。Python 3.13，**仅标准库**，死亡率全部为本地合成数据；
全部金额与概率使用 `decimal.Decimal` 精确运算。

## 功能

- 输入：投保年龄 `x`、期限 `n`、保额 `S`、固定利率 `i`、逐龄死亡率 `q_x`，
  以及统一现金流时点（期初 `beginning` 或期末 `end`）和费用假设。
- 概率：由条件生存概率递推死亡/生存分支，逐年严格保持概率质量守恒。
- 定价：等价原则下的净保费与毛保费、给付/费用/保费现值。
- 准备金：**显式状态求和**与**反向递推**两种独立算法，逐年对照；
  终端准备金按合同边界归零；超出容差时只返回诊断，不挑选任一结果。
- 输出：净保费、毛保费、给付 PV、费用 PV、逐年准备金、利率敏感度，
  以及逐状态现金流和概率路径明细。
- 机械 oracle：零死亡率给付为零、保费–给付–费用现值闭合、逐年递推方程、
  概率质量四项身份恒等式核验。

## 记号与公式

令 `v = 1/(1+i)`，年度节点的贴现指数：

- 期初：`d_t = v^(t-1)`（第 `t` 年节点在物理时刻 `t-1`）
- 期末：`d_t = v^t`（节点在物理时刻 `t`）

### 概率路径

第 `t` 年年龄 `x+t-1`，`q_t = q_{x+t-1}`，`p_t = 1 - q_t`。

```
in_force_1 = 1
death_t   = in_force_t * q_t
survive_t = in_force_t - death_t          # 一致切分，保证质量严格守恒
in_force_{t+1} = survive_t
death_t + survive_t == in_force_t         # 每年恒等
```

### 现金流顺序（每个年度节点，三腿同节点，顺序固定）

1. 毛保费 `P`：按进入该年度的全部在保人数收取；
2. 费用 `e_t`：保费比例费用 + 每年固定费用，首年额外初始费用；
3. 死亡给付 `S`：仅对该年度死亡者结算。

```
premium_t = in_force_t * P
expense_t = in_force_t * (alpha*P + beta + gamma*[t=1])
benefit_t = death_t * S
```

其中 `alpha` 为保费比例、`beta` 为每年固定费用、`gamma` 为首年初始费用。

### 定价（等价原则）

```
年金因子  a_ddot = Σ_t d_t * in_force_t
给付 PV   B      = Σ_t d_t * death_t * S
费用 PV   E      = Σ_t d_t * expense_t
净保费    P_net  = B / a_ddot
毛保费    P      = (B + Σ_t d_t * in_force_t * (beta + gamma*[t=1]))
                   / (a_ddot * (1 - alpha))
```

构造上满足 `gross_premium_pv = P * a_ddot = B + E`（Decimal 末位容差内）。

### 准备金（两法独立）

`R_t` 为第 `t` 年节点、每名在保者的准备金。三腿同节点、相邻节点相隔一年，
故两种时点下递推式相同，只是节点物理时刻不同。

显式状态求和（cohort 级前瞻）：

```
C_{n+1} = 0
C_t = in_force_t * (q_t*S + e_t - P) + v * C_{t+1}
R_t = C_t / in_force_t
```

反向递推（每名在保者）：

```
R_{n+1} = 0                         # 合同边界，终端准备金归零
R_t = q_t*S + e_t - P + v * p_t * R_{t+1}
```

两法逐年之差 `|R_t^A - R_t^B|` 若大于容差（默认 `1e-4`），在
`ReserveResult.diagnostics` 中返回年份、两个值、差值与容差，CLI 以退出码 3
报告；引擎不会用其中一个覆盖另一个。正常情况下两者仅在 Decimal 除法末位
（约 `1e-25`）有差异。

### 利率敏感度

对基准利率施加确定性冲击（默认 `-1%`、`+1%`），重新完整定价，结果按利率
升序稳定排序输出。

## 输入校验

- 每个 `q_x ∈ [0,1]`；
- 年龄覆盖必须完整：`[x, x+n-1]` 缺一即报错；
- `term >= 1`、`issue_age >= 0`、保额为正、`1+i > 0`、费用非负且
  保费比例费用 `alpha < 1`。

## 复现命令

运行测试（仅标准库）：

```powershell
python -m unittest discover -s tests -v
```

CLI（默认使用确定性 Gompertz 风格合成生命表）：

```powershell
python -m life_reserve.cli `
  --issue-age 40 --term 5 --sum-assured 200000 --rate 0.035 `
  --timing beginning --expense-percent 0.04 `
  --expense-per-policy 20 --initial-expense 150 --oracle
```

使用显式逐龄死亡率（年龄覆盖必须完整）：

```powershell
python -m life_reserve.cli --issue-age 40 --term 3 `
  --qx 40=0.01 --qx 41=0.02 --qx 42=0.03 --rate 0.03 --timing end
```

零死亡率 oracle：

```powershell
python -m life_reserve.cli --issue-age 40 --term 5 --timing beginning `
  --qx 40=0 --qx 41=0 --qx 42=0 --qx 43=0 --qx 44=0 `
  --expense-percent 0.05 --expense-per-policy 10 --initial-expense 100 --oracle
```

退出码：`0` 成功；`2` 输入校验失败；`3` 两准备金算法超容差（诊断）；
`4` oracle 校验失败。

## 包结构

- `life_reserve/model.py`：策略、生命表、费用、时点类型与校验
- `life_reserve/probability.py`：生存/死亡概率递推
- `life_reserve/cashflows.py`：年度现金流顺序与贴现因子
- `life_reserve/pricing.py`：净/毛保费与各项现值
- `life_reserve/reserve.py`：显式求和与反向递推、诊断
- `life_reserve/engine.py`：总估值、敏感度、合成生命表
- `life_reserve/oracle.py`：机械恒等式校验
- `life_reserve/cli.py`：命令行界面
- `tests/test_life_reserve.py`：自动化测试
