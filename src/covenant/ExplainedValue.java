package covenant;

import java.math.BigDecimal;
import java.util.List;

public record ExplainedValue(BigDecimal value,
                             List<Fact> sources,
                             String formulaTree) {
}
