package risk

// All monetary amounts, prices and quantities are signed fixed-point integers
// represented as int64 (the implicit scale is agreed out-of-band). Every
// arithmetic operation goes through the checked helpers below.

import "math/bits"

// Add64 returns a+b and ok=false when the result overflows int64.
func Add64(a, b int64) (int64, bool) {
	s := int64(uint64(a) + uint64(b))
	// Same-sign operands must produce the same sign; every other wrap is
	// representable for signed addition.
	if (a >= 0) == (b >= 0) && (a >= 0) != (s >= 0) {
		return 0, false
	}
	return s, true
}

// Sub64 returns a-b and ok=false when the result overflows int64.
func Sub64(a, b int64) (int64, bool) {
	d := int64(uint64(a) - uint64(b))
	if (a >= 0) != (b >= 0) && (a >= 0) != (d >= 0) {
		return 0, false
	}
	return d, true
}

// Mul64 returns a*b and false when the result overflows int64.
func Mul64(a, b int64) (int64, bool) {
	hi, lo := bits.Mul64(uint64(a), uint64(b))
	// Correct the unsigned 128-bit high word for signed operands.
	if a < 0 {
		hi -= uint64(b)
	}
	if b < 0 {
		hi -= uint64(a)
	}
	p := int64(lo)
	// hi must equal the sign extension of lo for the product to fit.
	if hi != uint64(p>>63) {
		return 0, false
	}
	return p, true
}
