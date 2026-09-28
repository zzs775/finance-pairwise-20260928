# Implementation-Shortfall Analyzer（实施差额分析器）

TypeScript 5 / Node 22，零运行时依赖（仅使用 Node 内置模块）。根据母单、子单、
成交和本地报价，计算 VWAP、TWAP，并把总实施差额严格分解为
**延迟成本 + 交易成本 + 机会成本 + 显式费用**。

## 1. 定点尺度

- 所有金额、价格、数量、费用在内部都是 `bigint` 定点整数。
- 统一尺度 `FIXED_SCALE = 10^9`（`src/fixedpoint.ts`）。
  - 例如 `"12.34"` 解析为 `12_340_000_000n`；`"0.000000001"` 解析为 `1n`。
  - 输入字符串超过 9 位小数会被拒绝（`E_BAD_NUMBER`）。
- JSON 输入/输出中所有定点数都是十进制字符串（如 `"105.00"`），时间是
  epoch 毫秒整数字符串。
- 中间乘除使用 `mulDiv(a,b,c)`，结果按**向负无穷取整（floor）**；
  中间价 `mid = floor((bid+ask)/2)`。所有取整操作数都写在归因公式里，
  因此报告可逐步手算复核。

## 2. 基准时点与价格

- 基准时点：**母单到达时刻 `parent.arrivalTime`**。
- 基准价格：该时刻“不晚于它的最新有效报价”的中间价
  `arrivalMid = floor((bid+ask)/2)`（`benchmark = { time: "parent_arrival",
  price: "mid" }`）。
- 报价按事件时间连接：一条报价从其时间戳起持续有效，直到下一时间戳的报价
  覆盖它；同一时间戳多条报价时，按 **来源（source 字典序）→ 来源序号 seq**
  裁决。
- TWAP 按报价持续区间做时长加权（毫秒权重），窗口起点早于首条报价的部分是
  **报价缺口**，会在 `twap.gaps` 中显式报告并从覆盖时长中扣除。
- 若母单到达、子单释放/截止、母单截止这些关键时点找不到“不晚于该时刻”的
  报价，则产生 `E_NO_QUOTE`，整份报告 `status = "failed"`。

## 3. 成本分解（买卖符号一致）

方向乘数：`sign = +1`（BUY），`-1`（SELL）。费用与方向无关，始终为正成本。

- 延迟成本（逐子单）
  `sign * child.qty * (releaseMid - arrivalMid)`
- 交易成本（逐成交累加）
  `sign * Σ fill.qty * (fill.price - releaseMid)`
- 机会成本（母单层，部分/完全未成交按截止价）
  `sign * (parent.qty - Σ fill.qty) * (deadlineMid - arrivalMid)`
- 显式费用
  `Σ fill.fee + parent.fee`
- 总实施差额
  `total = delay + trading + opportunity + fee`（**严格相等，逐项 bigint 求和，
  不允许取整差**）

等价的现金流口径（恒等式，oracle 逐成交复算同一结果）：

- BUY：`total = Σ fill.qty*fill.price + fee - parent.qty*arrivalMid
  + unfilled.qty*(arrivalMid - deadlineMid)`
- SELL：符号相反。

VWAP = `Σ fill.qty*fill.price / Σ fill.qty`；无成交时为 `null`。
TWAP = `Σ mid_i*duration_i / Σ duration_i`，缺口区间不参与。

每个归因项都带 `formula.expression` 与 `formula.operands`
（`{name, value, scale}`），可直接对照公式重算。

## 4. 输入、去重与稳定性

- 输入允许乱序、重复成交、重复报价。
- 业务键幂等去重：`parent:<id>`、`child:<id>`、`fill:<id>`、
  `quote:<source>:<seq>`；重复出现的记录只保留首次载荷，并在
  `duplicates` 中报告出现次数。
- 归并后按业务键/时间稳定排序，输出为递归排序键的规范 JSON；
  **输入任意重排，字节级输出不变**（见 `test/pipeline.test.ts`）。
- 坏记录不进入计算，统一带稳定错误码：
  `E_BAD_SIDE`、`E_PARENT_NOT_FOUND`、`E_SIDE_MISMATCH`、
  `E_CHILD_INTERVAL`、`E_FILL_OUTSIDE_CHILD`、`E_FILL_QTY_EXCEEDED`、
  `E_BID_ABOVE_ASK`、`E_BAD_NUMBER`、`E_BAD_TIME`、`E_MISSING_FIELD`、
  `E_NO_QUOTE`、`E_RECONCILE`。
- 校验内容：买卖方向合法、母子关系存在、子单区间落在母单区间内、
  成交落在子单区间内、成交累计量不超过子单量（逐笔裁决）、
  子单量之和不超过母单量、`bid <= ask`。

## 5. Oracle 与整报告失败

`src/oracle.ts` 是独立实现：直接逐成交、逐报价区间重算四项金额，
不调用管线内部代码。管线对每个母单运行 oracle 交叉校验；
`src/reconcile.ts` 再逐层核对
子单→母单→全局的数量与金额恒等，以及四项之和严格等于总额。
任何不一致都产生 `E_RECONCILE` 且 `status = "failed"`。

## 6. CLI

```
node dist/src/cli.js [--input FILE] [--output FILE] [--sample] [--check]
```

- 不带 `--input` 时使用内置确定性合成数据（买卖双向、部分成交、
  同刻双来源报价、重复成交/报价）。
- 输出默认 stdout，可用 `--output` 写文件。
- `--check`：报告失败时进程退出码为 1；参数/IO 错误为 2；成功为 0。

示例输入与报告：`data/sample.json`、`data/sample.report.json`。

## 7. 复现命令

```powershell
# 构建（TypeScript 5.9，零三方运行时依赖）
node node_modules/typescript/lib/tsc.js -p tsconfig.json

# 运行全部自动化测试（48 个用例，覆盖买卖双向、部分成交、
# 同刻事件、报价缺口、重复输入、符号边界）
node --test --experimental-test-isolation=none "dist/test/**/*.test.js"

# 或者
npm test

# 跑合成样本并输出规范 JSON
node dist/src/cli.js --sample --check --output data/sample.report.json

# 跑文件输入
node dist/src/cli.js --input data/sample.json --check
```

说明：Windows 沙箱下 `node --test` 的默认子进程隔离可能被拦截，
因此测试命令带 `--experimental-test-isolation=none`（Node 22.8+）。

## 8. 代码布局

```
src/
  fixedpoint.ts   定点 bigint 运算与解析
  dedupe.ts       业务键幂等去重 / 稳定归并
  validate.ts     解析与全部记录级校验
  quotetimeline.ts 事件时间连接、同刻裁决、TWAP 区间与缺口
  attribution.ts  方向符号、公式操作数、求和
  pricing.ts      逐子单 VWAP/TWAP + 三项归因、母单汇总
  oracle.ts       独立逐成交/逐区间复算
  reconcile.ts    逐层恒等校验（不一致整报告失败）
  pipeline.ts     去重→校验→分析→oracle→reconcile
  io.ts           文件输入输出与规范 JSON
  sample.ts       本地确定性合成数据
  cli.ts          命令行
test/             node:test 自动化测试
data/             示例输入与报告
```
