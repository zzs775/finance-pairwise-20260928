//! Naive full-rebuild engine: recompute everything from raw inputs.

use crate::clock::Clock;
use crate::engine::{validate_announcement, Engine};
use crate::error::{EngineError, Result};
use crate::ledger::Ledger;
use crate::model::{Announcement, PositionTx};
use std::collections::{BTreeMap, BTreeSet};

/// Recompute the whole ledger from raw inputs.
///
/// This is the deliberately naive counterpart of the incremental engine: it
/// sorts everything into a canonical order, drops duplicate ids/revisions,
/// skips revisions whose parent chain cannot be resolved, then replays the
/// survivors through the same posting code path. Parity is property-tested
/// against `Engine` under random input sequences.
pub fn full_rebuild<C: Clock>(
    clock: &C,
    positions: &[PositionTx],
    announcements: &[Announcement],
) -> Result<Ledger> {
    let mut engine: Engine<&C> = Engine::new(clock);

    // Positions: first occurrence of each id wins (matching incremental
    // duplicate rejection semantics), then canonical business-time order.
    let mut seen_pos: BTreeSet<String> = BTreeSet::new();
    let mut ordered: Vec<&PositionTx> = positions
        .iter()
        .filter(|t| seen_pos.insert(t.id.clone()))
        .collect();
    ordered.sort_by_key(|t| (t.time, t.id.clone()));
    for t in ordered {
        engine.load_position(t.clone())?;
    }

    // Announcements: first occurrence of (event, revision) wins.
    let mut seen_rev: BTreeSet<(String, u32)> = BTreeSet::new();
    let mut unique: Vec<&Announcement> = announcements
        .iter()
        .filter(|a| seen_rev.insert((a.event_id.clone(), a.revision)))
        .collect();
    unique.sort_by_key(|a| {
        (
            a.announced_at,
            a.event_id.clone(),
            a.revision,
        )
    });

    // Resolve chains: a revision posts only if its parent is present and
    // itself resolvable; root revisions must be revision 1.
    let parents: BTreeMap<(String, u32), Option<u32>> = unique
        .iter()
        .map(|a| ((a.event_id.clone(), a.revision), a.parent_revision))
        .collect();
    let mut resolvable: BTreeSet<(String, u32)> = BTreeSet::new();
    let mut progress = true;
    while progress {
        progress = false;
        for a in &unique {
            let key = (a.event_id.clone(), a.revision);
            if resolvable.contains(&key) {
                continue;
            }
            let ok = match a.parent_revision {
                None => a.revision == 1,
                Some(p) => {
                    p < a.revision
                        && parents.contains_key(&(a.event_id.clone(), p))
                        && resolvable.contains(&(a.event_id.clone(), p))
                }
            };
            if ok {
                resolvable.insert(key);
                progress = true;
            }
        }
    }

    for a in unique {
        let key = (a.event_id.clone(), a.revision);
        if !resolvable.contains(&key) {
            // Orphan / broken chain: nothing is posted for it.
            continue;
        }
        // Validate exactly like the incremental path; an invalid but
        // chain-connected announcement fails the whole rebuild.
        validate_announcement(a)?;
        if let Err(EngineError::ArithmeticOverflow(_)) = engine.publish(a.clone()) {
            return Err(EngineError::ArithmeticOverflow(
                "rebuild arithmetic overflow".to_string(),
            ));
        }
    }
    Ok(Ledger::from_entries(engine.entries().to_vec()))
}
