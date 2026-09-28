package exer;

import exer.model.AllocationPlan;
import exer.model.AccountShare;
import exer.model.Deliverable;
import exer.model.EngineException;
import exer.model.GenerationResult;
import exer.model.Obligation;
import exer.model.RejectReason;
import exer.model.TailLedgerEntry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** 按分配方案与 deliverable 精确汇总现金/证券/复合交割义务。 */
public final class ObligationGenerator {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger TEN = BigInteger.TEN;

    public GenerationResult generate(Deliverable deliverable, AllocationPlan plan) {
        if (deliverable == null || plan == null) {
            throw new EngineException(RejectReason.ILLEGAL_DELIVERABLE,
                    "deliverable and allocation plan must be non-null");
        }
        if (!deliverable.contractId().equals(plan.contractId())) {
            throw new EngineException(RejectReason.ILLEGAL_DELIVERABLE,
                    "deliverable contract " + deliverable.contractId()
                            + " does not match plan contract " + plan.contractId());
        }
        BigInteger plannedTotal = ZERO;
        for (AccountShare share : plan.shares()) {
            plannedTotal = plannedTotal.add(share.quantity());
        }
        if (plannedTotal.compareTo(plan.totalQuantity()) != 0) {
            throw new EngineException(RejectReason.OVER_EXERCISE,
                    "plan shares sum " + plannedTotal + " != notice quantity " + plan.totalQuantity());
        }

        List<Obligation> obligations = new ArrayList<>();
        List<TailLedgerEntry> tailLedger = new ArrayList<>();

        for (AccountShare share : plan.shares()) {
            BigInteger qty = share.quantity();
            List<Obligation.SecurityDelivery> securities = new ArrayList<>();
            for (Deliverable.SecurityLeg leg : deliverable.securityLegs()) {
                BigInteger exact = qty.multiply(leg.unscaledPerContract());
                BigInteger factor = TEN.pow(leg.scale() - leg.settleScale());
                BigInteger delivered = exact.divide(factor);
                BigInteger tail = exact.subtract(delivered.multiply(factor));
                securities.add(new Obligation.SecurityDelivery(leg.symbol(), leg.settleScale(), delivered));
                if (tail.signum() != 0 || factor.compareTo(BigInteger.ONE) > 0) {
                    tailLedger.add(new TailLedgerEntry(share.accountId(), "SECURITY", leg.symbol(),
                            leg.scale(), leg.settleScale(), exact, delivered, tail));
                }
            }
            List<Obligation.CashDelivery> cash = new ArrayList<>();
            for (Deliverable.CashLeg leg : deliverable.cashLegs()) {
                BigInteger exact = qty.multiply(leg.unscaledPerContract());
                BigInteger factor = TEN.pow(leg.scale() - leg.settleScale());
                BigInteger delivered = exact.divide(factor);
                BigInteger tail = exact.subtract(delivered.multiply(factor));
                cash.add(new Obligation.CashDelivery(leg.currency(), leg.settleScale(), delivered));
                if (tail.signum() != 0 || factor.compareTo(BigInteger.ONE) > 0) {
                    tailLedger.add(new TailLedgerEntry(share.accountId(), "CASH", leg.currency(),
                            leg.scale(), leg.settleScale(), exact, delivered, tail));
                }
            }
            obligations.add(new Obligation(plan.noticeKey(), share.accountId(), plan.contractId(),
                    qty, securities, cash));
        }
        return new GenerationResult(obligations, tailLedger);
    }
}
