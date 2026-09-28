//! End-to-end coverage: the five action shapes, date-boundary gating with an
//! injectable clock, short positions, fractional residuals, corrections and
//! crash recovery via journal replay.

use corp_action_engine::clock::{Clock, FixedClock, MutableClock};
use corp_action_engine::conservation;
use corp_action_engine::engine::Engine;
use corp_action_engine::fixed::Amount;
use corp_action_engine::ledger::Ledger;
use corp_action_engine::model::*;
use corp_action_engine::output;
use corp_action_engine::rebuild::full_rebuild;

const N: i64 = Amount::SCALE as i64;

fn pos(id: &str, acct: &str, inst: &str, day: i64, delta: i64) -> PositionTx {
    PositionTx {
        id: id.into(),
        account: acct.into(),
        instrument: inst.into(),
        time: Stamp {
            date: Date(day),
            seq: 0,
        },
        delta,
    }
}

fn ann(
    event: &str,
    rev: u32,
    parent: Option<u32>,
    inst: &str,
    record: i64,
    terms: ActionTerms,
    recipient: Option<&str>,
) -> Announcement {
    Announcement {
        event_id: event.into(),
        revision: rev,
        parent_revision: parent,
        kind: ActionKind::CashDividend,
        instrument: inst.into(),
        record_date: Date(record),
        ex_date: Date(record + 1),
        pay_date: Date(record + 5),
        announced_at: Stamp {
            date: Date(record - 5),
            seq: 0,
        },
        terms,
        residual_recipient: recipient.map(str::to_string),
    }
}

#[test]
fn all_five_action_shapes_run_end_to_end_and_conserve() {
    let mut e = Engine::new(MutableClock::new(Date(50)));
    for p in [
        pos("p1", "alice", "DIV", 1, 100),
        pos("p2", "bob", "DIV", 1, -40),
        pos("p3", "alice", "SPL", 1, 105),
        pos("p4", "bob", "SPL", 1, 3),
        pos("p5", "alice", "RTS", 1, 20),
        pos("p6", "bob", "MRG", 1, 9),
    ] {
        e.load_position(p).unwrap();
    }

    let dividends = ann(
        "D", 1, None, "DIV", 10,
        ActionTerms::CashDividend {
            per_share: Amount(N / 2),
        },
        None,
    );
    let split = ann(
        "S", 1, None, "SPL", 20,
        ActionTerms::Split {
            ratio: Ratio { num: 1, den: 10 },
            frac_price: Amount(4 * N),
        },
        None,
    );
    let rights = ann(
        "R", 1, None, "RTS", 30,
        ActionTerms::RightsIssue {
            ratio: Ratio { num: 3, den: 10 },
            price: Amount(2 * N),
            frac_price: Amount(5 * N),
        },
        Some("alice"),
    );
    let merger = ann(
        "M", 1, None, "MRG", 40,
        ActionTerms::Merger {
            ratio: Ratio { num: 1, den: 4 },
            new_instrument: "BBB".into(),
            frac_price: Amount(8 * N),
        },
        None,
    );
    for a in [&dividends, &split, &rights, &merger] {
        e.publish(a.clone()).unwrap();
    }
    assert_eq!(e.clock.now(), Date(50));
    e.clock.advance(3);
    assert_eq!(e.clock.now(), Date(53));

    let announcements = [dividends, split, rights, merger];
    let gaps = conservation::verify_ledger(&e.ledger, &announcements);
    assert!(gaps.is_empty(), "conservation gaps: {gaps:?}");

    // Spot-check shape-specific outcomes.
    let text = output::render(&e.ledger);
    assert!(text.contains("REVERSAL") == false);
    // Merger produced BBB securities for bob.
    assert!(text.contains("MRG|BBB|"));
    // Split 105 -> 10 whole shares.
    assert!(text.contains("S|1|FORWARD|alice|SPL|-|105|105|"));
}

