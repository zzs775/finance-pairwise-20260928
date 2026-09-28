//! Local synthetic position/action dataset for demos and tests.

use crate::model::Announcement;
use crate::fixed::Amount;
use crate::model::{
    ActionKind, ActionTerms, Date, PositionTx, Ratio, Stamp,
};

pub struct Dataset {
    pub positions: Vec<PositionTx>,
    pub announcements: Vec<Announcement>,
}

pub fn dataset() -> Dataset {
    let n = Amount::SCALE as i64;
    let stamp = |day: i64, seq: u64| Stamp {
        date: Date(day),
        seq,
    };
    let pos = |id: &str, acct: &str, inst: &str, day: i64, seq: u64, delta: i64| {
        PositionTx {
        id: id.to_string(),
        account: acct.to_string(),
        instrument: inst.to_string(),
        time: stamp(day, seq),
        delta,
        }
    };
    let positions = vec![
        // 现金分红标的 T1
        pos("p1", "alice", "T1", 1, 0, 100),
        pos("p2", "bob", "T1", 2, 1, -40),
        pos("p3", "carol", "T1", 2, 0, 7),
        pos("p4", "alice", "T1", 12, 0, 500), // ex(11) 之后，不倒灌
        // 拆并股标的 T2（10合1，碎股）
        pos("p5", "alice", "T2", 1, 0, 105),
        pos("p6", "bob", "T2", 1, 1, 3),
        // 配股标的 T3（10配3）
        pos("p7", "alice", "T3", 1, 0, 25),
        // 合并换股标的 T4（4换1 BBB）
        pos("p8", "bob", "T4", 1, 0, 9),
    ];

    let announcements = vec![
        // 1) 分红：v1 每股 0.5，随后更正为 0.6
        ann(
            "DIV1", 1, None, ActionKind::CashDividend, "T1", 10, 11, 15, 5, 0,
            ActionTerms::CashDividend {
                per_share: Amount(n / 2),
            },
            None,
        ),
        ann(
            "DIV1", 2, Some(1), ActionKind::CashDividend, "T1", 10, 11, 15, 6, 0,
            ActionTerms::CashDividend {
                per_share: Amount(6 * n / 10),
            },
            None,
        ),
        // 2) 10合1，碎股按 4.0/新股 转现金
        ann(
            "MRG-SHARE", 1, None, ActionKind::Split, "T2", 20, 21, 25, 5, 0,
            ActionTerms::Split {
                ratio: Ratio { num: 1, den: 10 },
                frac_price: Amount(4 * n),
            },
            None,
        ),
        // 3) 10配3，配股价 2.0，零股价 5.0
        ann(
            "RIGHTS", 1, None, ActionKind::RightsIssue, "T3", 30, 31, 35, 5, 0,
            ActionTerms::RightsIssue {
                ratio: Ratio { num: 3, den: 10 },
                price: Amount(2 * n),
                frac_price: Amount(5 * n),
            },
            Some("alice".to_string()),
        ),
        // 4) 4换1 BBB，碎股按 8.0 转现金
        ann(
            "MERGER", 1, None, ActionKind::Merger, "T4", 40, 41, 45, 5, 0,
            ActionTerms::Merger {
                ratio: Ratio { num: 1, den: 4 },
                new_instrument: "BBB".to_string(),
                frac_price: Amount(8 * n),
            },
            None,
        ),
    ];

    Dataset {
        positions,
        announcements,
    }
}

#[allow(clippy::too_many_arguments)]
fn ann(
    event_id: &str,
    revision: u32,
    parent: Option<u32>,
    kind: ActionKind,
    instrument: &str,
    record: i64,
    ex: i64,
    pay: i64,
    ann_day: i64,
    ann_seq: u64,
    terms: ActionTerms,
    residual_recipient: Option<String>,
) -> Announcement {
    Announcement {
        event_id: event_id.to_string(),
        revision,
        parent_revision: parent,
        kind,
        instrument: instrument.to_string(),
        record_date: Date(record),
        ex_date: Date(ex),
        pay_date: Date(pay),
        announced_at: Stamp {
            date: Date(ann_day),
            seq: ann_seq,
        },
        terms,
        residual_recipient,
    }
}
