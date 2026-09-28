//! Entitlement math for the five action shapes.

use crate::error::Result;
use crate::error::EngineError;
use crate::fixed::{trunc_div, Amount};
use crate::model::{ActionTerms, Ratio};

/// Per-account gross entitlement before residual allocation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct Entitlement {
    pub cash: Amount,
    pub securities: i64,
    /// Fractional cash before the final tail assignment.
    pub frac_cash: Amount,
}

pub fn validate_ratio(_ratio: Ratio) -> Result<()> {
    if _ratio.den <= 0 || _ratio.num <= 0 {
        return Err(EngineError::InvalidRatio(format!(
            "ratio {}/{} must have strictly positive numerator and denominator",
            _ratio.num, _ratio.den
        )));
    }
    Ok(())
}

/// Compute one account's entitlement from its eligible (signed) share count.
///
/// All arithmetic truncates toward zero, which preserves signs for short
/// positions: a short account receives negative cash/securities (i.e. an
/// obligation). Ratios are applied in i128 space; any result outside the i64
/// range is reported as `ArithmeticOverflow`.
pub fn compute_entitlement(
    terms: &ActionTerms,
    eligible: i64,
) -> Result<Entitlement> {
    let e = i128::from(eligible);
    let money = |scaled_nano: i128| -> Result<Amount> {
        crate::fixed::fits_i64(scaled_nano)
            .map(Amount)
            .ok_or_else(|| {
                EngineError::ArithmeticOverflow(
                    "cash amount outside i64 nanounit range".to_string(),
                )
            })
    };
    match terms {
        ActionTerms::CashDividend { per_share } => {
            let cash = per_share
                .checked_mul_whole(eligible)
                .ok_or_else(overflow_money)?;
            Ok(Entitlement {
                cash,
                securities: 0,
                frac_cash: Amount::ZERO,
            })
        }
        ActionTerms::Split { ratio, frac_price } => {
            validate_ratio(*ratio)?;
            // new_shares_numerator = eligible * num / den
            let num = e
                .checked_mul(i128::from(ratio.num))
                .ok_or_else(overflow_shares)?;
            let securities = trunc_div(num, i128::from(ratio.den));
            fit_shares(securities)?;
            // residual old-share equivalent, priced in NEW shares at frac_price:
            // fraction = (num mod den)/den of one new share, worth
            // frac_price * remainder/den nanounits.
            let rem = num.checked_rem(i128::from(ratio.den)).unwrap();
            let frac = frac_cash(rem, ratio.den, *frac_price)?;
            Ok(Entitlement {
                cash: Amount::ZERO,
                securities: securities as i64,
                frac_cash: frac,
            })
        }
        ActionTerms::RightsIssue {
            ratio,
            price,
            frac_price,
        } => {
            validate_ratio(*ratio)?;
            let num = e
                .checked_mul(i128::from(ratio.num))
                .ok_or_else(overflow_shares)?;
            let securities = trunc_div(num, i128::from(ratio.den));
            fit_shares(securities)?;
            let rem = num.checked_rem(i128::from(ratio.den)).unwrap();
            let frac = frac_cash(rem, ratio.den, *frac_price)?;
            // Whole new shares are subscribed at `price`; for a long holder
            // this is a cash outflow (negative), for a short holder an
            // inflow from the counterparty.
            let call_money = i128::from(price.0)
                .checked_mul(securities)
                .ok_or_else(overflow_money)?;
            let cash = money(-call_money)?;
            Ok(Entitlement {
                cash,
                securities: securities as i64,
                frac_cash: frac,
            })
        }
        ActionTerms::Merger {
            ratio,
            new_instrument: _,
            frac_price,
        } => {
            validate_ratio(*ratio)?;
            let num = e
                .checked_mul(i128::from(ratio.num))
                .ok_or_else(overflow_shares)?;
            let securities = trunc_div(num, i128::from(ratio.den));
            fit_shares(securities)?;
            let rem = num.checked_rem(i128::from(ratio.den)).unwrap();
            let frac = frac_cash(rem, ratio.den, *frac_price)?;
            Ok(Entitlement {
                cash: Amount::ZERO,
                securities: securities as i64,
                frac_cash: frac,
            })
        }
    }
}

fn frac_cash(remainder: i128, den: i64, price: Amount) -> Result<Amount> {
    let scaled = i128::from(price.0)
        .checked_mul(remainder)
        .and_then(|v| Some(v / i128::from(den)));
    match scaled {
        Some(v) => crate::fixed::fits_i64(v)
            .map(Amount)
            .ok_or_else(overflow_money),
        None => Err(overflow_money()),
    }
}

