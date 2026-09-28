package circuitbreaker

import "math/bits"

const maxInt64 = int64(^uint64(0) >> 1)

// RuleSet is one versioned set of circuit breaker parameters.
type RuleSet struct {
	Version  Version
	Horizon  Duration
	Level1   BasisPoints
	Level2   BasisPoints
	Level3   BasisPoints
	Recover  BasisPoints
	Halt1    Duration
	Halt2    Duration
	Cooldown Duration
}

func (r RuleSet) Validate() error {
	if r.Version < 0 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "negative rule version"}
	}
	if r.Level1 <= 0 || r.Level2 <= 0 || r.Level3 <= 0 || r.Recover < 0 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "thresholds must be positive"}
	}
	if !(r.Level1 < r.Level2 && r.Level2 < r.Level3) {
		return &RejectError{Reason: RejectIllegalRules, Detail: "levels must be strictly increasing"}
	}
	if r.Recover >= r.Level1 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "recovery threshold must be below level 1"}
	}
	if r.Horizon < 0 || r.Halt1 < 0 || r.Halt2 < 0 || r.Cooldown < 0 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "durations must be non-negative"}
	}
	if r.Horizon == 0 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "horizon must be positive"}
	}
	if r.Halt2 < r.Halt1 {
		return &RejectError{Reason: RejectIllegalRules, Detail: "halt2 duration must be at least halt1"}
	}
	return nil
}

// drawdownBP computes the drop of price versus ref in basis points.
// The second result is false on arithmetic overflow.
func drawdownBP(ref, price Price) (BasisPoints, bool) {
	if ref <= 0 {
		return 0, false
	}
	if price > ref {
		return 0, true
	}
	drop := int64(ref) - int64(price)
	if drop > maxInt64/10000 {
		return 0, false
	}
	num := drop * 10000
	q, _ := bits.Div64(0, uint64(num), uint64(ref))
	if q > uint64(maxInt64) {
		return 0, false
	}
	return BasisPoints(q), true
}

// reference is a versioned reference price effective from a time onward.
type reference struct {
	version   Version
	price     Price
	effective Time
}

// refBook stores versioned reference prices.
type refBook struct {
	refs []reference
}

func newRefBook() *refBook {
	return &refBook{refs: make([]reference, 0)}
}

// install registers a versioned reference price. Each version may be
// installed at most once; redefining one is a conflicting duplicate.
func (b *refBook) install(r reference) error {
	if r.version < 0 || r.price <= 0 {
		return &RejectError{Reason: RejectIllegalEvent, Detail: "bad reference version or price"}
	}
	for _, old := range b.refs {
		if old.version == r.version {
			if old.price == r.price && old.effective == r.effective {
				return nil
			}
			return &RejectError{Reason: RejectDuplicateConflict, Detail: "reference version redefined"}
		}
	}
	b.refs = append(b.refs, r)
	return nil
}

// priceAt returns the reference visible at time t under version v. A new
// reference version only affects events at or after its effective time.
func (b *refBook) priceAt(t Time, v Version) (Price, bool) {
	for _, r := range b.refs {
		if r.version == v && r.effective <= t {
			return r.price, true
		}
	}
	return 0, false
}
