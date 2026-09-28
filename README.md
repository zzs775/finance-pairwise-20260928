# chainladder-revisable

可修订赔付三角形链梯法（Chain-Ladder）引擎。Python 3.13，**仅使用标准库**，
金额与因子全程使用 `decimal.Decimal` 固定尺度表示：

- 金额：`0.01`（两位小数，`ROUND_HALF_UP`）
- 因子：`0.000000`（六位小数，`ROUND_HALF_UP`）
- 全局精度 60 位，避免大数中间运算溢出/精度损失

## 功能

- 三角形校验，错误按类型分类：
  - 重复单元 `DuplicateCellError`
  - 负赔付 / 累计非减违规 `NegativePaymentError`
  - 已观察边界内部空洞 `InternalHoleError`
  - 非法修订版本 `IllegalVersionError`
  - 零分母（未配置策略时）`ZeroDenominatorError`
  - 形状越界 `ShapeError`
- 三种发展因子：
  - `volume_weighted`：Σ C(i,d+1) / Σ C(i,d)
  - `simple_average`：个体比率 C(i,d+1)/C(i,d) 的算术平均（因子明细含 `ratios`）
  - `fixed`：逐发展期给定选择因子（缺少任一发展期即报错）
- 零分母策略：`error`（默认，报错并给出上下文）、`skip`（剔除零分母单元后重算）、
  `use_one`（该发展期因子取 1.000000）
- 因子审计明细：分子、分母、纳入单元（事故期索引/左值/右值）、个体比率、
  未舍入原始比率、舍入后因子、纳入单元数
- 尾因子（默认 1），必须 ≥ 1；从最新对角点逐期投影，**不覆盖任何已观察单元**
- 恒等关系：每个事故期 `paid + reserve = ultimate`
- 累计 ↔ 增量双向可逆转换，行级与三角形级
- 历史修订：单元携带严格递增版本号；非法修订在落盘前被拒绝；仅重算受影响发展期
  因子（其余 `FactorStep` 对象原样复用），再重跑投影
- 增量更新与全量重算的因子、终极赔付、准备金、**规范 JSON 完全一致**
- 独立矩阵循环 oracle（`chainladder/oracle.py`，不与主引擎共享计算代码）
- 行输入顺序、字段空白、事故期计算顺序均不影响结果与诊断顺序
- CLI 读取本地 CSV/JSON 合成数据，输出 JSON
- 70+ 个 `unittest` 自动化测试，全部使用本地合成数据

## 手算小三角

累计赔付（合成数据）：

| 事故期 | dev1 | dev2 | dev3 |
|--------|-----:|-----:|-----:|
| 2020   |  100 |  150 |  175 |
| 2021   |  120 |  180 |      |
| 2022   |  130 |      |      |

- f1（体积加权）= (150+180)/(100+120) = 330/220 = **1.500000**
- f2 = 175/150 = **1.166667**
- 2022 投影：130 × 1.5 = 195.00；195 × 1.166667 = 227.50
- 无尾因子时准备金：2020 = 0，2021 = 30.00，2022 = 97.50，合计 127.50

## CLI

```bash
python -m chainladder.cli examples/triangle.csv
python -m chainladder.cli examples/triangle.json --method simple_average --tail 1.05
python -m chainladder.cli examples/triangle.csv \
    --method fixed --fixed 1=1.5 --fixed 2=1.2 --canonical
python -m chainladder.cli examples/triangle.csv --zero-policy skip
```

输入格式：

- CSV：表头 `origin,dev,amount`（可选 `version`），空白容忍，顺序任意
- JSON：对象数组 `[{"origin": 2020, "dev": 1, "amount": "100.00"}, ...]`

错误输入以退出码 `2` 输出到 stderr。

## 作为库使用

```python
from chainladder import (
    Triangle, FactorConfig, FactorMethod, ZeroDenominatorPolicy,
    project, revise, incremental_recompute, Cell, to_canonical_json,
)

tri = Triangle.from_rows([
    (2020, 1, "100.00"), (2020, 2, "150.00"), (2020, 3, "175.00"),
    (2021, 1, "120.00"), (2021, 2, "180.00"),
    (2022, 1, "130.00"),
])

cfg = FactorConfig(method=FactorMethod.VOLUME_WEIGHTED)
res = project(tri, cfg, tail_factor="1.05")

previous = res
affected = revise(tri, Cell(origin=2021, dev=2, amount="192.00", version=2))
updated = incremental_recompute(tri, previous, affected, cfg, "1.05")
# updated 与全量 project(tri, cfg, "1.05") 的规范 JSON 完全一致
print(to_canonical_json(updated))
```

累计/增量往返：

```python
from chainladder import cumulative_to_incremental, incremental_to_cumulative
from decimal import Decimal

cum = [Decimal("100.00"), Decimal("150.00"), Decimal("175.00")]
inc = cumulative_to_incremental(cum)   # 100, 50, 25
assert incremental_to_cumulative(inc) == cum
```

## 修订语义

- 修订通过 `revise(triangle, Cell(origin, dev, amount, version))` 提交
- `version` 必须严格大于该单元当前版本，否则抛 `IllegalVersionError`
- 修订后若产生负赔付或累计非减违规，整次修订被拒绝，三角形保持原值
- 受影响发展期集合：
  - 修订 dev=d：过渡 `d-1`（该单元作为右端）恒受影响（d>1）
  - 过渡 `d`（该单元作为左端）仅在该事故期已观察到 d+1 时受影响
- `incremental_recompute` 只重算这些发展期的因子，未受影响的因子对象复用，
  但所有事故期投影都会基于新对角线重新生成

## 测试

```bash
python -m unittest discover -s tests -v
```

覆盖：手算小三角核验、oracle 交叉核验、三种因子、零分母三策略、尾因子、
稀疏边界与单单元三角、修订传播与版本规则、累计/增量往返、大数（无 float）、
顺序/空白/诊断确定性、CLI（CSV/JSON/错误退出码）。

## 模块布局

```
chainladder/
  errors.py          错误分类
  decimal_utils.py   固定尺度 Decimal 工具
  model.py           Cell/Triangle、校验与观察边界
  factors.py         三种因子与零分母策略、因子审计明细
  development.py     累计 <-> 增量
  projection.py      逐期投影、尾因子、paid/reserve/ultimate
  revision.py        版本化修订与增量重算
  oracle.py          独立朴素矩阵循环 oracle
  serialization.py   规范 JSON
  io_utils.py        CSV/JSON 读取
  cli.py             命令行入口
tests/               unittest 测试与本地合成夹具
examples/            示例 CSV/JSON
```
