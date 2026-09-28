//! Canonical, order-independent rendering of ledger entries.

use crate::ledger::Ledger;

/// Canonical entry ordering. Sorting on every field means the result is
/// identical regardless of the order accounts or announcements arrived in.
pub fn canonical_rows(ledger: &Ledger) -> Vec<crate::ledger::Entry> {
    let mut rows = ledger.entries.clone();
    rows.sort_by(|a, b| {
        let ka = (
            &a.event_id,
            a.revision,
            a.reversal,
            &a.account,
            &a.instrument,
            &a.new_instrument,
            a.original_position,
            a.eligible_shares,
            a.cash,
            a.securities,
            a.rounding_residual,
        );
        let kb = (
            &b.event_id,
            b.revision,
            b.reversal,
            &b.account,
            &b.instrument,
            &b.new_instrument,
            b.original_position,
            b.eligible_shares,
            b.cash,
            b.securities,
            b.rounding_residual,
        );
        ka.cmp(&kb)
    });
    rows
}

/// Render one ledger as canonical, machine-parseable pipe-delimited lines.
pub fn render(ledger: &Ledger) -> String {
    let mut out = String::from(
        "event|rev|leg|account|instrument|new_instrument|\
         original|eligible|cash_nano|securities|fractional_cash_nano|\
         residual_nano",
    );
    for e in canonical_rows(ledger) {
        out.push('\n');
        out.push_str(&format!(
            "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            e.event_id,
            e.revision,
            if e.reversal { "REVERSAL" } else { "FORWARD" },
            e.account,
            e.instrument,
            e.new_instrument.as_deref().unwrap_or("-"),
            e.original_position,
            e.eligible_shares,
            e.cash.raw(),
            e.securities,
            e.fractional_cash.raw(),
            e.rounding_residual.raw(),
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixed::Amount;
    use crate::ledger::Entry;

    fn entry(event: &str, account: &str, cash: i64) -> Entry {
        Entry {
            event_id: event.to_string(),
            revision: 1,
            reversal: false,
            account: account.to_string(),
            instrument: "AAA".to_string(),
            new_instrument: None,
            original_position: 1,
            eligible_shares: 1,
            cash: Amount(cash),
            securities: 0,
            fractional_cash: Amount::ZERO,
            rounding_residual: Amount::ZERO,
        }
    }

    #[test]
    fn rendering_is_order_independent() {
        let a = Ledger::from_entries(vec![
            entry("e", "b", 20),
            entry("e", "a", 10),
        ]);
        let b = Ledger::from_entries(vec![
            entry("e", "a", 10),
            entry("e", "b", 20),
        ]);
        assert_eq!(render(&a), render(&b));
    }
}
