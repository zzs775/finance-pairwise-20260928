package fx;

/** Directed currency pair, e.g. EUR/USD quoted as units of counter per one base. */
public record Pair(Ccy base, Ccy counter) {

    public Pair {
        if (base == null || counter == null) {
            throw new FxException(Failure.INVALID_PAIR, "pair legs must be non-null");
        }
        if (base.equals(counter)) {
            throw new FxException(Failure.INVALID_PAIR, "pair legs must differ: " + base);
        }
    }

    public static Pair of(String base, String counter) {
        return new Pair(Ccy.of(base), Ccy.of(counter));
    }

    public Pair inverted() {
        return new Pair(counter, base);
    }

    @Override
    public String toString() {
        return base + "/" + counter;
    }
}
