//! 报价类型、定点尺度与输入分类（过期 / 重复 / 反向 / 尺度溢出 / bid>ask）。

/// 定点尺度：1 个标准货币单位 = 1_000_000。
pub const SCALE: u64 = 1_000_000;
/// SCALE 的 f64 形式，供对数域剪枝使用。
pub const SCALE_F: f64 = SCALE as f64;
/// 允许的最大价格（1e9.000000 标准单位）。
pub const MAX_PRICE: u64 = 1_000_000_000_000_000;
/// 允许的最大数量（1e12.000000 标准单位）。
pub const MAX_QTY: u64 = 1_000_000_000_000_000_000;

/// 一条本地快照报价。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Quote {
    pub base: String,
    pub quote: String,
    pub bid: u64,
    pub ask: u64,
    pub max_qty: u64,
    pub source_seq: u64,
    pub logical_time: u64,
}

/// 报价拒绝原因，五类必须分类处理。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QuoteError {
    /// bid 高于 ask。
    BidAboveAsk,
    /// 逻辑时间早于 now - max_age。
    Expired,
    /// 同 (base, quote, source_seq) 的报价重复出现。
    Duplicate,
    /// 反向错误：base==quote 或币种代码非法。
    ReversedPair,
    /// 价格或数量超出定点尺度允许范围。
    ScaleOverflow,
}

