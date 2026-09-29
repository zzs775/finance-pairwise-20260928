# 年频定期寿险准备金引擎

Annual term-life pricing & reserve engine. Pure Python 3.13 standard library,
all monetary/probability math in `decimal.Decimal`, mortality data is synthetic
or user-supplied (no network, no third-party packages).

## 功能

- 输入：投保年龄、保险期限、保额、现金流时点（期初 `beginning` / 期末
  `end`）、固定年利率、逐龄死亡率 `q_x`，以及初始/续年/理赔三类费用。
- 由逐期条件生存概率递推死亡/生存概率，输出逐状态路径与逐年期望现金流。
- 等价原则计算净保费与毛保费、给付现值、费用现值。
- 逐年准备金用两种独立方法计算：**显式状态求和** 与 **反向准备金递推**；
  两者在容差内不一致时返回 `diagnostics` 诊断列表，**绝不任选其一**。
- 利率敏感度（加性冲击后完整重定价）。
- 所有表格按年度稳定排序；死亡率表以年龄为键，重排输入不改变结果。

## 目录

```
life_reserves/
  models.py       # 输入/输出不可变数据类型
  mortality.py    # 死亡率表校验、条件生存概率
  cashflows.py    # 概率路径递推、逐年期望现金流与现值
  pricing.py      # 年金因子、给付/费用现值、净保费、毛保费
  reserves.py     # 显式状态求和、反向递推、对账诊断
  sensitivity.py  # 利率敏感度重定价
  engine.py       # 编排与输入校验
  cli.py          # 命令行（JSON 输入/输出 + 合成数据演示）
tests/            # 37 个 unittest 用例
examples/contract.json
```

## 记号与公式

设投保年龄 `x0`，期限 `n`，保额 `S`，年利率 `i`，折现因子 `v = 1/(1+i)`。
第 `k` 年（`k=1..n`）到达年龄 `x = x0+k-1`，当年死亡率 `q_k = q_x`，
条件生存概率 `p_k = 1 - q_k`。

### 概率递推（每期生存+死亡分支之和 = 进入该期的生存概率）

```
P(进入第k期) = l_k :   l_1 = 1,   l_{k+1} = l_k * p_k
P(第k期死亡)  = d_k = l_k * q_k
P(活到第k期末)= l_{k+1} = l_k * p_k
恒等式:        d_k + l_{k+1} = l_k
全局划分:      sum_k d_k + l_{n+1} = 1
```

### 现金流发生顺序

每个保单年度内顺序固定：①被保险人活着进入年度 → ②收取年度毛保费 →
③发生初始费用（仅第 1 年）或续年费用 → ④观察死亡，对年内死者支付
死亡给付 `S` 与理赔费用。

时点约定（同一保单年度内全部流量使用同一时点）：

- `beginning`：第 `k` 年流量记于 `t = k-1`；
- `end`：第 `k` 年流量记于 `t = k`；

记 `D_k = v^{k-1}`（期初）或 `v^k`（期末）。

### 定价（等价原则）

```
年金因子   a = sum_k l_k * D_k                    # 1单位生存年金现值
给付现值   A = sum_k d_k * S * D_k
费用现值   E = E0*D_1 + sum_{k>1} l_k*Er*D_k + sum_k d_k*Ec*D_k
净保费     Pn = A / a                              # 仅对给付等价
毛保费     Pg = (A + E) / a                        # 对给付+费用等价
```

### 逐年准备金（每一生存被保险人的金额，边界 `t=k`）

终端边界按合同归零：`V_n = 0`。

显式状态求和（对边界 `t=k` 仍生存条件化，未来年 `j=k+1..n`）：

```
V_k = (1 / l_{k+1}) * sum_j (期望给付_j + 期望费用_j - 期望保费_j) * f_{j,k}
f_{j,k} = v^{j-k-1} (期初) 或 v^{j-k} (期末);   V_0 不做条件化, V_n = 0
```

反向递推（`claim = S + Ec`，`E_k` 为当年初始/续年费用）：

```
期末时点:  V_{k-1} = v * ( q_k*claim + p_k*V_k - Pg + E_k )
期初时点:  V_{k-1} =       q_k*claim + v*p_k*V_k - Pg + E_k
```

按等价原则定价时必有 `V_0 = 0`。两种方法逐边界比较，
`|V_state - V_backward| > tolerance` 即在输出中产生一条诊断：

```json
{"code": "RESERVE_METHOD_MISMATCH", "detail": "...", "expected": ..., "actual": ..., "tolerance": ...}
```

## 使用方法

无需安装第三方依赖；可选安装为命令：

```powershell
pip install -e .
life-reserves run --input examples/contract.json
```

免安装直接运行：

```powershell
# 合成 Gompertz 死亡率演示（数据由程序生成，非真实行业表）
python -m life_reserves.cli demo --issue-age 40 --term 10 --rate 0.03 --timing end

# 使用自定义 JSON 合同
python -m life_reserves.cli run --input examples/contract.json --out result.json

# 调整两法对账容差
python -m life_reserves.cli run --input examples/contract.json --tolerance 1e-9
```

JSON 合同格式见 `examples/contract.json`；死亡率键为年龄，值为 `[0,1]`
内的 Decimal 字符串，表必须完整覆盖 `issue_age .. issue_age+term-1`，
允许提供多余年龄。`rate_shocks` 可省略（默认 `-0.02, 0, +0.02`）。

库调用：

```python
from decimal import Decimal
from life_reserves.engine import run
from life_reserves.models import Inputs, Timing, Expenses

result = run(Inputs(
    issue_age=40, term=10, sum_assured=Decimal("100000"),
    timing=Timing.END, interest_rate=Decimal("0.03"),
    mortality={40: Decimal("0.00217"), 41: Decimal("0.00235")},  # ...
    expenses=Expenses(initial=Decimal("200"),
                      renewal=Decimal("20"), claim=Decimal("100")),
))
print(result.pricing.gross_premium, result.diagnostics)
```

## 复现 / 测试

```powershell
python -m compileall -q life_reserves tests
python -m unittest discover -s tests -v
python -m life_reserves.cli run --input examples/contract.json
```

测试覆盖：首年确定死亡、终年确定死亡、零死亡率 oracle、零利率与极端
高利率、利率逼近 `-1` 报错、年龄越界、概率越界（`<0`、`>1`、非 Decimal）、
期初/期末时点切换、两准备金方法一致性、逐年递推方程、概率质量划分、
输入重排稳定性、敏感度排序单调性及 CLI 冒烟。
