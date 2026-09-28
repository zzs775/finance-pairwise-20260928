//! Position timeline ordered by business time and receive sequence.

use crate::error::Result;
use crate::error::EngineError;
use crate::model::{Date, PositionTx, Seq, Stamp};

pub struct PositionTimeline {
    pub txs: Vec<PositionTx>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tx(id: &str, account: &str, day: i64, seq: u64, delta: i64) -> PositionTx {
        PositionTx {
            id: id.to_string(),
            account: account.to_string(),
            instrument: "AAA".to_string(),
            time: Stamp {
                date: Date(day),
                seq,
            },
            delta,
        }
    }

    #[test]
    fn duplicate_tx_id_rejected() {
        let mut tl = PositionTimeline::new();
        tl.push(tx("t1", "a", 1, 0, 10)).unwrap();
        assert!(matches!(
            tl.push(tx("t1", "b", 2, 0, 5)),
            Err(EngineError::DuplicateEventId(_))
        ));
    }

    #[test]
    fn frozen_snapshot_ignores_post_record_trades() {
        let mut tl = PositionTimeline::new();
        tl.push(tx("t1", "a", 1, 0, 100)).unwrap();
        tl.push(tx("t2", "a", 5, 0, -30)).unwrap(); // ex/record之后
        tl.push(tx("t3", "b", 3, 2, -20)).unwrap(); // 负头寸
        tl.push(tx("t4", "b", 3, 1, 60)).unwrap();
        let snap = tl.frozen_snapshot("AAA", Date(3)).unwrap();
        // 账号按字典序规范输出：a=100, b=40（-20+60，序号不影响净和）
        assert_eq!(snap, vec![
            ("a".to_string(), 100),
            ("b".to_string(), 40),
        ]);
    }

    #[test]
    fn negative_short_positions_are_retained() {
        let mut tl = PositionTimeline::new();
        tl.push(tx("t1", "sh", 1, 0, -50)).unwrap();
        let snap = tl.frozen_snapshot("AAA", Date(2)).unwrap();
        assert_eq!(snap, vec![("sh".to_string(), -50)]);
    }
}

impl Default for PositionTimeline {
    fn default() -> Self {
        Self::new()
    }
}

impl PositionTimeline {
    pub fn new() -> Self {
        PositionTimeline { txs: Vec::new() }
    }

    pub fn push(&mut self, _tx: PositionTx) -> Result<()> {
        if self.txs.iter().any(|t| t.id == _tx.id) {
            return Err(EngineError::DuplicateEventId(_tx.id));
        }
        self.txs.push(_tx);
        Ok(())
    }

    /// Frozen snapshot: net shares per account at the record date.
    ///
    /// Only transactions with `time <= (record, u64::MAX)` contribute, so a
    /// trade settling after the record date (including every post-ex trade)
    /// can never flow backwards into the entitlement. Within the cutoff ties
    /// are broken by the receive sequence number, matching the ordering the
    /// timeline itself is canonicalised under.
    pub fn frozen_snapshot(
        &self,
        instrument: &str,
        record: Date,
    ) -> Result<Vec<(String, i64)>> {
        let cutoff = Stamp {
            date: record,
            seq: Seq::MAX,
        };
        let mut accounts: Vec<String> = Vec::new();
        let mut quantities: Vec<i64> = Vec::new();
        let mut ordered: Vec<&PositionTx> = self
            .txs
            .iter()
            .filter(|t| t.instrument == instrument && t.time <= cutoff)
            .collect();
        ordered.sort_by_key(|t| t.time);
        for tx in ordered {
            let idx = match accounts.iter().position(|a| a == &tx.account) {
                Some(i) => i,
                None => {
                    accounts.push(tx.account.clone());
                    quantities.push(0);
                    accounts.len() - 1
                }
            };
            // Arithmetic overflow in netting is categorised at this boundary.
            quantities[idx] = quantities[idx]
                .checked_add(tx.delta)
                .ok_or_else(|| {
                    EngineError::ArithmeticOverflow(format!(
                        "position accumulation overflow for account {}",
                        tx.account
                    ))
                })?;
        }
        let mut rows: Vec<(String, i64)> =
            accounts.into_iter().zip(quantities).collect();
        // Canonical, input-order-independent ordering of account rows.
        rows.sort_by(|a, b| a.0.cmp(&b.0));
        Ok(rows)
    }
}
