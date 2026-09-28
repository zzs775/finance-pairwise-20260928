package circuitbreaker

import "testing"

func TestWindowEvictsAtHorizonBoundary(t *testing.T) {
	w := newWindow(10)
	w.add(tradePoint{time: 0, seq: 1, price: 9000, ruleVersion: 1})
	w.add(tradePoint{time: 5, seq: 2, price: 8000, ruleVersion: 1})

	w.evict(10)
	// t=0 is exactly at the boundary now-horizon and must be retained.
	if w.len() != 2 {
		t.Fatalf("boundary point must be retained, len=%d", w.len())
	}

	w.evict(11)
	if w.len() != 1 {
		t.Fatalf("t=0 point should be evicted at now=11, len=%d", w.len())
	}
	low, ok := w.minPriceSince(1, 1)
	if !ok || low != 8000 {
		t.Fatalf("want retained low 8000, got %d ok=%v", low, ok)
	}
}

func TestWindowMinPriceFiltersByVersion(t *testing.T) {
	w := newWindow(100)
	w.add(tradePoint{time: 1, seq: 1, price: 7000, ruleVersion: 1})
	w.add(tradePoint{time: 2, seq: 2, price: 6000, ruleVersion: 2})

	if low, ok := w.minPriceSince(0, 1); !ok || low != 7000 {
		t.Fatalf("version 1 low want 7000, got %d ok=%v", low, ok)
	}
	if low, ok := w.minPriceSince(0, 2); !ok || low != 6000 {
		t.Fatalf("version 2 low want 6000, got %d ok=%v", low, ok)
	}
	if _, ok := w.minPriceSince(0, 3); ok {
		t.Fatal("version 3 must have no trades")
	}
}

func TestDrawdownOverflow(t *testing.T) {
	if _, ok := drawdownBP(Price(maxInt64), 1); ok {
		t.Fatal("expected overflow flag")
	}
	d, ok := drawdownBP(10000, 9000)
	if !ok || d != 1000 {
		t.Fatalf("want 1000 bps, got %d ok=%v", d, ok)
	}
}

func TestOverflowTradeRejectedByEngine(t *testing.T) {
	engine, _, _ := newTestEngine(t)
	// Force an extreme reference by installing one through a trade with a
	// huge price is impossible (ref is fixed); instead exercise the guard
	// directly: reference 1<<62, price 1 overflows the bps multiplication.
	engine.refs.refs[0] = reference{version: 1, price: 1 << 62}
	err := engine.applyTrade(trade(1, 1, 1))
	if rejectReason(err) != RejectOverflow {
		t.Fatalf("want overflow rejection, got %v", err)
	}
}
