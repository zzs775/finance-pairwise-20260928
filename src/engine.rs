//! Incremental, journaled engine (the "index" implementation).

use crate::clock::Clock;
use crate::error::{EngineError, Result};
use crate::fixed::Amount;
use crate::ledger::{Entry, Ledger};
use crate::model::{Announcement, PositionTx};
use crate::positions::PositionTimeline;
use std::collections::{BTreeMap, BTreeSet};

pub struct Engine<C: Clock> {
    pub timeline: PositionTimeline,
    pub ledger: Ledger,
    pub clock: C,
    /// (event_id, revision) pairs already accepted. Guarantees replays and
    /// duplicate corrections never post twice.
    accepted: BTreeSet<(String, u32)>,
    /// (event_id, revision) seen but rejected as orphan/invalid: remembered so
    /// a replay returns the same error without posting, and a later sibling
    /// revision is not poisoned by the dead branch.
    rejected: BTreeMap<(String, u32), EngineError>,
    /// Revisions waiting for a parent that has not arrived yet. Retried
    /// whenever a new revision is accepted, so arrival order does not matter.
    pending: Vec<Announcement>,
}

impl<C: Clock> Engine<C> {
    pub fn new(clock: C) -> Self {
        Engine {
            timeline: PositionTimeline::new(),
            ledger: Ledger::new(),
            clock,
            accepted: BTreeSet::new(),
            rejected: BTreeMap::new(),
            pending: Vec::new(),
        }
    }

    pub fn load_position(&mut self, tx: PositionTx) -> Result<()> {
        self.timeline.push(tx)
    }

    /// Publish one announcement revision. Replays are no-ops; a missing
    /// parent posts nothing.
    pub fn publish(&mut self, ann: Announcement) -> Result<()> {
        let key = (ann.event_id.clone(), ann.revision);
        if self.accepted.contains(&key) {
            // Idempotent replay: nothing must be posted again.
            return Ok(());
        }
        if let Some(err) = self.rejected.get(&key) {
            return Err(err.clone());
        }
        if let Err(err) = validate_announcement(&ann) {
            self.rejected.insert(key, err.clone());
            return Err(err);
        }
        match ann.parent_revision {
            None => {
                if ann.revision != 1 {
                    let err = EngineError::MissingParent(format!(
                        "event {} first revision must be 1, got {}",
                        ann.event_id, ann.revision
                    ));
                    self.rejected.insert(key, err.clone());
                    return Err(err);
                }
            }
            Some(parent) => {
                if !self.accepted.contains(&(ann.event_id.clone(), parent)) {
                    // Parent not seen yet: park for later retry. A revision
                    // whose parent was explicitly rejected stays unresolvable
                    // and is parked forever (never posts).
                    let parent_key = (ann.event_id.clone(), parent);
                    if self.rejected.contains_key(&parent_key) {
                        let err = EngineError::MissingParent(format!(
                            "event {} revision {} parent {} was rejected",
                            ann.event_id, ann.revision, parent
                        ));
                        self.rejected.insert(key, err.clone());
                        return Err(err);
                    }
                    if !self.pending.iter().any(|p| {
                        p.event_id == ann.event_id && p.revision == ann.revision
                    }) {
                        self.pending.push(ann);
                    }
                    return Ok(());
                }
                if parent >= ann.revision {
                    let err = EngineError::InvalidInput(format!(
                        "event {} revision {} parent {} is not older",
                        ann.event_id, ann.revision, parent
                    ));
                    self.rejected.insert(key, err.clone());
                    return Err(err);
                }
                // Reverse the superseded parent legs before posting the delta.
                if let Err(err) = self.ledger.reverse_revision(&ann.event_id, parent) {
                    self.rejected.insert(key, err.clone());
                    return Err(err);
                }
            }
        }
        let posted = match self.post_entitlements(&ann) {
            Ok(p) => p,
            Err(err) => {
                self.rejected.insert(key, err.clone());
                return Err(err);
            }
        };
        for entry in posted {
            if let Err(err) = self.ledger.post(entry) {
                self.rejected.insert(key, err.clone());
                return Err(err);
            }
        }
        self.accepted.insert(key);
        // Try to drain anything that was waiting on this event.
        self.drain_pending();
        Ok(())
    }

