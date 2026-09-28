package covenant;

public record Unit(Dimension dimension, String name) {

    public static Unit money(String currency) {
        return new Unit(Dimension.MONEY, "MONEY:" + requireName(currency));
    }

    public static Unit ratio() {
        return new Unit(Dimension.RATIO, "ratio");
    }

    public static Unit count() {
        return new Unit(Dimension.COUNT, "count");
    }

    public static Unit day() {
        return new Unit(Dimension.DAY, "day");
    }

    public static Unit interestRate(String name) {
        return new Unit(Dimension.INTEREST_RATE, name);
    }

    private static String requireName(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidInputException("currency code must not be blank");
        }
        return currency.trim().toUpperCase();
    }

    public boolean compatibleWith(Unit other) {
        if (other == null) {
            return false;
        }
        if (dimension != other.dimension) {
            return false;
        }
        return name.equals(other.name);
    }
}
