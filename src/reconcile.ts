import { makeError } from "./errors";
import type {
  AttributionKey,
  Dataset,
  GlobalResult,
  ParentResult,
  ReconcileResult,
} from "./types";

/**
 * Verify that every aggregate amount equals the sum of its detail amounts
 * and that the four attribution items sum (strictly) to total shortfall.
 * Any mismatch makes the whole report fail.
 */
export function reconcile(
  dataset: Dataset,
  global: GlobalResult,
): ReconcileResult {
  const mismatches: ReconcileResult["mismatches"] = [];
  const componentKeys: AttributionKey[] = [
    "delay",
    "trading",
    "opportunity",
    "fee",
  ];

  const checkIdentity = (
    expected: bigint,
    actual: bigint,
    recordKey: string,
    message: string,
  ): void => {
    if (expected !== actual) {
      mismatches.push(makeError("E_RECONCILE", recordKey,
        `${message}: expected ${expected.toString()}, got ${actual.toString()}`));
    }
  };

  for (const parent of global.parentResults) {
    const source = dataset.parents.find((p) => p.id === parent.parentId);
    checkParent(parent, mismatches, source?.fee ?? 0n);
  }

  for (const key of componentKeys) {
    const sum = global.parentResults.reduce(
      (total, parent) => total + componentOf(parent, key).amount, 0n,
    );
    checkIdentity(
      componentOf(global, key).amount,
      sum,
      "global",
      `global ${key} must equal sum of parent ${key}`,
    );
  }

  const componentSum = componentKeys.reduce(
    (sum, key) => sum + componentOf(global, key).amount, 0n,
  );
  checkIdentity(
    global.totalShortfall.amount,
    componentSum,
    "global",
    "global total must strictly equal delay+trading+opportunity+fee",
  );

  checkIdentity(
    global.totalShortfall.amount,
    global.parentResults.reduce(
      (sum, parent) => sum + parent.totalShortfall.amount, 0n,
    ),
    "global",
    "global total must equal sum of parent totals",
  );

  // Dataset quantities must be fully reflected in the report.
  const orderedTotal = dataset.parents.reduce(
    (sum, parent) => sum + parent.qty, 0n,
  );
  const reportedOrdered = global.parentResults.reduce(
    (sum, parent) => sum + parent.orderedQty, 0n,
  );
  checkIdentity(orderedTotal, reportedOrdered, "global",
    "ordered qty mismatch between dataset and report");

  return { ok: mismatches.length === 0, mismatches };
}

function componentOf(
  holder: ParentResult | GlobalResult,
  key: AttributionKey,
): { amount: bigint } {
  switch (key) {
    case "delay": return holder.delayCost;
    case "trading": return holder.tradingCost;
    case "opportunity": return holder.opportunityCost;
    case "fee": return holder.feeCost;
    case "total": return holder.totalShortfall;
  }
}

function checkParent(
  parent: ParentResult,
  mismatches: ReturnType<typeof reconcile>["mismatches"],
  parentLevelFee: bigint,
): void {
  const check = (
    expected: bigint,
    actual: bigint,
    message: string,
  ): void => {
    if (expected !== actual) {
      mismatches.push(makeError("E_RECONCILE", `parent:${parent.parentId}`,
        `${message}: expected ${expected.toString()}, got ${actual.toString()}`));
    }
  };

  let filled = 0n;
  let delay = 0n;
  let trading = 0n;
  let fee = 0n;
  for (const child of parent.childResults) {
    filled += child.filledQty;
    delay += child.delayCost.amount;
    trading += child.tradingCost.amount;
    fee += child.feeCost.amount;
  }
  check(filled, parent.filledQty, "parent filled qty vs child sum");
  check(delay, parent.delayCost.amount, "parent delay vs child sum");
  check(trading, parent.tradingCost.amount, "parent trading vs child sum");

  const componentSum =
    parent.delayCost.amount + parent.tradingCost.amount +
    parent.opportunityCost.amount + parent.feeCost.amount;
  check(componentSum, parent.totalShortfall.amount,
    "parent total must strictly equal four-component sum");

  check(fee + parentLevelFee, parent.feeCost.amount,
    "parent fee must equal child fill fees plus the parent-level fee");
}