    fn drain_pending(&mut self) {
        loop {
            let Some(idx) = self.pending.iter().position(|p| {
                match p.parent_revision {
                    Some(parent) => self
                        .accepted
                        .contains(&(p.event_id.clone(), parent)),
                    None => true,
                }
            }) else {
                break;
            };
            let next = self.pending.swap_remove(idx);
            // Recurse via publish; replays/rejections are already handled.
            let _ = self.publish(next);
        }
    }

    pub fn entries(&self) -> &[Entry] {
        &self.ledger.entries
    }

    fn post_entitlements(&self, ann: &Announcement) -> Result<Vec<Entry>> {
        let snapshot =
            self.timeline.frozen_snapshot(&ann.instrument, ann.record_date)?;
        // Stable, input-order-independent residual recipient:
        // explicit account if eligible, else lexicographically first
        // account with a strictly long entitlement.
        let fallback = snapshot
            .iter()
            .filter(|(_, q)| *q > 0)
            .map(|(a, _)| a.clone())
            .min();
        let recipient = match (&ann.residual_recipient, fallback) {
            (Some(name), _) if snapshot.iter().any(|(a, q)| a == name && *q > 0) => {
                name.clone()
            }
            (_, Some(first)) => first,
            (Some(name), None) => name.clone(),
            (None, None) => String::new(),
        };
        // First pass: gross entitlements and tail accumulation.
        let mut gross: Vec<(String, i64, crate::actions::Entitlement)> =
            Vec::with_capacity(snapshot.len());
        let mut tail = 0i128;
        for (account, eligible) in &snapshot {
            let ent = crate::actions::compute_entitlement(&ann.terms, *eligible)?;
            if *eligible > 0 {
                // Fractional cash was computed per account with truncation;
                // the dropped nanounits accumulate as the distributable tail.
                let exact = exact_frac_nano(&ann.terms, *eligible);
                tail += exact - i128::from(ent.frac_cash.0);
            }
            gross.push((account.clone(), *eligible, ent));
        }
        let tail64 = crate::fixed::fits_i64(tail).ok_or_else(|| {
            EngineError::ArithmeticOverflow(
                "rounding tail outside i64 range".to_string(),
            )
        })?;
        let mut out = Vec::with_capacity(gross.len());
        for (account, eligible, ent) in gross {
            let is_recipient = account == recipient && eligible > 0;
            let residual = if is_recipient { tail64 } else { 0 };
            let cash = ent
                .cash
                .checked_add(ent.frac_cash)
                .and_then(|c| c.checked_add(Amount(residual)))
                .ok_or_else(|| {
                    EngineError::ArithmeticOverflow(format!(
                        "cash composition overflow for account {}",
                        account
                    ))
                })?;
            out.push(Entry {
                event_id: ann.event_id.clone(),
                revision: ann.revision,
                reversal: false,
                account,
                instrument: ann.instrument.clone(),
                new_instrument: match &ann.terms {
                    crate::model::ActionTerms::Merger { new_instrument, .. } => {
                        Some(new_instrument.clone())
                    }
                    _ => None,
                },
                original_position: eligible,
                eligible_shares: eligible,
                cash,
                securities: ent.securities,
                fractional_cash: ent.frac_cash,
                rounding_residual: Amount(residual),
            });
        }
        Ok(out)
    }
}