fn fit_shares(value: i128) -> Result<()> {
    if i128::from(i64::MIN) <= value && value <= i128::from(i64::MAX) {
        Ok(())
    } else {
        Err(overflow_shares())
    }
}

fn overflow_money() -> EngineError {
    EngineError::ArithmeticOverflow("cash arithmetic overflow".to_string())
}

fn overflow_shares() -> EngineError {
    EngineError::ArithmeticOverflow("share arithmetic overflow".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: i64 = Amount::SCALE as i64;

    #[test]
    fn cash_dividend_basic_and_short() {
        let t = ActionTerms::CashDividend {
            per_share: Amount(2 * N / 10), // 0.2 per share
        };
        assert_eq!(
            compute_entitlement(&t, 100).unwrap(),
            Entitlement {
                cash: Amount(20 * N),
                securities: 0,
                frac_cash: Amount::ZERO
            }
        );
        let short = compute_entitlement(&t, -50).unwrap();
        assert_eq!(short.cash, Amount(-10 * N));
    }

    #[test]
    fn split_whole_ratio() {
        let t = ActionTerms::Split {
            ratio: Ratio { num: 3, den: 2 },
            frac_price: Amount(N),
        };
        let got = compute_entitlement(&t, 100).unwrap();
        assert_eq!(got.securities, 150);
        assert_eq!(got.frac_cash, Amount::ZERO);
    }

    #[test]
    fn consolidation_fraction_is_cashed() {
        // 1 拆 0.1（10合1）：105 -> 10 股 + 半个新股 = 0.5 现金
        let t = ActionTerms::Split {
            ratio: Ratio { num: 1, den: 10 },
            frac_price: Amount(4 * N),
        };
        let got = compute_entitlement(&t, 105).unwrap();
        assert_eq!(got.securities, 10);
        assert_eq!(got.frac_cash, Amount(2 * N));
        // 空头：-105 -> -10 股 - 0.5 新股现金义务
        let sh = compute_entitlement(&t, -105).unwrap();
        assert_eq!(sh.securities, -10);
        assert_eq!(sh.frac_cash, Amount(-2 * N));
    }

    #[test]
    fn rights_issue_cash_and_fraction() {
        // 每10股配3股，配股价 2.0，零股按 5.0 转现金
        let t = ActionTerms::RightsIssue {
            ratio: Ratio { num: 3, den: 10 },
            price: Amount(2 * N),
            frac_price: Amount(5 * N),
        };
        let got = compute_entitlement(&t, 25).unwrap();
        assert_eq!(got.securities, 7);
        assert_eq!(got.cash, Amount(-14 * N));
        assert_eq!(got.frac_cash, Amount(5 * N / 2)); // 0.5 股 * 5.0
    }

    #[test]
    fn merger_into_new_instrument() {
        let t = ActionTerms::Merger {
            ratio: Ratio { num: 1, den: 4 },
            new_instrument: "BBB".to_string(),
            frac_price: Amount(8 * N),
        };
        let got = compute_entitlement(&t, 9).unwrap();
        assert_eq!(got.securities, 2);
        assert_eq!(got.frac_cash, Amount(2 * N)); // 1/4 * 8
    }

    #[test]
    fn invalid_ratio_rejected() {
        assert!(matches!(
            validate_ratio(Ratio { num: 0, den: 3 }),
            Err(EngineError::InvalidRatio(_))
        ));
        assert!(matches!(
            validate_ratio(Ratio { num: 3, den: 0 }),
            Err(EngineError::InvalidRatio(_))
        ));
        assert!(matches!(
            validate_ratio(Ratio { num: -1, den: 3 }),
            Err(EngineError::InvalidRatio(_))
        ));
    }

    #[test]
    fn overflow_is_classified() {
        let t = ActionTerms::CashDividend {
            per_share: Amount(i64::MAX),
        };
        assert!(matches!(
            compute_entitlement(&t, 2),
            Err(EngineError::ArithmeticOverflow(_))
        ));
        let t2 = ActionTerms::Split {
            ratio: Ratio { num: i64::MAX, den: 1 },
            frac_price: Amount(N),
        };
        assert!(matches!(
            compute_entitlement(&t2, i64::MAX),
            Err(EngineError::ArithmeticOverflow(_))
        ));
    }
}
