package riskengine

import "math"

// add64 returns a+b or ErrOverflow when the int64 range is exceeded.
func add64(a, b int64) (int64, *Error) {
	if b > 0 && a > math.MaxInt64-b {
		return 0, codeErr(ErrOverflow, "addition overflow")
	}
	if b < 0 && a < math.MinInt64-b {
		return 0, codeErr(ErrOverflow, "addition overflow")
	}
	return a + b, nil
}

// mul64 returns a*b or ErrOverflow when the int64 range is exceeded.
func mul64(a, b int64) (int64, *Error) {
	if a == 0 || b == 0 {
		return 0, nil
	}
	r := a * b
	if r/b != a {
		return 0, codeErr(ErrOverflow, "multiplication overflow")
	}
	return r, nil
}