/// Validate date ordering (China A-share convention):
/// announced <= record < ex <= pay. Announcements arriving after the record
/// date are refused because the snapshot could no longer be taken.
pub fn validate_announcement(ann: &Announcement) -> Result<()> {
    if ann.record_date >= ann.ex_date {
        return Err(EngineError::DateOrder(format!(
            "event {} record date must be strictly before ex date",
            ann.event_id
        )));
    }
    if ann.ex_date > ann.pay_date {
        return Err(EngineError::DateOrder(format!(
            "event {} pay date must not precede ex date",
            ann.event_id
        )));
    }
    if ann.announced_at.date > ann.record_date {
        return Err(EngineError::DateOrder(format!(
            "event {} announced {} after record date {}",
            ann.event_id,
            ann.announced_at.date.0,
            ann.record_date.0
        )));
    }
    if let crate::model::ActionTerms::CashDividend { per_share } = &ann.terms {
        if per_share.0 < 0 {
            return Err(EngineError::InvalidInput(format!(
                "event {} negative dividend per share",
                ann.event_id
            )));
        }
    }
    if let crate::model::ActionTerms::Split { ratio, .. }
    | crate::model::ActionTerms::RightsIssue { ratio, .. }
    | crate::model::ActionTerms::Merger { ratio, .. } = &ann.terms
    {
        crate::actions::validate_ratio(*ratio)?;
    }
    Ok(())
}

/// Exact (pre-truncation) fractional cash in nanounits for a long eligible
/// position: price * (eligible*num mod den) / den.
fn exact_frac_nano(terms: &crate::model::ActionTerms, eligible: i64) -> i128 {
    use crate::model::ActionTerms;
    let (ratio, price) = match terms {
        ActionTerms::CashDividend { .. } => return 0,
        ActionTerms::Split {
            ratio, frac_price
        }
        | ActionTerms::Merger {
            ratio, frac_price, ..
        } => (*ratio, *frac_price),
        ActionTerms::RightsIssue {
            ratio, frac_price, ..
        } => (*ratio, *frac_price),
    };
    let num = i128::from(eligible) * i128::from(ratio.num);
    let rem = num % i128::from(ratio.den);
    i128::from(price.0) * rem / i128::from(ratio.den)
}

