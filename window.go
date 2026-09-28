package circuitbreaker

// tradePoint is one trade retained inside the rolling window.
type tradePoint struct {
	time        Time
	seq         Seq
	price       Price
	ruleVersion Version
}

// window retains the trades inside the trailing look-back horizon.
type window struct {
	horizon Duration
	points  []tradePoint
}

func newWindow(horizon Duration) *window {
	return &window{horizon: horizon}
}

func (w *window) add(p tradePoint) {
	w.points = append(w.points, p)
}

func (w *window) evict(now Time) {
	cutoff := now - Time(w.horizon)
	keep := 0
	for _, p := range w.points {
		if p.time >= cutoff {
			break
		}
		keep++
	}
	if keep > 0 {
		rest := copy(w.points, w.points[keep:])
		w.points = w.points[:rest]
	}
}

func (w *window) len() int {
	return len(w.points)
}

func (w *window) reset() {
	w.points = w.points[:0]
}

// minPriceSince returns the lowest retained trade price at or after since
// tagged with the given rule version. ok is false when no trade qualifies.
func (w *window) minPriceSince(since Time, version Version) (Price, bool) {
	var min Price
	found := false
	for _, p := range w.points {
		if p.time < since || p.ruleVersion != version {
			continue
		}
		if !found || p.price < min {
			min = p.price
			found = true
		}
	}
	return min, found
}
