//! Fixed-point cash amounts. Scale is 1e9 nanounits per major unit.

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Default, Hash)]
pub struct Amount(pub i64);

impl Amount {
    pub const SCALE: i128 = 1_000_000_000;
    pub const ZERO: Amount = Amount(0);

    pub fn from_raw(raw: i64) -> Self {
        Amount(raw)
    }

    pub fn raw(self) -> i64 {
        self.0
    }

    pub fn checked_add(self, other: Amount) -> Option<Amount> {
        i128::from(self.0)
            .checked_add(i128::from(other.0))
            .and_then(fits_i64)
            .map(Amount)
    }

    pub fn checked_sub(self, other: Amount) -> Option<Amount> {
        i128::from(self.0)
            .checked_sub(i128::from(other.0))
            .and_then(fits_i64)
            .map(Amount)
    }

    pub fn checked_neg(self) -> Option<Amount> {
        i128::from(self.0).checked_neg().and_then(fits_i64).map(Amount)
    }

    /// Multiply the amount by an integer factor (e.g. a signed share count),
    /// rejecting overflow of the i64 nanounit range.
    pub fn checked_mul_whole(self, shares: i64) -> Option<Amount> {
        i128::from(self.0)
            .checked_mul(i128::from(shares))
            .and_then(fits_i64)
            .map(Amount)
    }
}

/// Truncated division of two signed wide integers (round toward zero).
/// Caller guarantees `den > 0`.
pub fn trunc_div(num: i128, den: i128) -> i128 {
    debug_assert!(den > 0);
    num / den
}

/// True when a wide value fits into the i64 nanounit range.
pub fn fits_i64(value: i128) -> Option<i64> {
    if i128::from(i64::MIN) <= value && value <= i128::from(i64::MAX) {
        Some(value as i64)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_sub_overflow_checked() {
        assert_eq!(Amount(5).checked_add(Amount(-2)), Some(Amount(3)));
        assert_eq!(Amount(7).checked_sub(Amount(10)), Some(Amount(-3)));
        assert_eq!(Amount(i64::MAX).checked_add(Amount(1)), None);
        assert_eq!(Amount(i64::MIN).checked_sub(Amount(1)), None);
    }

    #[test]
    fn mul_overflow_checked() {
        assert_eq!(Amount(3).checked_mul_whole(-4), Some(Amount(-12)));
        assert_eq!(Amount(i64::MAX).checked_mul_whole(2), None);
    }

    #[test]
    fn truncation_is_toward_zero() {
        assert_eq!(trunc_div(7, 3), 2);
        assert_eq!(trunc_div(-7, 3), -2);
        assert_eq!(trunc_div(-6, 3), -2);
        assert_eq!(trunc_div(0, 3), 0);
    }
}

