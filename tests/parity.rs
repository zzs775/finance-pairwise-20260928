//! Property-style parity: the incremental engine and the naive full rebuild
//! must produce identical canonical ledgers under random event sequences and
//! under arbitrary input ordering.

use corp_action_engine::clock::FixedClock;
use corp_action_engine::engine::Engine;
use corp_action_engine::model::*;
use corp_action_engine::output;
use corp_action_engine::rebuild::full_rebuild;

struct Rng(u64);

impl Rng {
    fn next_u64(&mut self) -> u64 {
        // Deterministic LCG (Numerical Recipes constants).
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0
    }
    fn below(&mut self, bound: u64) -> u64 {
        self.next_u64() % bound
    }
}

fn stamp(rng: &mut Rng, day: i64) -> Stamp {
    Stamp {
        date: Date(day),
        seq: rng.below(1000),
    }
}

struct Case {
    positions: Vec<PositionTx>,
    announcements: Vec<Announcement>,
}

fn random_case(seed: u64) -> Case {
    let mut rng = Rng(seed);
    let n = 1_000_000_000i64;
    let accounts = ["a", "b", "c", "d"];
    let instruments = ["I1", "I2", "I3"];

    // Positions: random signed deltas with random ids (some duplicated),
    // spread before and after the record dates.
    let mut positions: Vec<PositionTx> = Vec::new();
    let pos_count = 40 + rng.below(30);
    for i in 0..pos_count {
        let acct = accounts[rng.below(accounts.len() as u64) as usize];
        let inst = instruments[rng.below(instruments.len() as u64) as usize];
        let day = 1 + rng.below(50) as i64;
        let delta = (rng.below(121) as i64) - 60;
        // Occasionally reuse an earlier id -> duplicate, must be ignored once.
        let id = if !positions.is_empty() && rng.below(8) == 0 {
            positions[rng.below(positions.len() as u64) as usize]
                .id
                .clone()
        } else {
            format!("p{i}")
        };
        positions.push(PositionTx {
            id,
            account: acct.to_string(),
            instrument: inst.to_string(),
            time: stamp(&mut rng, day),
            delta,
        });
    }

    // Announcements: one root event per instrument plus a random chain of
    // corrections referencing explicit parents; occasionally an orphan.
    let mut announcements = Vec::new();
    for inst in instruments {
        let event = format!("EV-{inst}");
        let root_share = n / 7 + rng.below(10) as i64;
        announcements.push(head(
            &mut rng,
            inst,
            &event,
            1,
            None,
            root_share,
            5,
        ));
        let chain = rng.below(4);
        for k in 1..=chain {
            let rev = (k + 1) as u32;
            let parent = if rng.below(6) == 0 {
                // Orphan referencing a never-published revision.
                Some(rev + 50)
            } else {
                Some(rev - 1)
            };
            let day = 5 + rng.below(3) as i64;
            let per_share = n / 11 + rng.below(20) as i64;
            announcements.push(head(
                &mut rng,
                inst,
                &event,
                rev,
                parent,
                per_share,
                day,
            ));
        }
        // Sometimes duplicate an already emitted revision (idempotent replay).
        if rng.below(2) == 0 {
            let dup = announcements
                .iter()
                .rev()
                .find(|a| a.event_id == event)
                .unwrap()
                .clone();
            announcements.push(dup);
        }
    }
    Case {
        positions,
        announcements,
    }
}

#[allow(clippy::too_many_arguments)]
fn head(
    rng: &mut Rng,
    inst: &str,
    event: &str,
    rev: u32,
    parent: Option<u32>,
    per_share: i64,
    day: i64,
) -> Announcement {
    Announcement {
        event_id: event.to_string(),
        revision: rev,
        parent_revision: parent,
        kind: ActionKind::CashDividend,
        instrument: inst.to_string(),
        record_date: Date(30),
        ex_date: Date(31),
        pay_date: Date(35),
        announced_at: Stamp {
            date: Date(day),
            seq: rng.below(1000),
        },
        terms: ActionTerms::CashDividend {
            per_share: corp_action_engine::fixed::Amount(per_share),
        },
        residual_recipient: None,
    }
}

