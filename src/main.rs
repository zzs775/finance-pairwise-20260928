//! Command line interface for the mktcodec container.
//!
//! Text interchange format is one JSON object per line:
//! {"ts":1000,"bid":99,"ask":101,"size":5}

use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::process::ExitCode;

use mktcodec::decoder::Decoder;
use mktcodec::encoder::encode_all;
use mktcodec::error::CodecError;
use mktcodec::query::query_range;
use mktcodec::segment::Quote;

fn print_usage() {
    eprintln!(
        "mktcodec {version} - segmented synthetic quote codec\n\n\
         USAGE:\n  \
         mktcodec encode  <out.mq> [records-per-segment] < records.jsonl\n  \
         mktcodec decode  <in.mq> > records.jsonl\n  \
         mktcodec query   <in.mq> <start-ts> <end-ts> > records.jsonl\n  \
         mktcodec verify  <in.mq>\n\n\
         Time ranges are half open: [start, end).",
        version = env!("CARGO_PKG_VERSION")
    );
}

fn fail(error: CodecError) -> ExitCode {
    eprintln!("error: {error}");
    ExitCode::from(1)
}

fn read_jsonl(reader: impl Read) -> io::Result<Vec<Quote>> {
    let buffered = BufReader::new(reader);
    let mut quotes = Vec::new();
    for (line_no, line) in buffered.lines().enumerate() {
        let line = line?;
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        match parse_json_object(trimmed) {
            Some(quote) => quotes.push(quote),
            None => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("malformed JSON object on line {}", line_no + 1),
                ))
            }
        }
    }
    Ok(quotes)
}

fn parse_json_object(line: &str) -> Option<Quote> {
    let body = line.strip_prefix('{')?.strip_suffix('}')?;
    let mut ts = None;
    let mut bid = None;
    let mut ask = None;
    let mut size = None;
    for field in body.split(',') {
        let (key, value) = field.split_once(':')?;
        let key = key.trim().trim_matches('"');
        let value: i64 = value.trim().parse().ok()?;
        match key {
            "ts" => ts = Some(value),
            "bid" => bid = Some(value),
            "ask" => ask = Some(value),
            "size" => size = Some(value),
            _ => {}
        }
    }
    Some(Quote::new(ts?, bid?, ask?, size?))
}

fn write_jsonl(out: impl Write, quotes: &[Quote]) -> io::Result<()> {
    let mut writer = io::BufWriter::new(out);
    for quote in quotes {
        writeln!(
            writer,
            "{{\"ts\":{},\"bid\":{},\"ask\":{},\"size\":{}}}",
            quote.ts, quote.bid, quote.ask, quote.size
        )?;
    }
    writer.flush()
}

fn run() -> Result<(), CliError> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        print_usage();
        return Ok(());
    }

    match args[1].as_str() {
        "encode" => {
            let path = args.get(2).ok_or(CliError::Usage("missing <out.mq>"))?;
            let records_per_segment = args
                .get(3)
                .map(|value| value.parse::<u32>())
                .transpose()
                .map_err(|_| CliError::Usage("records-per-segment must be a u32"))?
                .unwrap_or(1024);
            let quotes = read_jsonl(io::stdin())?;
            let bytes = encode_all(&quotes, records_per_segment).map_err(CliError::Codec)?;
            fs::write(path, bytes)?;
            eprintln!("encoded {} record(s) -> {path}", quotes.len());
        }
        "decode" => {
            let path = args.get(2).ok_or(CliError::Usage("missing <in.mq>"))?;
            let bytes = fs::read(path)?;
            let quotes = Decoder::new(&bytes)
                .map_err(CliError::Codec)?
                .decode_all()
                .map_err(CliError::Codec)?;
            write_jsonl(io::stdout(), &quotes)?;
        }
        "query" => {
            let path = args.get(2).ok_or(CliError::Usage("missing <in.mq>"))?;
            let start: i64 = args
                .get(3)
                .ok_or(CliError::Usage("missing <start-ts>"))?
                .parse()
                .map_err(|_| CliError::Usage("start-ts must be an i64"))?;
            let end: i64 = args
                .get(4)
                .ok_or(CliError::Usage("missing <end-ts>"))?
                .parse()
                .map_err(|_| CliError::Usage("end-ts must be an i64"))?;
            let bytes = fs::read(path)?;
            let quotes = query_range(&bytes, start, end).map_err(CliError::Codec)?;
            write_jsonl(io::stdout(), &quotes)?;
        }
        "verify" => {
            let path = args.get(2).ok_or(CliError::Usage("missing <in.mq>"))?;
            let bytes = fs::read(path)?;
            let decoder = Decoder::new(&bytes).map_err(CliError::Codec)?;
            let count = decoder.decode_all().map_err(CliError::Codec)?.len();
            eprintln!("ok: {count} record(s) verified");
        }
        other => {
            eprintln!("unknown command: {other}");
            print_usage();
            return Err(CliError::Usage("unknown command"));
        }
    }
    Ok(())
}

enum CliError {
    Usage(&'static str),
    Io(io::Error),
    Codec(CodecError),
}

impl From<io::Error> for CliError {
    fn from(error: io::Error) -> Self {
        CliError::Io(error)
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(CliError::Usage(message)) => {
            eprintln!("usage error: {message}");
            print_usage();
            ExitCode::from(2)
        }
        Err(CliError::Io(error)) => {
            eprintln!("io error: {error}");
            ExitCode::from(1)
        }
        Err(CliError::Codec(error)) => fail(error),
    }
}
