package csm.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 统一尺度的不可变金额/数量值。引擎内所有数值运算都通过本类型，
 * 禁止散落的裸 {@link BigDecimal} 运算。
 */
public final class Amount {

    public static final int SCALE = 6;
    public static final int RATE_SCALE = 10;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal value;

    private Amount(BigDecimal value) {
        this.value = value.setScale(SCALE, ROUNDING);
    }

    public static Amount of(BigDecimal raw) {
        return new Amount(raw);
    }

    public static Amount of(String raw) {
        return new Amount(new BigDecimal(raw));
    }

    public static Amount zero() {
        return new Amount(BigDecimal.ZERO);
    }

    public BigDecimal raw() {
        return value;
    }

    public Amount add(Amount other) {
        return new Amount(value.add(other.value));
    }

    public Amount subtract(Amount other) {
        return new Amount(value.subtract(other.value));
    }

    public Amount negate() {
        return new Amount(value.negate());
    }

    public Amount min(Amount other) {
        return value.compareTo(other.value) <= 0 ? this : other;
    }

    public Amount max(Amount other) {
        return value.compareTo(other.value) >= 0 ? this : other;
    }

    public Amount multiply(Amount factor) {
        return new Amount(value.multiply(factor.value));
    }

    public Amount multiplyRate(BigDecimal rate) {
        BigDecimal normalized = rate.stripTrailingZeros();
        if (normalized.scale() > RATE_SCALE) {
            normalized = normalized.setScale(RATE_SCALE, ROUNDING);
        }
        return new Amount(value.multiply(normalized)
                .setScale(SCALE, ROUNDING));
    }

    public Amount divide(Amount divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("division by zero Amount");
        }
        return new Amount(value.divide(divisor.value, SCALE + 4, ROUNDING));
    }

    public int signum() {
        return value.signum();
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isNonNegative() {
        return value.signum() >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amount other)) {
            return false;
        }
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
