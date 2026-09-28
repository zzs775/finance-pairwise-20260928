//! CLI 入口。
//!
//! 快照文件每行一条报价（空白分隔）：
//! BASE QUOTE BID ASK MAX_QTY SOURCE_SEQ LOGICAL_TIME
//! `#` 开头为注释，金额一律为 1e-6 尺度的定点整数。

use std::fs;
use std::process::exit;

use fx_arb::{
    arbitrage::best_arbitrage,
    explain::{explain_arbitrage, explain_path},
    graph::Graph,
    pathfind::best_path,
    quote::{validate_quotes, Quote, QuoteError},
    reference::{best_arbitrage_reference, best_path_reference},
};

struct Args {
    now: u64,
    max_age: u64,
    max_hops: usize,
}

impl Default for Args {
    fn default() -> Self {
        Args {
            now: 0,
            max_age: u64::MAX,
            max_hops: 4,
        }
    }
}

fn usage() -> ! {
    eprintln!(
        "usage:\n  \
         fx-arb best-path  <file> --from C --to C --amount N [--now N] [--max-age N] [--max-hops N] [--reference]\n  \
         fx-arb arbitrage  <file> [--now N] [--max-age N] [--max-hops N] [--reference]\n  \
         fx-arb validate   <file> [--now N] [--max-age N]\n  \
         fx-arb gen        [--fixture triangular|none|pseudo] [--count N]"
    );
    exit(2);
}

fn main() {
    let argv: Vec<String> = std::env::args().collect();
    if argv.len() < 2 {
        usage();
    }
    match argv[1].as_str() {
        "best-path" => cmd_best_path(&argv[2..]),
        "arbitrage" => cmd_arbitrage(&argv[2..]),
        "validate" => cmd_validate(&argv[2..]),
        "gen" => cmd_gen(&argv[2..]),
        _ => usage(),
    }
}

fn parse_quotes(path: &str) -> Vec<Quote> {
    let text = fs::read_to_string(path).unwrap_or_else(|e| fatal(&format!("read {path}: {e}")));
    let mut out = Vec::new();
    for (lineno, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() != 7 {
            fatal(&format!(
                "{path}:{}: expected 7 fields, got {}",
                lineno + 1,
                parts.len()
            ));
        }
        let nums: Vec<u64> = parts[2..]
            .iter()
            .map(|s| {
                s.parse::<u64>().unwrap_or_else(|_| {
                    fatal(&format!("{path}:{}: bad integer '{s}'", lineno + 1))
                })
            })
            .collect();
        out.push(Quote {
            base: parts[0].to_string(),
            quote: parts[1].to_string(),
            bid: nums[0],
            ask: nums[1],
            max_qty: nums[2],
            source_seq: nums[3],
            logical_time: nums[4],
        });
    }
    out
}

fn opt<'a>(argv: &'a [String], name: &str) -> Option<&'a str> {
    argv.iter()
        .position(|a| a == name)
        .and_then(|i| argv.get(i + 1))
        .map(String::as_str)
}

fn flag(argv: &[String], name: &str) -> bool {
    argv.iter().any(|a| a == name)
}

fn common(argv: &[String]) -> Args {
    let mut a = Args::default();
    if let Some(v) = opt(argv, "--now") {
        a.now = v.parse().unwrap_or_else(|_| fatal("bad --now"));
    }
    if let Some(v) = opt(argv, "--max-age") {
        a.max_age = v.parse().unwrap_or_else(|_| fatal("bad --max-age"));
    }
    if let Some(v) = opt(argv, "--max-hops") {
        a.max_hops = v.parse().unwrap_or_else(|_| fatal("bad --max-hops"));
    }
    a
}

fn build_graph(file: &str, now: u64, max_age: u64) -> Graph {
    let quotes = parse_quotes(file);
    let (accepted, rejected) = validate_quotes(&quotes, now, max_age);
    for (q, err) in &rejected {
        eprintln!(
            "rejected {}/{} seq={}: {}",
            q.base,
            q.quote,
            q.source_seq,
            error_name(*err)
        );
    }
    Graph::build(&accepted)
}