#[test]
fn incremental_matches_naive_rebuild_across_random_cases() {
    for seed in 1..=80 {
        let case = random_case(seed);
        let clock = FixedClock {
            today: Date(100),
        };

        // Incremental path: feed in the random arrival order, treating
        // broken-chain and duplicate publishes the way a live journal does.
        let mut engine = Engine::new(FixedClock {
            today: Date(100),
        });
        for p in &case.positions {
            let _ = engine.load_position(p.clone());
        }
        for a in &case.announcements {
            let _ = engine.publish(a.clone());
        }
        let incremental = output::render(&engine.ledger);

        // Naive path: full rebuild from the same raw inputs.
        let rebuilt =
            full_rebuild(&clock, &case.positions, &case.announcements).unwrap();
        let naive = output::render(&rebuilt);

        assert_eq!(
            incremental, naive,
            "seed {seed}: incremental/naive mismatch"
        );
    }
}

#[test]
fn output_is_invariant_under_input_permutation() {
    let case = random_case(1234);
    let clock = FixedClock {
        today: Date(100),
    };
    let reference = {
        let rebuilt =
            full_rebuild(&clock, &case.positions, &case.announcements).unwrap();
        output::render(&rebuilt)
    };

    // Deterministically permute both input lists.
    let mut positions = case.positions.clone();
    let mut announcements = case.announcements.clone();
    permute(&mut positions, 99);
    permute(&mut announcements, 7);

    let rebuilt = full_rebuild(&clock, &positions, &announcements).unwrap();
    assert_eq!(reference, output::render(&rebuilt));
}

fn permute<T>(items: &mut [T], seed: u64) {
    let mut rng = Rng(seed);
    for i in (1..items.len()).rev() {
        let j = rng.below(i as u64 + 1) as usize;
        items.swap(i, j);
    }
}

#[test]
fn residual_recipient_is_stable_when_accounts_are_renamed_order() {
    // Same holdings, accounts arrive in different orders; canonical output
    // and the residual tail recipient stay fixed.
    let make = || {
        Engine::new(FixedClock {
            today: Date(20),
        })
    };
    // Consolidation 10->1, frac price 4.0: 3 shares => 0 whole + 1.2 cash,
    // truncation tail lands on the lexicographically first long account.
    let terms = ActionTerms::Split {
        ratio: Ratio { num: 1, den: 10 },
        frac_price: corp_action_engine::fixed::Amount(4 * 1_000_000_000),
    };
    let mk_pos = |id: &str, acct: &str| PositionTx {
        id: id.into(),
        account: acct.into(),
        instrument: "AAA".into(),
        time: Stamp {
            date: Date(1),
            seq: 0,
        },
        delta: 3,
    };
    let mk_ann = || Announcement {
        event_id: "E".into(),
        revision: 1,
        parent_revision: None,
        kind: ActionKind::Split,
        instrument: "AAA".into(),
        record_date: Date(10),
        ex_date: Date(11),
        pay_date: Date(15),
        announced_at: Stamp {
            date: Date(5),
            seq: 0,
        },
        terms: terms.clone(),
        residual_recipient: None,
    };

    let run = |order: Vec<(&str, &str)>| {
        let mut e = make();
        for (id, acct) in order {
            e.load_position(mk_pos(id, acct)).unwrap();
        }
        e.publish(mk_ann()).unwrap();
        output::render(&e.ledger)
    };

    let first = run(vec![("p1", "zoe"), ("p2", "amy")]);
    let second = run(vec![("p1", "amy"), ("p2", "zoe")]);
    assert_eq!(first, second);
    // Tail recipient is amy regardless of arrival order.
    assert!(first.contains("FORWARD|amy|"));
    let amy_line = first
        .lines()
        .find(|l| l.contains("FORWARD|amy|"))
        .unwrap();
    assert!(amy_line.ends_with("|0")); // whole shares zero
}
