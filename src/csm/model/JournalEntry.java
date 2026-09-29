package csm.model;

import java.util.List;

/** 一个业务变动生成的借贷平衡分录。 */
public record JournalEntry(String entryId, String movementRef, List<Posting> lines) {

    public boolean isBalanced() {
        Amount debits = Amount.zero();
        Amount credits = Amount.zero();
        for (Posting line : lines) {
            debits = debits.add(line.debit());
            credits = credits.add(line.credit());
            if (line.debit().isNegative() || line.credit().isNegative()) {
                return false;
            }
            if (!line.debit().isZero() && !line.credit().isZero()) {
                return false;
            }
        }
        return debits.equals(credits);
    }
}
