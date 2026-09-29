package csm.engine;

import csm.model.Direction;
import csm.model.MovementType;
import csm.model.RuleVersion;

import java.math.BigDecimal;

/**
 * 题内冻结规则。不同版本只可能改变生效后事件的处理参数。
 */
public final class RuleBook {

    private static final BigDecimal V1_ACCRETION_RATE = new BigDecimal("0.05");
    private static final BigDecimal V2_ACCRETION_RATE = new BigDecimal("0.06");

    private final RuleVersion version;

    private RuleBook(RuleVersion version) {
        this.version = version;
    }

    public static RuleBook forVersion(RuleVersion version) {
        if (version == null) {
            throw new CsmValidationException("unknown rule version: null");
        }
        return new RuleBook(version);
    }

    public BigDecimal accretionRate() {
        return switch (version) {
            case V1 -> V1_ACCRETION_RATE;
            case V2 -> V2_ACCRETION_RATE;
        };
    }

    public RuleVersion version() {
        return version;
    }

    public void requireConsistentSign(MovementType type, Direction direction, BigDecimal amount) {
        if (type == null) {
            throw new CsmValidationException("movement type must not be null");
        }
        if (direction == null) {
            throw new CsmValidationException("direction must not be null for " + type);
        }
        if (amount == null) {
            throw new CsmValidationException("amount must not be null for " + type);
        }
        int sign = amount.signum();
        switch (type) {
            case INTEREST_ACCRETION -> require(type, direction, Direction.NEUTRAL, sign >= 0);
            case FUTURE_SERVICE_CHANGE -> checkAdjustmentSign(type, direction, sign, amount);
            case RISK_ADJUSTMENT_CHANGE -> checkAdjustmentSign(type, direction, sign, amount);
            case CURRENT_SERVICE_RELEASE -> {
                if (direction == Direction.ADVERSE || sign > 0) {
                    throw signConflict(type, direction, amount);
                }
            }
            case CASH_SETTLEMENT -> require(type, direction, Direction.NEUTRAL, sign >= 0);
        }
    }

    private static void checkAdjustmentSign(MovementType type, Direction direction, int sign,
                                            BigDecimal amount) {
        switch (direction) {
                case ADVERSE -> {
                    if (sign < 0) {
                        throw signConflict(type, direction, amount);
                    }
                }
                case FAVORABLE -> {
                    if (sign > 0) {
                        throw signConflict(type, direction, amount);
                    }
                }
                case NEUTRAL -> {
                    if (sign != 0) {
                        throw signConflict(type, direction, amount);
                    }
                }
        }
    }

    private static void require(MovementType type, Direction actual, Direction expected, boolean signOk) {
        if (actual != expected || !signOk) {
            throw signConflict(type, actual, null);
        }
    }

    private static CsmValidationException signConflict(MovementType type, Direction direction,
                                                       BigDecimal amount) {
        return new CsmValidationException(
                "sign conflict: type=" + type + ", direction=" + direction
                        + (amount == null ? "" : ", amount=" + amount));
    }
}