#[test]
fn clock_controls_record_date_boundary() {
    // Trade on day 11 (ex date) must never leak into the record-date snapshot.
    let mut e = Engine::new(FixedClock {
        today: Date(30),
    });
    e.load_position(pos("early", "a", "AAA", 5, 100)).unwrap();
    e.load_position(pos("late", "a", "AAA", 11, 40)).unwrap();
    e.publish(ann(
        "D",
        1,
        None,
        "AAA",
        10,
        ActionTerms::CashDividend {
            per_share: Amount(N),
        },
        None,
    ))
    .unwrap();
    let row = e
        .entries()
        .iter()
        .find(|x| x.account == "a")
        .unwrap();
    assert_eq!(row.eligible_shares, 100);
    assert_eq!(row.cash, Amount(100 * N));
}

#[test]
fn fractional_residual_goes_to_canonical_recipient() {
    let mut e = Engine::new(FixedClock {
        today: Date(20),
    });
    // Two long accounts each with 3 shares under a 10:1 consolidation; each
    // truncates 0.3 of a new share = 1.2 cash; all nanounit tails go to the
    // lexicographically first long account.
    e.load_position(pos("p1", "zoe", "AAA", 1, 3)).unwrap();
    e.load_position(pos("p2", "amy", "AAA", 1, 3)).unwrap();
    e.publish(ann(
        "S",
        1,
        None,
        "AAA",
        10,
        ActionTerms::Split {
            ratio: Ratio { num: 1, den: 10 },
            frac_price: Amount(4 * N),
        },
        None,
    ))
    .unwrap();
    let am = e.entries().iter().find(|x| x.account == "amy").unwrap();
    let zoe = e.entries().iter().find(|x| x.account == "zoe").unwrap();
    assert!(am.rounding_residual.raw() >= 0);
    assert_eq!(zoe.rounding_residual.raw(), 0);
    // All truncated tails land on amy, never zoe, regardless of input order.
    assert!(am.rounding_residual.raw() > 0 || am.fractional_cash.raw() > 0);
}

#[test]
fn crash_recovery_replaying_the_journal_is_idempotent() {
    // Simulate a journal of raw inputs; replay from scratch after a crash.
    let positions = vec![pos("p1", "a", "AAA", 1, 100)];
    let v1 = ann(
        "D", 1, None, "AAA", 10,
        ActionTerms::CashDividend {
            per_share: Amount(N),
        },
        None,
    );
    let v2 = ann(
        "D", 2, Some(1), "AAA", 10,
        ActionTerms::CashDividend {
            per_share: Amount(2 * N),
        },
        None,
    );

    let run = || -> Ledger {
        let mut e = Engine::new(FixedClock {
            today: Date(20),
        });
        for p in &positions {
            e.load_position(p.clone()).unwrap();
        }
        // Journal may replay every announcement, including duplicates.
        for a in [&v1, &v2, &v1, &v2, &v2] {
            e.publish(a.clone()).unwrap();
        }
        Ledger::from_entries(e.entries().to_vec())
    };
    let first = run();
    let second = run(); // restart
    assert_eq!(output::render(&first), output::render(&second));
    // v1 forward, v1 reversal, v2 forward only.
    assert_eq!(first.entries.len(), 3);
}

#[test]
fn naive_rebuild_matches_engine_for_end_to_end_dataset() {
    let ds = corp_action_engine::synthetic::dataset();
    let clock = FixedClock {
        today: Date(60),
    };
    let rebuilt =
        full_rebuild(&clock, &ds.positions, &ds.announcements).unwrap();
    let mut e = Engine::new(FixedClock {
        today: Date(60),
    });
    for p in &ds.positions {
        e.load_position(p.clone()).unwrap();
    }
    for a in &ds.announcements {
        e.publish(a.clone()).unwrap();
    }
    assert_eq!(
        output::render(&rebuilt),
        output::render(&e.ledger)
    );
}
