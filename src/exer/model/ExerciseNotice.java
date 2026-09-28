package exer.model;

import java.math.BigInteger;

/** 本地合成的行权通知（不可变）。 */
public record ExerciseNotice(String idempotencyKey, String contractId, BigInteger quantity) {
}
