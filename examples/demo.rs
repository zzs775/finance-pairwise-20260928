//! Library API demo: encode, sequentially decode, and run a range query.

use mktcodec::decoder::Decoder;
use mktcodec::encoder::encode_all;
use mktcodec::query::query_range;
use mktcodec::segment::Quote;

fn main() {
    let quotes: Vec<Quote> = (0..200)
        .map(|i| Quote::new(1_000 + i as i64 * 5, 100 + i as i64 % 7, 104 + i as i64 % 7, i as i64 % 11))
        .collect();

    let bytes = encode_all(&quotes, 40).expect("encode");
    println!(
        "encoded {} records into {} bytes (schema version 1)",
        quotes.len(),
        bytes.len()
    );

    let decoded = Decoder::new(&bytes)
        .expect("open container")
        .decode_all()
        .expect("decode all");
    assert_eq!(decoded, quotes);

    let hits = query_range(&bytes, 1300, 1320).expect("range query");
    println!("query [1300, 1320) returned {} records:", hits.len());
    for quote in hits {
        println!(
            "  ts={} bid={} ask={} size={}",
            quote.ts, quote.bid, quote.ask, quote.size
        );
    }
}
