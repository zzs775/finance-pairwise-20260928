//! Minimal line-based text input parser.

use crate::error::Result;
use crate::error::EngineError;
use crate::fixed::Amount;
use crate::model::{
    ActionKind, ActionTerms, Announcement, Date, PositionTx, Ratio, Stamp,
};

pub struct InputFile {
    pub positions: Vec<PositionTx>,
    pub announcements: Vec<Announcement>,
}

/// Line grammar (one record per line, `#` starts a comment):
///
/// ```text
/// POS  <id>|<account>|<instrument>|<date>|<seq>|<delta>
/// DIV  <event>|<rev>|<parent|->|<instrument>|<record>|<ex>|<pay>|<announced:date>|<seq>|<per_share_nano>
/// SPL  <same head>|num|den|frac_price_nano
/// RTS  <same head>|num|den|price_nano|frac_price_nano
/// MRG  <same head>|num|den|new_instrument|frac_price_nano
/// RES  <event>|<recipient account|->
/// ```
pub fn parse_input(text: &str) -> Result<InputFile> {
    let mut positions = Vec::new();
    let mut announcements = Vec::new();
    let mut recipients: Vec<(String, Option<String>)> = Vec::new();
    for (idx, raw_line) in text.lines().enumerate() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let at = || format!("line {}: {}", idx + 1, line);
        let (tag, rest) = line
            .split_once(|c: char| c.is_whitespace())
            .ok_or_else(|| EngineError::InvalidInput(at()))?;
        let fields: Vec<&str> = rest.split('|').map(str::trim).collect();
        match tag {
            "POS" => positions.push(parse_pos(&fields).map_err(
                |e: EngineError| EngineError::InvalidInput(format!("{}: {}", at(), e)),
            )?),
            "DIV" | "SPL" | "RTS" | "MRG" => announcements.push(parse_action(
                tag, &fields,
            )
            .map_err(|e| EngineError::InvalidInput(format!("{}: {}", at(), e)))?),
            "RES" => {
                expect_fields(&fields, 2, &at)?;
                let recipient = if fields[1] == "-" {
                    None
                } else {
                    Some(fields[1].to_string())
                };
                recipients.push((fields[0].to_string(), recipient));
            }
            other => {
                return Err(EngineError::InvalidInput(format!(
                    "{}: unknown record tag {}",
                    at(),
                    other
                )))
            }
        }
    }
    for (event_id, recipient) in recipients {
        let ann = announcements
            .iter_mut()
            .find(|a| a.event_id == event_id && a.revision == 1)
            .ok_or_else(|| {
                EngineError::InvalidInput(format!(
                    "RES references unknown root event {}",
                    event_id
                ))
            })?;
        ann.residual_recipient = recipient;
    }
    Ok(InputFile {
        positions,
        announcements,
    })
}

fn expect_fields(
    fields: &[&str],
    n: usize,
    at: &dyn Fn() -> String,
) -> Result<()> {
    if fields.len() != n {
        return Err(EngineError::InvalidInput(format!(
            "{}: expected {} fields, got {}",
            at(),
            n,
            fields.len()
        )));
    }
    Ok(())
}

fn parse_i64(s: &str, what: &str) -> Result<i64> {
    s.parse::<i64>()
        .map_err(|_| EngineError::InvalidInput(format!("bad {}: {}", what, s)))
}

fn parse_u64(s: &str, what: &str) -> Result<u64> {
    s.parse::<u64>()
        .map_err(|_| EngineError::InvalidInput(format!("bad {}: {}", what, s)))
}

fn parse_pos(fields: &[&str]) -> Result<PositionTx> {
    if fields.len() != 6 {
        return Err(EngineError::InvalidInput(format!(
            "POS expected 6 fields, got {}",
            fields.len()
        )));
    }
    Ok(PositionTx {
        id: fields[0].to_string(),
        account: fields[1].to_string(),
        instrument: fields[2].to_string(),
        time: Stamp {
            date: Date(parse_i64(fields[3], "date")?),
            seq: parse_u64(fields[4], "seq")?,
        },
        delta: parse_i64(fields[5], "delta")?,
    })
}

