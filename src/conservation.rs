//! Exact rational conservation identities, independently recomputed from
//! posted entries. Every identity compares the theoretical pre-action
//! entitlement with what the ledger actually delivered (whole securities,
//! fractional cash and the distributed rounding tail).

use crate::ledger::{Entry, Ledger};
use crate::model::Announcement;
use crate::model::{ActionTerms, Ratio};
use std::collections::BTreeMap;

/// Signed rational with strictly positive denominator, kept in i128 space.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rational {
    pub num: i128,
    pub den: i128,
}

impl Rational {
    pub fn integer(v: i128) -> Self {
        Rational { num: v, den: 1 }
    }

    pub fn new(num: i128, den: i128) -> Self {
        debug_assert!(den > 0);
        Rational { num, den }
    }

    pub fn add(self, other: Rational) -> Rational {
        let raw = Rational {
            num: self.num * other.den + other.num * self.den,
            den: self.den * other.den,
        };
        raw.reduce()
    }

    pub fn sub(self, other: Rational) -> Rational {
        self.add(Rational {
            num: -other.num,
            den: other.den,
        })
    }

    /// Multiply by a nanounit scalar, yielding a value in major units.
    pub fn mul_scalar(self, scalar_nano: i128) -> Rational {
        Rational::new(
            self.num * scalar_nano,
            self.den * i128::from(crate::fixed::Amount::SCALE),
        )
    }

    pub fn is_zero(&self) -> bool {
        self.num == 0
    }

    fn reduce(mut self) -> Rational {
        if self.num == 0 {
            return Rational { num: 0, den: 1 };
        }
        let g = gcd(self.num.unsigned_abs(), self.den.unsigned_abs());
        self.num /= g as i128;
        self.den /= g as i128;
        self
    }

    /// True iff `lhs == rhs` after cross-multiplication. Panics on overflow
    /// of the i128 product (test inputs are far from that range).
    pub fn eq_exact(lhs: Rational, rhs: Rational) -> bool {
        lhs.num
            .checked_mul(rhs.den)
            .zip(rhs.num.checked_mul(lhs.den))
            .map(|(a, b)| a == b)
            .expect("rational comparison overflow")
    }
}

fn gcd(a: u128, b: u128) -> u128 {
    if b == 0 { a } else { gcd(b, a % b) }
}

/// One event's recomputed conservation discrepancy, per identity.
    #[derive(Clone, Debug, PartialEq, Eq)]
pub struct Discrepancy {
    pub event_id: String,
    pub revision: u32,
    /// `lhs - rhs` for each identity; zero iff conservation holds.
    pub security_gap: Rational,
    pub cash_gap: Rational,
}

/// Verify conservation for every forward entry set currently in the ledger.
///
/// Forward legs of a revision describe what was delivered; reversal legs
/// cancel earlier deliveries and are therefore excluded from the identity
/// (a later revision is checked on its own forward legs).
pub fn verify_ledger(
    ledger: &Ledger,
    announcements: &[Announcement],
) -> Vec<Discrepancy> {
    let mut groups: BTreeMap<(String, u32), Vec<&Entry>> = BTreeMap::new();
    for e in &ledger.entries {
        if !e.reversal {
            groups
                .entry((e.event_id.clone(), e.revision))
                .or_default()
                .push(e);
        }
    }
    let mut out = Vec::new();
    for ((event_id, revision), entries) in groups {
        let terms = announcements
            .iter()
            .find(|a| a.event_id == event_id && a.revision == revision)
            .map(|a| &a.terms);
        let Some(terms) = terms else {
            continue;
        };
        let d = verify_event(&event_id, revision, &terms, &entries);
        if !Rational::eq_exact(d.security_gap, Rational::integer(0))
            || !Rational::eq_exact(d.cash_gap, Rational::integer(0))
        {
            out.push(d);
        }
    }
    out
}

/// Check one revision. Returns zero gaps iff all identities hold exactly.
pub fn verify_event(
    event_id: &str,
    revision: u32,
    terms: &ActionTerms,
    entries: &[&Entry],
) -> Discrepancy {
    let scale = i128::from(crate::fixed::Amount::SCALE);
    // Start both sides at zero; each action adds its own identity legs.
    let mut lhs_security = Rational::integer(0);
    let mut rhs_security = Rational::integer(0);
    let mut lhs_cash = Rational::integer(0);
    let mut rhs_cash = Rational::integer(0);

    match terms {
        ActionTerms::CashDividend { per_share } => {
            // Sum(q) * per_share == delivered cash, exactly in nanounits.
            // q may be negative for short accounts; signs stay symmetric.
            for e in entries {
                let q = i128::from(e.eligible_shares);
                lhs_cash = lhs_cash.add(Rational::new(
                    q * i128::from(per_share.raw()),
                    scale,
                ));
                rhs_cash = rhs_cash.add(Rational::new(
                    i128::from(e.cash.raw()),
                    scale,
                ));
            }
        }
        ActionTerms::Split {
            ratio,
            frac_price,
        }
        | ActionTerms::Merger {
            ratio,
            frac_price,
            ..
        } => {
            security_identity(ratio, frac_price.raw(), entries, &mut lhs_security, &mut rhs_security);
        }
        ActionTerms::RightsIssue {
            ratio,
            price,
            frac_price,
        } => {
            security_identity(ratio, frac_price.raw(), entries, &mut lhs_security, &mut rhs_security);
            // Cash identity (inflow-positive):
            //   delivered_cash == frac_value - whole_new * price
            // LHS is the exact entitlement; RHS is what the ledger posted.
            for e in entries {
                let calls = i128::from(e.securities);
                let q = i128::from(e.eligible_shares);
                let prod = q * i128::from(ratio.num);
                let whole = crate::fixed::trunc_div(prod, i128::from(ratio.den));
                let rem = prod - whole * i128::from(ratio.den);
                let frac_units = Rational::new(rem, i128::from(ratio.den));
                lhs_cash = lhs_cash.add(Rational::new(
                    -calls * i128::from(price.raw()),
                    scale,
                ));
                lhs_cash = lhs_cash.add(frac_units.mul_scalar(i128::from(
                    frac_price.raw(),
                )));
                rhs_cash =
                    rhs_cash.add(Rational::new(i128::from(e.cash.raw()), scale));
            }
        }
    }

    let security_gap_r = lhs_security.sub(rhs_security);
    let cash_gap_r = lhs_cash.sub(rhs_cash);

    Discrepancy {
        event_id: event_id.to_string(),
        revision,
        security_gap: security_gap_r,
        cash_gap: cash_gap_r,
    }
}

