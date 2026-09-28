# 公司行动权益引擎 (Corporate Action Entitlement Engine)

纯 Rust stable、仅使用标准库（`std`）实现的公司行动权益处理引擎。依据本地头寸
时间线处理**现金分红、拆股/并股、配股、合并换股**四类行动以及由此产生的
**零碎权益（碎股转现金）**，支持公告版本链与更正冲回，并同时提供
**增量索引引擎**与**朴素全量重建引擎**两种实现，二者在随机事件序列下结果必须一致。

## 构建与运行

```bash
cargo test                 # 全部单元测试 + 端到端 + 随机一致性
cargo run -- demo          # 内置合成数据集，双引擎对账并校验守恒式
cargo run -- run FILE 60   # 读取文本输入文件，60 为注入时钟的“今天”
```

CLI 输出包含两段机器可读报告：

- `# incremental == naive full-rebuild: OK` —— 两种引擎规范输出逐字节相同；
- `# conservation identities: OK` —— 每个事件的理论经济权益守恒式全部成立。

## 核心概念

### 业务时间与日期

- `Stamp { date, seq }`：业务日期 + 接收序号。排序先按日期、同日期按序号，
  杜绝同一笔流在乱序到达时结果不同。
- 每个公告显式区分四个日期：
  - `announced_at`：公告时间（必须不晚于 record date）；
  - `record_date`：股权登记日，**资格数量唯一取此日收盘冻结快照**；
  - `ex_date`：除权日，必须严格晚于 record date；ex 日及之后的交易
    **永不倒灌**进权益快照；
  - `pay_date`：到账日，不得早于 ex date。

上述日期顺序遵循中国 A 股约定（登记日次一交易日除权）。逆序输入归类为
`DateOrder` 拒绝。

### 定点整数

- 所有现金使用 `Amount(i64)`，尺度为 `1_000_000_000`（1 元 = 10⁹ 纳元）；
- 份额为整数 `i64`，允许**负头寸（空头）**，空头的现金/证券权益带负号；
- 比例 `Ratio { num, den }` 要求分子分母严格为正，否则 `InvalidRatio`；
- 所有乘加在 `i128` 中间空间完成，结果无法落回 `i64` 时分类为
  `ArithmeticOverflow`，绝不静默回绕。

### 资格快照

`PositionTimeline::frozen_snapshot(instrument, record_date)` 只累计
`time <= (record, u64::MAX)` 的头寸增量，按业务时间+序号排序后净额轧差；
账户行按账户名字典序规范返回，因此账户输入顺序不影响结果。

### 零碎权益与稳定尾差

拆股、并股、配股、合并均可能产生不足一股的零碎权益：

- 先按题内 `frac_price`（每新股纳元价）把每份碎股折算为现金，除法向零截断；
- 截断丢掉的纳元（“尾差”）在全事件范围内累计，**稳定**地分配给
  残差接收者：优先使用公告显式指定的合格多头账户，否则取
  字典序第一个净多头账户。账户/公告输入顺序变化不会改变接收者。

## 行动语义

| 行动 | 证券 | 现金 |
|------|------|------|
| 现金分红 | 无 | `eligible * per_share`（空头为负） |
| 拆股/并股 | `eligible * num/den` 取整 | 碎股按 `frac_price` 转现金 + 尾差 |
| 配股 | `eligible * num/den` 取整 | 整股按配股价 `price` 认缴（多头为现金流出），碎股按 `frac_price` 转现金 |
| 合并换股 | 换入 `new_instrument`，比例同上 | 碎股按 `frac_price` 转现金 |

每条分录输出：原头寸 `original_position`、资格数量 `eligible_shares`、
应收现金 `cash`、应收证券 `securities`、碎股现金 `fractional_cash`、
舍入残差 `rounding_residual`。

## 守恒式（精确有理复算）

`src/conservation.rs` 用 i128 精确有理分数（自动约分）独立复算每个事件：

- **现金分红**：`Σ eligible·per_share == Σ cash`；
- **拆股/并股/合并**（以新股计）：
  `Σ eligible·num/den == Σ whole_new + Σ (fractional_cash + residual)/frac_price`；
- **配股**：证券恒等式同上；现金恒等式
  `cash == 碎股公允价值 − whole_new·price`。

空头账户以负号对称参与；任何非零差距都会被列为 conservation violation。

## 公告版本链

- 首个版本 `revision = 1`、`parent_revision = None`；
- 后续版本通过**显式父版本号**形成链；发布时先把父版本的全部正向分录
  生成镜像**冲回腿**（`REVERSAL`，所有数量与金额取负），再按新版本入账，
  净效果即“只冲回旧版本差额再入账新差额”；
- `(event_id, revision)` 已接收则重放为幂等 no-op，重复更正/重放绝不重复记账；
- 引用缺失或被拒绝父版本的公告不发布任何新分录；父版本**尚未到达**时
  进入待处理缓冲，父版本入账后自动重试——因此乱序公告与有序公告最终结果一致。

## 双引擎一致性

- **增量引擎**（`src/engine.rs`）：维护头寸时间线、追加式账本、已接收/已拒绝/
  待处理集合；
- **朴素全量重建**（`src/rebuild.rs`）：对原始输入去重、规范排序、解析版本链、
  跳过孤儿，然后重放同一套过账代码。

`tests/parity.rs` 在 80 个确定性 LCG 随机场景（随机负头寸、重复 id、
重复更正、孤儿版本、乱序到达）下逐字节比较两种引擎的规范输出，并单独验证
输入置换后输出与尾差接收者不变。

## 输入文件语法

每行一条记录，`#` 为注释，字段以 `|` 分隔，现金一律使用纳元整数：

```text
POS <id>|<account>|<instrument>|<date>|<seq>|<delta>
DIV <event>|<rev>|<parent|->|<instrument>|<record>|<ex>|<pay>|<ann_date>|<ann_seq>|<per_share_nano>
SPL ...|<num>|<den>|<frac_price_nano>
RTS ...|<num>|<den>|<price_nano>|<frac_price_nano>
MRG ...|<num>|<den>|<new_instrument>|<frac_price_nano>
RES <event>|<recipient account|->
```

解析错误归类为 `InvalidInput`。

## 错误分类

`EngineError`：`DuplicateEventId`、`DuplicateCorrection`、`DateOrder`、
`InvalidRatio`、`ArithmeticOverflow`、`MissingParent`、`InvalidInput`。

## 故障恢复

账本仅追加、过账幂等。崩溃后从原始日志重新加载头寸并重放公告即可重建同一账本；
`tests/end_to_end.rs::crash_recovery_replaying_the_journal_is_idempotent`
验证含重复重放的两次重放结果逐字节相同。

## 时钟

`FixedClock` 与可推进的 `MutableClock` 实现同一 `Clock` trait，
测试通过注入时钟控制“今天”并跨越 record/ex/pay 边界。
