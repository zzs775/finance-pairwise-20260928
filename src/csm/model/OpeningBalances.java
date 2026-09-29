package csm.model;

/** 组级期初余额：履约现金流、CSM、亏损组成（均为负债方向正数口径）。 */
public record OpeningBalances(Amount fulfillmentCashFlow, Amount csm, Amount lossComponent) {
}
