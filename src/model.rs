//! Core input data model: business time, positions, announcements.

use crate::fixed::Amount;

pub type Seq = u64;

/// Days since an arbitrary epoch; only ordering matters.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Hash)]
pub struct Date(pub i64);

/// Business time: effective date plus a tie-breaking receive sequence number.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Hash)]
pub struct Stamp {
    pub date: Date,
    pub seq: Seq,
}

/// A signed position delta (negative = short / reduction).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PositionTx {
    pub id: String,
    pub account: String,
    pub instrument: String,
    pub time: Stamp,
    pub delta: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ActionKind {
    CashDividend,
    Split,
    RightsIssue,
    Merger,
}

/// Positive rational ratio, denominator strictly positive.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ratio {
    pub num: i64,
    pub den: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ActionTerms {
    /// Cash per eligible share, in nanounits.
    CashDividend { per_share: Amount },
    /// Split (ratio > 1) or consolidation (ratio < 1). Fractional new
    /// shares are cashed out at `frac_price` per new share.
    Split {
        ratio: Ratio,
        frac_price: Amount,
    },
    /// Pro-rata subscription of new shares at `price`; the fractional
    /// entitlement is cashed out at `frac_price` instead of subscribed.
    RightsIssue {
        ratio: Ratio,
        price: Amount,
        frac_price: Amount,
    },
    /// Exchange into `new_instrument`; fractions cashed at `frac_price`.
    Merger {
        ratio: Ratio,
        new_instrument: String,
        frac_price: Amount,
    },
}

/// A specific published revision of a corporate action announcement.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Announcement {
    pub event_id: String,
    pub revision: u32,
    /// Explicit parent revision. `None` for the first version; later
    /// revisions must point at an existing posted revision.
    pub parent_revision: Option<u32>,
    pub kind: ActionKind,
    pub instrument: String,
    pub record_date: Date,
    pub ex_date: Date,
    pub pay_date: Date,
    pub announced_at: Stamp,
    pub terms: ActionTerms,
    /// Account that receives the deterministic cash rounding tail.
    /// `None` => first eligible account in canonical order.
    pub residual_recipient: Option<String>,
}
