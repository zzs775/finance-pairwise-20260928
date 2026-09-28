//! Append-only ledger of posted entitlement entries.

use crate::error::Result;
use crate::fixed::Amount;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub event_id: String,
    pub revision: u32,
    /// `true` for reversal legs posted when a correction supersedes a parent.
    pub reversal: bool,
    pub account: String,
    pub instrument: String,
    pub new_instrument: Option<String>,
    pub original_position: i64,
    pub eligible_shares: i64,
    pub cash: Amount,
    pub securities: i64,
    /// Cash paid for fractional shares before tail allocation.
    pub fractional_cash: Amount,
    pub rounding_residual: Amount,
}

pub struct Ledger {
    pub entries: Vec<Entry>,
}

impl Default for Ledger {
    fn default() -> Self {
        Self::new()
    }
}

impl Ledger {
    pub fn new() -> Self {
        Ledger { entries: Vec::new() }
    }

    pub fn from_entries(entries: Vec<Entry>) -> Self {
        Ledger { entries }
    }

    pub fn post(&mut self, _entry: Entry) -> Result<()> {
        self.entries.push(_entry);
        Ok(())
    }

    pub fn posted_revisions(&self, event_id: &str) -> Vec<u32> {
        let mut revs: Vec<u32> = self
            .entries
            .iter()
            .filter(|e| e.event_id == event_id && !e.reversal)
            .map(|e| e.revision)
            .collect();
        revs.sort_unstable();
        revs.dedup();
        revs
    }

    /// Forward entries currently in force for an event revision.
    pub fn revision_entries(&self, event_id: &str, revision: u32) -> Vec<&Entry> {
        self.entries
            .iter()
            .filter(|e| {
                e.event_id == event_id && e.revision == revision && !e.reversal
            })
            .collect()
    }

    /// Reverse (negate) every forward entry of `revision`, appending
    /// mirroring reversal legs. Used when a correction supersedes it.
    pub fn reverse_revision(&mut self, event_id: &str, revision: u32) -> Result<()> {
        let originals: Vec<Entry> = self
            .revision_entries(event_id, revision)
            .into_iter()
            .cloned()
            .collect();
        for e in originals {
            let mirror = Entry {
                event_id: e.event_id.clone(),
                revision: e.revision,
                reversal: true,
                account: e.account.clone(),
                instrument: e.instrument.clone(),
                new_instrument: e.new_instrument.clone(),
                original_position: -e.original_position,
                eligible_shares: -e.eligible_shares,
                cash: e.cash.checked_neg().ok_or_else(|| {
                    crate::error::EngineError::ArithmeticOverflow(
                        "reversal cash negation overflow".to_string(),
                    )
                })?,
                securities: e.securities.checked_neg().ok_or_else(|| {
                    crate::error::EngineError::ArithmeticOverflow(
                        "reversal securities negation overflow".to_string(),
                    )
                })?,
                fractional_cash: e.fractional_cash.checked_neg().ok_or_else(|| {
                    crate::error::EngineError::ArithmeticOverflow(
                        "reversal fractional-cash negation overflow".to_string(),
                    )
                })?,
                rounding_residual: e.rounding_residual.checked_neg().ok_or_else(
                    || {
                        crate::error::EngineError::ArithmeticOverflow(
                            "reversal residual negation overflow".to_string(),
                        )
                    },
                )?,
            };
            self.post(mirror)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(rev: u32, cash: i64, reversal: bool) -> Entry {
        Entry {
            event_id: "ev1".to_string(),
            revision: rev,
            reversal,
            account: "a".to_string(),
            instrument: "AAA".to_string(),
            new_instrument: None,
            original_position: 10,
            eligible_shares: 10,
            cash: Amount(cash),
            securities: 0,
            fractional_cash: Amount::ZERO,
            rounding_residual: Amount::ZERO,
        }
    }

    #[test]
    fn reverse_negates_and_marks_legs() {
        let mut l = Ledger::new();
        l.post(e(1, 100, false)).unwrap();
        l.post(e(2, 250, false)).unwrap();
        l.reverse_revision("ev1", 1).unwrap();
        assert_eq!(l.entries.len(), 3);
        let r = &l.entries[2];
        assert!(r.reversal);
        assert_eq!(r.cash, Amount(-100));
        assert_eq!(r.eligible_shares, -10);
        assert_eq!(l.posted_revisions("ev1"), vec![1, 2]);
    }
}