/// Identity for split / rights / merger, measured in NEW-share units:
///
/// sum(q) * num/den  ==  sum(whole_new)
///                    + sum(frac_cash + residual)/frac_price
    fn security_identity(
    ratio: &Ratio,
    frac_price_nano: i64,
    entries: &[&Entry],
    lhs: &mut Rational,
    rhs: &mut Rational,
) {
    // LHS: old shares re-based onto the new-share scale.
    for e in entries {
        let q = i128::from(e.eligible_shares);
        *lhs = lhs.add(Rational::new(
            q * i128::from(ratio.num),
            i128::from(ratio.den),
        ));
    }
    // RHS: whole new shares delivered ...
    for e in entries {
        *rhs = rhs.add(Rational::integer(i128::from(e.securities)));
    }
    // ... plus the cash-in-lieu leg, expressed as fractional new shares:
    // (frac cash + tail residual) / frac_price.
    for e in entries {
        let cash_nano = i128::from(e.fractional_cash.raw())
            + i128::from(e.rounding_residual.raw());
        let as_shares = Rational::new(cash_nano, i128::from(frac_price_nano));
        *rhs = rhs.add(as_shares);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::FixedClock;
    use crate::engine::Engine;
    use crate::fixed::Amount;
    use crate::model::*;

    const N: i64 = Amount::SCALE as i64;

    fn pos(id: &str, acct: &str, inst: &str, delta: i64) -> PositionTx {
        PositionTx {
            id: id.to_string(),
            account: acct.to_string(),
            instrument: inst.to_string(),
            time: Stamp {
                date: Date(1),
                seq: 0,
            },
            delta,
        }
    }

    fn ann(inst: &str, terms: ActionTerms, recipient: Option<&str>) -> Announcement {
        Announcement {
            event_id: format!("EV-{inst}"),
            revision: 1,
            parent_revision: None,
            kind: ActionKind::CashDividend,
            instrument: inst.to_string(),
            record_date: Date(10),
            ex_date: Date(11),
            pay_date: Date(15),
            announced_at: Stamp {
                date: Date(5),
                seq: 0,
            },
            terms,
            residual_recipient: recipient.map(str::to_string),
        }
    }

    fn run(
        instrument: &str,
        holdings: &[(&str, i64)],
        terms: ActionTerms,
        recipient: Option<&str>,
    ) -> Vec<Discrepancy> {
        let mut e = Engine::new(FixedClock {
            today: Date(20),
        });
        for (i, (acct, q)) in holdings.iter().enumerate() {
            e.load_position(pos(&format!("p{i}"), acct, instrument, *q))
                .unwrap();
        }
        let a = ann(instrument, terms.clone(), recipient);
        e.publish(a.clone()).unwrap();
        let rows: Vec<&Entry> = e
            .entries()
            .iter()
            .filter(|x| !x.reversal)
            .collect();
        let d = verify_event(&a.event_id, 1, &a.terms, &rows);
        if d.security_gap.is_zero() && d.cash_gap.is_zero() {
            Vec::new()
        } else {
            vec![d]
        }
    }

    #[test]
    fn dividend_conserves_with_short_and_tail() {
        let d = run(
            "T1",
            &[("a", 100), ("b", -40), ("c", 7)],
            ActionTerms::CashDividend {
                per_share: Amount(N / 3),
            },
            None,
        );
        assert!(d.is_empty(), "dividend discrepancy: {d:?}");
    }

    #[test]
    fn consolidation_fractions_conserve() {
        // 10合1，碎股按 4.0/新股
        let d = run(
            "T2",
            &[("a", 105), ("b", 3), ("c", -105)],
            ActionTerms::Split {
                ratio: Ratio { num: 1, den: 10 },
                frac_price: Amount(4 * N),
            },
            Some("a"),
        );
        assert!(d.is_empty(), "split discrepancy: {d:?}");
    }

    #[test]
    fn rights_issue_conserves_cash_and_shares() {
        let d = run(
            "T3",
            &[("a", 20), ("b", 4)],
            ActionTerms::RightsIssue {
                ratio: Ratio { num: 3, den: 10 },
                price: Amount(2 * N),
                frac_price: Amount(5 * N),
            },
            None,
        );
        assert!(d.is_empty(), "rights discrepancy: {d:?}");
    }

    #[test]
    fn merger_conserves_into_new_instrument() {
        let d = run(
            "T4",
            &[("a", 9), ("b", 7)],
            ActionTerms::Merger {
                ratio: Ratio { num: 1, den: 4 },
                new_instrument: "BBB".to_string(),
                frac_price: Amount(8 * N),
            },
            None,
        );
        assert!(d.is_empty(), "merger discrepancy: {d:?}");
    }
}
