package csm.engine;

import csm.model.Account;
import csm.model.JournalEntry;
import csm.model.JournalLine;

import java.math.BigDecimal;
import java.util.List;

import csm.util.DecimalScale;

/**
 * 从每个业务变动生成借贷平衡分录。
 */
public final class JournalFactory {

    public JournalEntry entry(String groupId, int periodIndex, String step,
                              String reference, List<JournalLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new CsmValidationException("journal entry must contain at least one line");
        }
        BigDecimal debitSum = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;
        for (JournalLine line : lines) {
            debitSum = debitSum.add(line.debit());
            creditSum = creditSum.add(line.credit());
        }
        debitSum = DecimalScale.normalize(debitSum);
        creditSum = DecimalScale.normalize(creditSum);
        if (debitSum.compareTo(creditSum) != 0) {
            throw new CsmValidationException(
                    "unbalanced journal entry " + step + "/" + reference
                            + ": debit=" + debitSum + ", credit=" + creditSum);
        }
        return new JournalEntry(groupId, periodIndex, step, reference, List.copyOf(lines));
    }

    public JournalLine line(Account account, BigDecimal debit, BigDecimal credit) {
        if (account == null) {
            throw new CsmValidationException("journal account must not be null");
        }
        BigDecimal d = DecimalScale.normalize(debit == null ? BigDecimal.ZERO : debit);
        BigDecimal c = DecimalScale.normalize(credit == null ? BigDecimal.ZERO : credit);
        if (d.signum() < 0 || c.signum() < 0) {
            throw new CsmValidationException("journal line amounts must be non-negative: " + account);
        }
        if ((d.signum() == 0) == (c.signum() == 0)) {
            throw new CsmValidationException(
                    "journal line must be either debit or credit, not both/neither: " + account);
        }
        return new JournalLine(account, d, c);
    }

    public JournalLine debit(Account account, BigDecimal amount) {
        return line(account, amount, BigDecimal.ZERO);
    }

    public JournalLine credit(Account account, BigDecimal amount) {
        return line(account, BigDecimal.ZERO, amount);
    }
}