#[cfg(test)]
    mod tests {
        use super::*;
        use crate::clock::FixedClock;
        use crate::fixed::Amount;
    use crate::model::*;

    const N: i64 = Amount::SCALE as i64;

    fn pos(id: &str, acct: &str, day: i64, delta: i64) -> PositionTx {
        PositionTx {
            id: id.to_string(),
            account: acct.to_string(),
            instrument: "AAA".to_string(),
            time: Stamp {
                date: Date(day),
                seq: 0,
            },
            delta,
        }
    }

    fn dividend(event: &str, rev: u32, parent: Option<u32>, per_share: i64) -> Announcement {
        Announcement {
            event_id: event.to_string(),
            revision: rev,
            parent_revision: parent,
            kind: ActionKind::CashDividend,
            instrument: "AAA".to_string(),
            record_date: Date(10),
            ex_date: Date(11),
            pay_date: Date(15),
            announced_at: Stamp {
                date: Date(5),
                seq: 0,
            },
            terms: ActionTerms::CashDividend {
                per_share: Amount(per_share),
            },
            residual_recipient: None,
        }
    }

    fn engine() -> Engine<FixedClock> {
        Engine::new(FixedClock {
            today: Date(20),
        })
    }

    #[test]
    fn cash_dividend_posts_per_account_entries() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 100)).unwrap();
        e.load_position(pos("p2", "b", 2, -30)).unwrap();
        e.load_position(pos("p3", "a", 12, 50)).unwrap(); // ex之后，不倒灌
        e.publish(dividend("ev1", 1, None, N / 3)).unwrap();
        let mut fwd: Vec<&Entry> =
            e.entries().iter().filter(|x| !x.reversal).collect();
        fwd.sort_by_key(|x| x.account.clone());
        assert_eq!(fwd.len(), 2);
        assert_eq!(fwd[0].account, "a");
        assert_eq!(fwd[0].eligible_shares, 100);
        assert_eq!(fwd[0].cash, Amount(33_333_333_300)); // 100 * (N/3)
        assert_eq!(fwd[1].account, "b");
        assert_eq!(fwd[1].eligible_shares, -30);
        assert_eq!(fwd[1].cash, Amount(-9_999_999_990)); // -30 * (N/3)
        // 纯分红没有碎股，尾差为零
        assert_eq!(fwd[0].rounding_residual, Amount::ZERO);
        assert_eq!(fwd[1].rounding_residual, Amount::ZERO);
        let total: i64 = fwd.iter().map(|x| x.cash.0).sum();
        assert_eq!(total, 33_333_333_300 - 9_999_999_990);
    }

    #[test]
    fn replay_is_idempotent() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 10)).unwrap();
        let ann = dividend("ev1", 1, None, N);
        e.publish(ann.clone()).unwrap();
        e.publish(ann).unwrap();
        assert_eq!(e.entries().len(), 1);
    }

    #[test]
    fn missing_parent_is_deferred_and_posts_nothing() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 10)).unwrap();
        // Parent absent: revision parks in the pending buffer, no entries.
        e.publish(dividend("ev1", 2, Some(1), N)).unwrap();
        assert!(e.entries().is_empty());
        // Once the parent arrives, the parked correction drains and posts.
        e.publish(dividend("ev1", 1, None, N)).unwrap();
        // v1 forward, then v2 drains as a correction: reversal + new forward.
        assert_eq!(e.entries().len(), 3);
    }

    #[test]
    fn rejected_parent_blocks_child_but_posts_nothing_for_it() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 10)).unwrap();
        // Root with impossible dates is rejected and remembered.
        let mut bad = dividend("ev1", 1, None, N);
        bad.ex_date = bad.record_date;
        assert!(e.publish(bad).is_err());
        // Child referencing the rejected parent: MissingParent, zero legs.
        let child = dividend("ev1", 2, Some(1), N);
        assert!(matches!(
            e.publish(child),
            Err(EngineError::MissingParent(_))
        ));
        assert!(e.entries().is_empty());
        // Replaying the bad root is still an error and posts nothing.
        let mut bad = dividend("ev1", 1, None, N);
        bad.ex_date = bad.record_date;
        assert!(e.publish(bad).is_err());
        assert!(e.entries().is_empty());
    }

    #[test]
    fn correction_reverses_parent_then_posts_delta() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 100)).unwrap();
        e.publish(dividend("ev1", 1, None, N)).unwrap();
        e.publish(dividend("ev1", 2, Some(1), 2 * N)).unwrap();
        assert_eq!(e.entries().len(), 3);
        // v1 冲回 + v2 新账：净效果 100*2.0 = 200
        let net: i64 = e
            .entries()
            .iter()
            .filter(|x| x.account == "a")
            .map(|x| x.cash.0)
            .sum();
        assert_eq!(net, 200 * N);
        assert!(e.entries()[0].cash.0 == 100 * N);
        assert!(e.entries()[0].reversal == false);
    }

    #[test]
    fn duplicate_correction_does_not_double_post() {
        let mut e = engine();
        e.load_position(pos("p1", "a", 1, 100)).unwrap();
        e.publish(dividend("ev1", 1, None, N)).unwrap();
        let rev2 = dividend("ev1", 2, Some(1), 2 * N);
        e.publish(rev2.clone()).unwrap();
        e.publish(rev2).unwrap();
        // 3 = v1 forward, v1 reversal, v2 forward; replay adds nothing.
        assert_eq!(e.entries().len(), 3);
    }

    #[test]
    fn bad_date_order_is_rejected() {
        let mut e = engine();
        let mut bad = dividend("ev1", 1, None, N);
        bad.record_date = Date(12);
        bad.ex_date = Date(11);
        assert!(matches!(
            e.publish(bad),
            Err(EngineError::DateOrder(_))
        ));
        assert!(e.entries().is_empty());
    }
}
