package csm.model;

import java.math.BigDecimal;

/**
 * 三个滚动余额：合同服务边际(CSM)、亏损组成(loss component)、履约现金流负债(FCF)。
 * 三者均以非负值记账（FCF 为负债正向）。
 */
public record Balances(
        BigDecimal csm,
        BigDecimal lossComponent,
        BigDecimal fulfillmentCashFlows) {
}
