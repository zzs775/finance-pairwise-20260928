package covenant;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MetricCatalog {

    private final Map<String, MetricSpec> specs = new LinkedHashMap<>();

    public MetricCatalog(List<MetricSpec> specs) {
        if (specs == null) {
            throw new InvalidInputException("metric spec list must not be null");
        }
        for (MetricSpec spec : specs) {
            if (spec == null || spec.id() == null || spec.id().isBlank()) {
                throw new InvalidInputException("metric spec id must not be blank");
            }
            if (this.specs.putIfAbsent(spec.id(), spec) != null) {
                throw new InvalidInputException("duplicate metric definition: " + spec.id());
            }
        }
    }

    public MetricSpec get(String id) {
        MetricSpec spec = specs.get(id);
        if (spec == null) {
            throw new MissingDefinitionException("metric not defined: " + id);
        }
        return spec;
    }

    public void requireDefined(String id) {
        get(id);
    }

    public boolean isDefined(String id) {
        return specs.containsKey(id);
    }

    public List<MetricSpec> all() {
        return List.copyOf(specs.values());
    }
}