/// Common head:
/// event|rev|parent|instrument|record|ex|pay|ann_date|ann_seq
fn parse_action(tag: &str, fields: &[&str]) -> Result<Announcement> {
    let min = 9;
    if fields.len() < min {
        return Err(EngineError::InvalidInput(format!(
            "{} expected at least {} fields, got {}",
            tag,
            min,
            fields.len()
        )));
    }
    let kind = match tag {
        "DIV" => ActionKind::CashDividend,
        "SPL" => ActionKind::Split,
        "RTS" => ActionKind::RightsIssue,
        "MRG" => ActionKind::Merger,
        _ => unreachable!(),
    };
    let parent = if fields[2] == "-" {
        None
    } else {
    Some(parse_u32(fields[2], "parent")?)
    };
    let head = Announcement {
        event_id: fields[0].to_string(),
        revision: parse_u32(fields[1], "revision")?,
        parent_revision: parent,
        kind,
        instrument: fields[3].to_string(),
        record_date: Date(parse_i64(fields[4], "record")?),
        ex_date: Date(parse_i64(fields[5], "ex")?),
        pay_date: Date(parse_i64(fields[6], "pay")?),
        announced_at: Stamp {
            date: Date(parse_i64(fields[7], "announced date")?),
            seq: parse_u64(fields[8], "announced seq")?,
        },
        terms: ActionTerms::CashDividend {
            per_share: Amount::ZERO,
        },
        residual_recipient: None,
    };
    let tail = &fields[9..];
    let terms = match tag {
        "DIV" => {
            if tail.len() != 1 {
                return Err(EngineError::InvalidInput(
                    "DIV expects per_share_nano".to_string(),
                ));
            }
            ActionTerms::CashDividend {
                per_share: Amount(parse_i64(tail[0], "per_share_nano")?),
            }
        }
        "SPL" => {
            if tail.len() != 3 {
                return Err(EngineError::InvalidInput(
                    "SPL expects num|den|frac_price_nano".to_string(),
                ));
            }
            ActionTerms::Split {
                ratio: Ratio {
                    num: parse_i64(tail[0], "num")?,
                    den: parse_i64(tail[1], "den")?,
                },
                frac_price: Amount(parse_i64(tail[2], "frac_price")?),
            }
        }
        "RTS" => {
            if tail.len() != 4 {
                return Err(EngineError::InvalidInput(
                    "RTS expects num|den|price_nano|frac_price_nano".to_string(),
                ));
            }
            ActionTerms::RightsIssue {
                ratio: Ratio {
                    num: parse_i64(tail[0], "num")?,
                    den: parse_i64(tail[1], "den")?,
                },
                price: Amount(parse_i64(tail[2], "price")?),
                frac_price: Amount(parse_i64(tail[3], "frac_price")?),
            }
        }
        "MRG" => {
            if tail.len() != 4 {
                return Err(EngineError::InvalidInput(
                    "MRG expects num|den|new_instrument|frac_price_nano".to_string(),
                ));
            }
            ActionTerms::Merger {
                ratio: Ratio {
                    num: parse_i64(tail[0], "num")?,
                    den: parse_i64(tail[1], "den")?,
                },
                new_instrument: tail[2].to_string(),
                frac_price: Amount(parse_i64(tail[3], "frac_price")?),
            }
        }
        _ => unreachable!(),
    };
    Ok(Announcement { terms, ..head })
}

fn parse_u32(s: &str, what: &str) -> Result<u32> {
    s.parse::<u32>()
        .map_err(|_| EngineError::InvalidInput(format!("bad {}: {}", what, s)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_all_tags() {
        let text = "\
# demo
POS p1|alice|AAA|1|0|105
DIV d1|1|-|AAA|10|11|15|5|0|333333333
SPL s1|1|-|AAA|20|21|25|5|0|1|10|4000000000
RTS r1|1|-|AAA|30|31|35|5|0|3|10|2000000000|5000000000
MRG m1|1|-|AAA|40|41|45|5|0|1|4|BBB|8000000000
RES d1|alice
";
        let f = parse_input(text).unwrap();
        assert_eq!(f.positions.len(), 1);
        assert_eq!(f.announcements.len(), 4);
        assert_eq!(
            f.announcements[0].residual_recipient,
            Some("alice".to_string())
        );
    }

    #[test]
    fn malformed_lines_are_invalid_input() {
        assert!(matches!(
            parse_input("NOPE x"),
            Err(EngineError::InvalidInput(_))
        ));
        assert!(matches!(
            parse_input("POS p1|a|AAA|x|0|1"),
            Err(EngineError::InvalidInput(_))
        ));
        assert!(matches!(
            parse_input("RES evx|-"),
            Err(EngineError::InvalidInput(_))
        ));
    }
}
