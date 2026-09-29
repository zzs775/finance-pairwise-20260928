package csm.engine;

import csm.model.Amount;
import csm.model.JournalEntry;
import csm.model.Posting;

import java.util.List;

/** 分录构造助手：每个业务变动产生一笔借贷平衡分录。 */
public final class Journal {

    private long seq;

    public JournalEntry entry(String movementRef, List<Posting> lines) {
        List<Posting> copy = List.copyOf(lines);
        JournalEntry candidate = new JournalEntry(
                "JE-%04d".formatted(++seq), movementRef, copy);
        if (!candidate.isBalanced()) {
            throw new csm.model.OracleException(
                    "unbalanced entry for " + movementRef);
        }
        return candidate;
    }

    public Posting debit(String account, Amount amount) {
        if (!amount.isPositive()) {
            throw new csm.model.ValidationException(
                    "debit amount must be positive: " + amount);
        }
        return new Posting(account, amount, Amount.zero());
    }

    public Posting credit(String account, Amount amount) {
        if (!amount.isPositive()) {
            throw new csm.model.ValidationException(
                    "credit amount must be positive: " + amount);
        }
        return new Posting(account, Amount.zero(), amount);
    }
}
