# xbrlcheck

本地 XBRL 计算链接库（calculation linkbase）校验器。解析题内简化 XML 的事实
（facts）、上下文（contexts）、单位（units）与 `summation-item` 弧，使用
`Decimal` 做允许区间算术，生成**可复核、字节稳定**的不一致报告。仅依赖 Python 3.13
标准库。

## 特性

- 加固 XML 解析：**禁用外部实体**（DOCTYPE 一律拒绝），并限制嵌套深度、元素总数、
  文本长度与文档体积，所有解析错误带稳定错误码与 `源文件:行:列`。
- 结构校验：重复事实、未知概念、缺失 context / unit、单位冲突、非法 `decimals`、
  非法 period、计算环。
- 按 role 构建有向计算图；同一父子对只保留**最高优先级**弧（整数权重），等优先级
  冲突报 `E201`，被淘汰的较低优先级弧报 `I401` 且不参与计算。
- **禁止跨 context 或跨 unit 相加**（`E202` / `E203`）。
- 环检测输出**规范最小环证据**（词典序最小、最小节点旋转归一）。
- 事实值用 `Decimal`，由 `decimals` 推导允许区间（`decimals=n` → 半宽
  `0.5 * 10^-n`），**不预先四舍五入**；父项报告区间与子项加权和区间无交集时
  报 `W301`，列出每个贡献项、各自区间与**最小差额**。
- 支持同一 taxonomy 的**版本化链接库**与**增量事实修订**：受影响概念沿反向依赖
  传播重算；增量报告与全图重建**逐字节相同**；失败更新（存在错误）**不替换**
  当前版本。
- 小图枚举 oracle + 手算夹具交叉验证生产实现；报告按 role、概念、context、unit
  稳定排序，且与 XML 元素顺序无关。

## 简化 XML 词汇

链接库：

```xml
<linkbase>
  <concept name="Assets" type="monetary"/>
  <concept name="Cash" type="monetary"/>
  <concept name="AR"   type="monetary"/>
  <arc role="balance-sheet" from="Assets" to="Cash" weight="1" priority="0" order="1"/>
  <arc role="balance-sheet" from="Assets" to="AR"   weight="1" priority="0" order="2"/>
</linkbase>
```

- `type`：`monetary | decimal | shares | percent | number | string`；非数值类型
  不得参与计算弧、不得携带 unit。
- `weight` 必须是整数（可为负，如 `Income - Expense`）；`priority` 整数，
  `order` 决定同一父项下展示顺序。

实例文档：

```xml
<xbrl>
  <context id="c1"><period><instant>2025-12-31</instant></period></context>
  <context id="c2"><period>
    <startDate>2025-01-01</startDate><endDate>2025-12-31</endDate>
  </period></context>
  <unit id="usd"><measure>iso4217:USD</measure></unit>
  <unit id="eps"><divide>
    <unitNumerator><measure>iso4217:USD</measure></unitNumerator>
    <unitDenominator><measure>xbrli:shares</measure></unitDenominator>
  </divide></unit>
  <Assets contextRef="c1" unitRef="usd" decimals="-3">1000</Assets>
  <Cash   contextRef="c1" unitRef="usd" decimals="0">600</Cash>
</xbrl>
```

增量修订（只允许事实；按 `(concept, contextRef, unitRef)` upsert）：

```xml
<revision>
  <Cash contextRef="c1" unitRef="usd" decimals="0">650</Cash>
</revision>
```

## 区间语义

| `decimals` | 含义 | 区间 |
| --- | --- | --- |
| 缺省 / `INF` | 精确 | `[v, v]` |
| `2` | 四舍五入到 0.01 | `[v - 0.005, v + 0.005]` |
| `-3`（千） | 四舍五入到 1000 | `[v - 500, v + 500]` |

加权子项区间：`w * [lo, hi]`（负权重翻转端点）后逐端相加。父项区间与加权和区间
**相切即视为有交集**（闭区间），不报警；否则 `W301` 给出最小差额
`max(lo_parent - hi_children, lo_children - hi_parent)`。

跨 context / unit 判定：某父项在桶 `(ctx, unit)` 有事实，但其某子项在**同桶缺失**、
仅在其它 context（→ `E202`）或同 context 其它 unit（→ `E203`）存在时报警；此时
该桶不再产出 `W301`，避免误导。不同期间各自独立求和，互不视为错误。

