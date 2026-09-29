package csm.model;

import java.util.List;

/**
 * 保险合同组：期初余额、按期间排列的事件与覆盖单位。
 */
public record ContractGroup(
        String groupId,
        Balances opening,
        List<MovementEvent> events,
        List<CoveragePeriodUnits> coverage) {
}
