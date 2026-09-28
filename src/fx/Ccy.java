package fx;

import java.util.Objects;

/** ISO-4217-like three-letter currency code. */
public record Ccy(String code) {
    public Ccy {
        Objects.requireNonNull(code, "code");
        if (!code.matches("[A-Z]{3}")) {
            throw new FxException(Failure.INVALID_CURRENCY, "invalid currency code: " + code);
        }
    }

    public static Ccy of(String code) {
        if (code == null || !code.trim().matches("[A-Z]{3}")) {
            throw new FxException(Failure.INVALID_CURRENCY, "invalid currency code: " + code);
        }
        return new Ccy(code.trim());
    }

    @Override
    public String toString() {
        return code;
    }
}
