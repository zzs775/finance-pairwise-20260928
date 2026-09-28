package loancashflow.model;

import java.util.List;

/** A generated cash-flow plan plus the originating contract. */
public record ScheduleResult(Contract contract, List<CashFlowRow> rows) {

    public ScheduleResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
