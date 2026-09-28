use corp_action_engine::clock::FixedClock;
use corp_action_engine::conservation;
use corp_action_engine::engine::Engine;
use corp_action_engine::error::EngineError;
use corp_action_engine::input::parse_input;
use corp_action_engine::model::{Announcement, Date, PositionTx};
use corp_action_engine::output;
use corp_action_engine::rebuild::full_rebuild;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(text) => {
            println!("{text}");
            ExitCode::SUCCESS
        }
        Err(err) => {
            eprintln!("error: {err}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<String, EngineError> {
    let (positions, announcements, today) = match args.first().map(String::as_str) {
        None | Some("demo") => {
            let ds = corp_action_engine::synthetic::dataset();
            (ds.positions, ds.announcements, 60)
        }
        Some("run") => {
            let path = args
                .get(1)
                .ok_or_else(|| {
                    EngineError::InvalidInput("usage: run <input-file> [today]".into())
                })?;
            let text = std::fs::read_to_string(path).map_err(|io| {
                EngineError::InvalidInput(format!("cannot read {path}: {io}"))
            })?;
            let f = parse_input(&text)?;
            let today = args
                .get(2)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(60);
            (f.positions, f.announcements, today)
        }
        Some(other) => {
            return Err(EngineError::InvalidInput(format!(
                "unknown subcommand '{other}' (expected 'demo' or 'run')"
            )))
        }
    };

    let clock = FixedClock {
        today: Date(today),
    };
    let report = process(&clock, &positions, &announcements)?;
    Ok(report)
}

fn process(
    clock: &FixedClock,
    positions: &[PositionTx],
    announcements: &[Announcement],
) -> Result<String, EngineError> {
    // Incremental/indexed path.
    let mut engine = Engine::new(clock);
    for p in positions {
        engine.load_position(p.clone())?;
    }
    for a in announcements {
        engine.publish(a.clone())?;
    }
    let incremental = output::render(&engine.ledger);

    // Naive full-rebuild path.
    let rebuilt = full_rebuild(clock, positions, announcements)?;
    let naive = output::render(&rebuilt);

    let mut report = String::new();
    if incremental == naive {
        report.push_str("# incremental == naive full-rebuild: OK\n");
    } else {
        report.push_str("# MISMATCH between incremental and naive rebuild\n");
    }
    report.push_str(&incremental);
    report.push('\n');

    let discrepancies =
        conservation::verify_ledger(&engine.ledger, announcements);
    if discrepancies.is_empty() {
        report.push_str("# conservation identities: OK\n");
    } else {
        report.push_str(&format!(
            "# conservation violations: {}\n",
            discrepancies.len()
        ));
        for d in &discrepancies {
            report.push_str(&format!(
                "# - {} rev{} gap security={}/{} cash={}/{}\n",
                d.event_id,
                d.revision,
                d.security_gap.num,
                d.security_gap.den,
                d.cash_gap.num,
                d.cash_gap.den
            ));
        }
    }
    Ok(report)
}