fn error_name(e: QuoteError) -> &'static str {
    match e {
        QuoteError::BidAboveAsk => "bid_above_ask",
        QuoteError::Expired => "expired",
        QuoteError::Duplicate => "duplicate",
        QuoteError::ReversedPair => "reversed_pair",
        QuoteError::ScaleOverflow => "scale_overflow",
    }
}

fn cmd_best_path(argv: &[String]) {
    let file = argv.first().cloned().unwrap_or_else(|| usage());
    let from = opt(argv, "--from").unwrap_or_else(|| usage());
    let to = opt(argv, "--to").unwrap_or_else(|| usage());
    let amount: u64 = opt(argv, "--amount")
        .unwrap_or_else(|| usage())
        .parse()
        .unwrap_or_else(|_| fatal("bad --amount"));
    let a = common(argv);
    let graph = build_graph(&file, a.now, a.max_age);
    let fi = graph
        .currency_index(from)
        .unwrap_or_else(|| fatal(&format!("unknown currency {from}")));
    let ti = graph
        .currency_index(to)
        .unwrap_or_else(|| fatal(&format!("unknown currency {to}")));
    let result = if flag(argv, "--reference") {
        best_path_reference(&graph, fi, ti, amount, a.max_hops, a.now, a.max_age)
    } else {
        best_path(&graph, fi, ti, amount, a.max_hops, a.now, a.max_age)
    };
    println!("{}", explain_path(&graph, result.as_ref()));
}

fn cmd_arbitrage(argv: &[String]) {
    let file = argv.first().cloned().unwrap_or_else(|| usage());
    let a = common(argv);
    let graph = build_graph(&file, a.now, a.max_age);
    let result = if flag(argv, "--reference") {
        best_arbitrage_reference(&graph, a.max_hops, a.now, a.max_age)
    } else {
        best_arbitrage(&graph, a.max_hops, a.now, a.max_age)
    };
    println!("{}", explain_arbitrage(&graph, result.as_ref()));
}

fn cmd_validate(argv: &[String]) {
    let file = argv.first().cloned().unwrap_or_else(|| usage());
    let a = common(argv);
    let quotes = parse_quotes(&file);
    let (accepted, rejected) = validate_quotes(&quotes, a.now, a.max_age);
    for (q, err) in &rejected {
        println!(
            "REJECTED {}/{}\tseq={}\t{}",
            q.base,
            q.quote,
            q.source_seq,
            error_name(*err)
        );
    }
    println!("accepted={} rejected={}", accepted.len(), rejected.len());
}

fn cmd_gen(argv: &[String]) {
    let fixture = opt(argv, "--fixture").unwrap_or("triangular");
    let count: usize = opt(argv, "--count")
        .and_then(|s| s.parse().ok())
        .unwrap_or(1);
    match fixture {
        "triangular" => {
            // 确定性三角套利：A->B 1.1, B->C 1.1, C->A 0.83 => 乘积 1.0043
            println!("# A/B bid=1.1 ask=2.0 qty=1000 seq=1 t=10");
            println!("A B 1100000 2000000 1000000000 1 10");
            println!("B C 1100000 2000000 1000000000 2 10");
            println!("C A 830000 2000000 1000000000 3 10");
            for k in 1..count {
                println!("# noise pair set {k}");
                println!("D{k} E{k} 1000000 1000001 1000000000 {k}0 10");
            }
        }
        "pseudo" => {
            // 对数乘积略大于 1，但容量=1 最小单位时逐边取整收益为 0。
            println!("A B 1000100 2000000 1 1 10");
            println!("A B 1000000 1000051 1000000000 2 10");
        }
        "none" => {
            // 普通无套利报价：买入价始终略低于卖出价。
            println!("A B 999000 1001000 1000000000 1 10");
            println!("B C 999000 1001000 1000000000 2 10");
            println!("C A 999000 1001000 1000000000 3 10");
        }
        other => fatal(&format!("unknown fixture {other}")),
    }
}

fn fatal(msg: &str) -> ! {
    eprintln!("error: {msg}");
    exit(1);
}


