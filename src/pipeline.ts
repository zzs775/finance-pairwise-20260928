import { FIXED_SCALE_EXP } from "./fixedpoint";
import { attribution, formula, operand } from "./attribution";
import { analyzeChild, analyzeParent, buildTimelines } from "./pricing";
import { oracleBreakdown } from "./oracle";
import { reconcile } from "./reconcile";
import type {
  Attribution,
  Dataset,
  GlobalResult,
  ParentResult,
  Report,
} from "./types";

const SCALE = FIXED_SCALE_EXP;

function sumComponent(
  parents: ParentResult[],
  pick: (parent: ParentResult) => Attribution,
  key: Attribution["key"],
  expression: string,
): Attribution {
  const amount = parents.reduce((sum, parent) => sum + pick(parent).amount, 0n);
  return attribution(
    key,
    amount,
    formula(expression, [
      operand(`${key}Total`, amount, SCALE),
    ]),
  );
}

/** Full analysis pipeline; report.status is "failed" on any error. */
export function runPipeline(dataset: Dataset): Report {
  const base: Report = {
    status: "ok",
    scale: SCALE,
    benchmark: { time: "parent_arrival", price: "mid" },
    errors: [...dataset.errors],
    duplicates: dataset.duplicates,
    global: null,
  };

  if (dataset.errors.length > 0) {
    base.status = "failed";
    return base;
  }

  const timelines = buildTimelines(dataset);
  const parentResults: ParentResult[] = [];

  try {
    for (const parent of dataset.parents) {
      const childAnalyses = dataset.children
        .filter((child) => child.parentId === parent.id)
        .map((child) =>
          analyzeChild(dataset, timelines, child.id, dataset.errors),
        );
      if (dataset.errors.length > 0) {
        return { ...base, status: "failed" };
      }
      const parentResult = analyzeParent(
        dataset,
        timelines,
        parent.id,
        childAnalyses.map((analysis) => analysis.result),
        dataset.errors,
      );
      if (dataset.errors.length > 0) {
        return { ...base, status: "failed" };
      }
      parentResults.push(parentResult);
    }
  } catch (error) {
    base.errors.push({
      code: "E_NO_QUOTE",
      recordKey: "pipeline",
      message: (error as Error).message,
    });
    return { ...base, status: "failed" };
  }

  parentResults.sort((a, b) =>
    a.parentId < b.parentId ? -1 : a.parentId > b.parentId ? 1 : 0,
  );

  const delayCost = sumComponent(parentResults, (p) => p.delayCost,
    "delay", "sum(parent.delayCost)");
  const tradingCost = sumComponent(parentResults, (p) => p.tradingCost,
    "trading", "sum(parent.tradingCost)");
  const opportunityCost = sumComponent(parentResults, (p) => p.opportunityCost,
    "opportunity", "sum(parent.opportunityCost)");
  const feeCost = sumComponent(parentResults, (p) => p.feeCost,
    "fee", "sum(parent.feeCost)");
  const totalAmount =
    delayCost.amount + tradingCost.amount +
    opportunityCost.amount + feeCost.amount;
  const totalShortfall = attribution(
    "total",
    totalAmount,
    formula("delay + trading + opportunity + fee", [
      operand("delay", delayCost.amount, SCALE),
      operand("trading", tradingCost.amount, SCALE),
      operand("opportunity", opportunityCost.amount, SCALE),
      operand("fee", feeCost.amount, SCALE),
    ]),
  );

  const global: GlobalResult = {
    parentResults,
    delayCost,
    tradingCost,
    opportunityCost,
    feeCost,
    totalShortfall,
  };

  const check = reconcile(dataset, global);
  if (!check.ok) {
    return {
      ...base,
      status: "failed",
      errors: [...base.errors, ...check.mismatches],
      global,
    };
  }

  // Independent oracle cross-check on every parent.
  for (const parentResult of parentResults) {
    const expected = oracleBreakdown(dataset, parentResult.parentId);
    const pairs: Array<[string, bigint, bigint]> = [
      ["delay", expected.delay, parentResult.delayCost.amount],
      ["trading", expected.trading, parentResult.tradingCost.amount],
      ["opportunity", expected.opportunity, parentResult.opportunityCost.amount],
      ["fee", expected.fee, parentResult.feeCost.amount],
      ["total", expected.total, parentResult.totalShortfall.amount],
    ];
    for (const [component, expectedAmount, actualAmount] of pairs) {
      if (expectedAmount !== actualAmount) {
        base.errors.push({
          code: "E_RECONCILE",
          recordKey: `parent:${parentResult.parentId}`,
          message:
            `oracle mismatch for ${component}: oracle=${expectedAmount.toString()} ` +
            `pipeline=${actualAmount.toString()}`,
        });
      }
    }
  }

  if (base.errors.length > 0) {
    return { ...base, status: "failed", global };
  }

  return { ...base, global };
}

/** Convenience entry point used by the CLI after dedupe/normalize. */
export function analyzeDataset(dataset: Dataset): Report {
  return runPipeline(dataset);
}
