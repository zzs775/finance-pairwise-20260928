package cce

import "fmt"

const (
	maxInt64 = 1<<63 - 1
	minInt64 = -1 << 63
)

// Currency is an ISO-like tag carried alongside every fixed-point amount.
type Currency string

// Fixed is a currency-tagged signed amount stored as a checked integer in
// the engine's minimal unit (scale 1: one integer unit equals one atomic
// unit of the currency).
type Fixed struct {
	value int64
	cur   Currency
}

// Value returns the underlying signed integer magnitude.
func (f Fixed) Value() int64 { return f.value }

// Currency returns the amount's currency tag.
func (f Fixed) Currency() Currency { return f.cur }

// IsZero reports whether the magnitude is zero.
func (f Fixed) IsZero() bool { return f.value == 0 }

// MkFixed builds a checked fixed-point amount.
func MkFixed(value int64, cur Currency) (Fixed, error) {
	if cur == "" {
		return Fixed{}, &Failure{
			Class:  ClassCurrency,
			Op:     "MkFixed",
			Detail: fmt.Sprintf("amount %d has empty currency", value),
			Err:    ErrCurrencyMismatch,
		}
	}
	return Fixed{value: value, cur: cur}, nil
}

// Zero returns the zero amount for a currency.
func Zero(cur Currency) Fixed { return Fixed{cur: cur} }

// Add returns the checked sum of two same-currency amounts.
func (f Fixed) Add(o Fixed) (Fixed, error) {
	if f.cur != o.cur {
		return Fixed{}, currencyFailure("Fixed.Add", f.cur, o.cur)
	}
	r, ok := checkedAdd(f.value, o.value)
	if !ok {
		return Fixed{}, overflowFailure("Fixed.Add", f.value, o.value, "+")
	}
	return Fixed{value: r, cur: f.cur}, nil
}

// Sub returns the checked difference of two same-currency amounts.
func (f Fixed) Sub(o Fixed) (Fixed, error) {
	if f.cur != o.cur {
		return Fixed{}, currencyFailure("Fixed.Sub", f.cur, o.cur)
	}
	r, ok := checkedSub(f.value, o.value)
	if !ok {
		return Fixed{}, overflowFailure("Fixed.Sub", f.value, o.value, "-")
	}
	return Fixed{value: r, cur: f.cur}, nil
}

// Neg returns the checked negation of an amount.
func (f Fixed) Neg() (Fixed, error) {
	if f.value == minInt64 {
		return Fixed{}, overflowFailure("Fixed.Neg", f.value, 0, "neg")
	}
	return Fixed{value: -f.value, cur: f.cur}, nil
}

// Max returns the larger of two same-currency amounts.
func MaxFixed(a, b Fixed) Fixed {
	if b.cmp(a) > 0 {
		return b
	}
	return a
}

// Cmp returns -1, 0 or 1 comparing two same-currency amounts.
func (f Fixed) Cmp(o Fixed) (int, error) {
	if f.cur != o.cur {
		return 0, currencyFailure("Fixed.Cmp", f.cur, o.cur)
	}
	return f.cmp(o), nil
}

// cmp is the currency-free comparison used internally.
func (f Fixed) cmp(o Fixed) int {
	switch {
	case f.value < o.value:
		return -1
	case f.value > o.value:
		return 1
	default:
		return 0
	}
}

// String renders the amount as "<integer value> <currency>".
func (f Fixed) String() string { return fmt.Sprintf("%d %s", f.value, f.cur) }

func checkedAdd(a, b int64) (int64, bool) {
	if b > 0 && a > maxInt64-b {
		return 0, false
	}
	if b < 0 && a < minInt64-b {
		return 0, false
	}
	return a + b, true
}

func checkedSub(a, b int64) (int64, bool) {
	if b > 0 && a < minInt64+b {
		return 0, false
	}
	if b < 0 && a > maxInt64+b {
		return 0, false
	}
	return a - b, true
}
