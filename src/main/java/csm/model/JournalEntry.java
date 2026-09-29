package csm.model;

import java.util.List;

/**
 * 一组借贷平衡的分录行。
 */
public record JournalEntry(
        String groupId,
        int periodIndex,
        String step,
        String reference,
        List<JournalLine> lines) {
}