/// 币种代码是否合法：非空、<=8 字符、ASCII 字母数字。
fn valid_currency(code: &str) -> bool {
    !code.is_empty()
        && code.len() <= 8
        && code.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// 单条报价的静态校验，按固定优先级返回首个错误。
///
/// 优先级：ReversedPair -> ScaleOverflow -> BidAboveAsk -> Expired。
/// Duplicate 需要跨报价比较，在 validate_quotes 中最后判定。
fn check_one(q: &Quote, now: u64, max_age: u64) -> Option<QuoteError> {
    if !valid_currency(&q.base) || !valid_currency(&q.quote) || q.base == q.quote {
        return Some(QuoteError::ReversedPair);
    }
    if q.bid == 0
        || q.ask == 0
        || q.bid > MAX_PRICE
        || q.ask > MAX_PRICE
        || q.max_qty > MAX_QTY
    {
        return Some(QuoteError::ScaleOverflow);
    }
    if q.bid > q.ask {
        return Some(QuoteError::BidAboveAsk);
    }
    // 过期边界：age == max_age 仍有效，age == max_age + 1 过期。
    if q.logical_time < now.saturating_sub(max_age) {
        return Some(QuoteError::Expired);
    }
    None
}

/// 报价的规范排序键：保证结果与输入顺序无关。
fn canon_key(q: &Quote) -> (&str, &str, u64, u64, u64, u64, u64) {
    (
        q.base.as_str(),
        q.quote.as_str(),
        q.source_seq,
        q.logical_time,
        q.bid,
        q.ask,
        q.max_qty,
    )
}

/// 校验并规范化一批报价：顺序无关地保留接受集与拒绝明细。
///
/// 返回 (accepted, rejected)：两者均按规范键排序；同 (base, quote,
/// source_seq) 的报价只保留规范序最小的一条，其余记为 Duplicate。
pub fn validate_quotes(
    quotes: &[Quote],
    now: u64,
    max_age: u64,
) -> (Vec<Quote>, Vec<(Quote, QuoteError)>) {
    let mut rejected: Vec<(Quote, QuoteError)> = Vec::new();
    let mut candidates: Vec<Quote> = Vec::new();
    for q in quotes {
        match check_one(q, now, max_age) {
            Some(err) => rejected.push((q.clone(), err)),
            None => candidates.push(q.clone()),
        }
    }
    // 规范排序后做重复判定，保证与输入顺序无关。
    candidates.sort_by(|a, b| canon_key(a).cmp(&canon_key(b)));
    let mut accepted: Vec<Quote> = Vec::new();
    for q in candidates {
        let dup = accepted
            .last()
            .map(|p| {
                p.base == q.base && p.quote == q.quote && p.source_seq == q.source_seq
            })
            .unwrap_or(false);
        if dup {
            rejected.push((q, QuoteError::Duplicate));
        } else {
            accepted.push(q);
        }
    }
    rejected.sort_by(|a, b| canon_key(&a.0).cmp(&canon_key(&b.0)));
    (accepted, rejected)
}

/// 模拟“并发分片 -> 汇总”的快照摄入：每个分片先独立校验，
/// 再把各分片的接受/拒绝结果合并后统一再校验。
///
/// 结果只取决于报价集合，与分片边界、分片到达顺序无关。
pub fn merge_shards(
    shards: &[Vec<Quote>],
    now: u64,
    max_age: u64,
) -> (Vec<Quote>, Vec<(Quote, QuoteError)>) {
    let mut pooled: Vec<Quote> = Vec::new();
    for shard in shards {
        let (accepted, rejected) = validate_quotes(shard, now, max_age);
        pooled.extend(accepted);
        pooled.extend(rejected.into_iter().map(|(q, _)| q));
    }
    validate_quotes(&pooled, now, max_age)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn q(base: &str, quote: &str, bid: u64, ask: u64, seq: u64, time: u64) -> Quote {
        Quote {
            base: base.into(),
            quote: quote.into(),
            bid,
            ask,
            max_qty: 1_000 * SCALE,
            source_seq: seq,
            logical_time: time,
        }
    }

    #[test]
    fn classifies_each_error_kind() {
        let now = 1_000;
        let age = 100;
        let input = vec![
            q("USD", "USD", 1, 2, 1, now),          // ReversedPair
            q("USD", "", 1, 2, 2, now),             // ReversedPair
            q("USD", "EUR", 0, 2, 3, now),          // ScaleOverflow (bid==0)
            q("USD", "EUR", 1, MAX_PRICE + 1, 4, now), // ScaleOverflow
            q("USD", "EUR", 3, 2, 5, now),          // BidAboveAsk
            q("USD", "EUR", 1, 2, 6, now - age - 1), // Expired
            q("USD", "EUR", 1, 2, 7, now),          // ok
            q("USD", "EUR", 1, 2, 7, now),          // Duplicate
        ];
        let (ok, bad) = validate_quotes(&input, now, age);
        assert_eq!(ok.len(), 1);
        let count = |kind: QuoteError| bad.iter().filter(|(_, e)| *e == kind).count();
        assert_eq!(count(QuoteError::ReversedPair), 2);
        assert_eq!(count(QuoteError::ScaleOverflow), 2);
        assert_eq!(count(QuoteError::BidAboveAsk), 1);
        assert_eq!(count(QuoteError::Expired), 1);
        assert_eq!(count(QuoteError::Duplicate), 1);
    }

    #[test]
    fn expiry_boundary_is_inclusive() {
        let now = 1_000;
        let age = 100;
        let live = q("USD", "EUR", 1, 2, 1, now - age);
        let dead = q("USD", "JPY", 1, 2, 2, now - age - 1);
        let (ok, bad) = validate_quotes(&[live, dead], now, age);
        assert_eq!(ok.len(), 1);
        assert_eq!(bad, vec![(q("USD", "JPY", 1, 2, 2, now - age - 1), QuoteError::Expired)]);
    }

    #[test]
    fn result_is_order_independent() {
        let now = 10;
        let a = q("USD", "EUR", 1, 2, 1, now);
        let b = q("EUR", "JPY", 1, 2, 2, now);
        let c = q("USD", "EUR", 1, 3, 1, now); // 与 a 同键，规范序更大 -> Duplicate
        let (ok1, bad1) = validate_quotes(&[a.clone(), b.clone(), c.clone()], now, 10);
        let (ok2, bad2) = validate_quotes(&[c, b, a], now, 10);
        assert_eq!(ok1, ok2);
        assert_eq!(bad1, bad2);
        assert_eq!(ok1.len(), 2);
        assert_eq!(bad1.len(), 1);
    }
}