## 稳定错误码

| 码 | 含义 |
| --- | --- |
| `E001` | 解析资源上限（深度 / 元素数 / 文本 / 体积） |
| `E002` | DOCTYPE / 外部实体被拒绝 |
| `E003` | XML 语法错误 |
| `E101` | 重复事实（同 concept+context+unit） |
| `E102` | 未知概念（事实或弧引用） |
| `E103` | `contextRef` 无法解析 |
| `E104` | `unitRef` 无法解析 |
| `E105` | 单位使用冲突（空单位 / 非数值事实携带单位） |
| `E106` | 非法 `decimals` 或非法十进制值 |
| `E107` | 非法 context period |
| `E201` | 弧冲突（等优先级不同权重 / 非数值端点 / 缺端点） |
| `E202` | 跨 context 相加 |
| `E203` | 跨 unit 相加 |
| `E204` | role 计算图中的环 |
| `W301` | 父项与加权子项区间无交集 |
| `I401` | 重复的较低优先级弧被忽略 |
| `I402` | 增量修订被拒绝，当前版本保留 |

## CLI

```bash
# 校验（有 error 退出码 2，解析安全错误 5，缺文件 6）
python -m xbrlcheck.cli validate linkbase.xml instance.xml -o report.txt

# 增量修订（接受且与全量重建一致退出 0；被拒绝退出 3；增量≠全量退出 4）
python -m xbrlcheck.cli revise linkbase.xml instance.xml revision.xml --base-version 1

# 资源上限可调
python -m xbrlcheck.cli validate lb.xml inst.xml \
  --max-depth 64 --max-elements 100000 \
  --max-text-length 1000000 --max-document-bytes 20000000
```

安装后也可使用 `xbrlcheck` 命令（`pip install -e .`，无第三方依赖）。

报告为 UTF-8、LF、固定表头与汇总行，诊断按 `严重级别 → 码 → role → concept →
context → unit → 源位置` 排序，因此对同一逻辑状态**逐字节稳定**。

## 版本化与增量一致性

- `VersionedStore(taxonomy, instance, version)` 建立版本 1 并固化报告。
- `apply_revision(revision)`：upsert 事实 → 从修订概念沿**反向弧**标记全部受影响
  概念（子变 → 父重算）→ 全量校验 → 仅在无 error 时提交新版本。
- 提交结果会与“从零重建”的报告做逐字节比对（`byte_identical_rebuild`）。
- 存在 error 时返回 `accepted=False`、`I402`，当前 `version`、事实集与报告
  **原样保留**。
- `load_linkbase_version(taxonomy, version)` 可原子替换链接库版本；引入 error 同样
  拒绝并保留旧版本。

## 测试

```bash
python -m unittest discover -s tests -v
```

全部使用标准库 `unittest`，输入均为**本地合成财务数据**。覆盖：

- XXE / DOCTYPE 拒绝、深度 / 元素数 / 文本 / 体积上限、源位置；
- 多单位、多期间、重复弧与优先级、等优先级冲突、区间边界相切、自环与多环归一；
- 每个事实恰归属其 context、XML 元素顺序无关、报告稳定排序与字节稳定；
- 增量 upsert、反向重算、失败更新原子性、增量与全量逐字节一致；
- oracle 手算夹具（精确和、边界相切、差额 1、负权重、跨单位、多期间、环、重复弧）
  与 2 节点小图的确定性枚举（权重 × 值组合）。

## 包结构

```
xbrlcheck/
  xmlsafe.py     加固 XML 读取（expat，无 DOCTYPE / 外部实体，资源上限，行列位置）
  limits.py      上限数据类
  model.py       Fact / Context / Unit / Arc / Taxonomy / Instance
  parseinput.py  实例、链接库、修订解析
  intervals.py   Decimal 区间、decimals 推导、加权求和
  graph.py       role 图、优先级裁决、Tarjan SCC、规范最小环
  validate.py    结构 + 跨 context/unit + 区间一致性校验
  report.py      规范字节稳定报告
  versioning.py  版本化链接库、增量修订、原子提交、反向重算
  oracle.py      暴力枚举 oracle 与手算夹具
  cli.py         validate / revise 子命令
```
