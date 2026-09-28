package riskengine

import "testing"

func TestExpirySweepAndRace(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())

	eng.Reserve(buyReq("e1", "A", "STK", 1_000_000, 10_000, 10))
	eng.Reserve(buyReq("e2", "A", "STK", 1_000_000, 10_000, 10))

	// Nothing due before the first deadline.
	if got := eng.ExpireDue(); len(got) != 0 {
		t.Fatalf("nothing should expire at t=0, got %d", len(got))
	}
	mc.Advance(10)
	// At exactly the expiry tick the frozen reservation still wins:
	// confirm succeeds and expiry does not reclaim it.
	if _, err := eng.Confirm("e1"); err != nil {
		t.Fatalf("confirm at expiry tick must win: %v", err)
	}
	if got := eng.ExpireDue(); len(got) != 0 {
		t.Fatalf("just-confirmed reservation must not expire, got %d", len(got))
	}

	mc.Advance(1)
	got := eng.ExpireDue()
	if len(got) != 1 || got[0].Order.IdemKey != "e2" || got[0].Status != StatusExpired {
		t.Fatalf("unexpected expiry result: %+v", got)
	}
	acc := eng.accounts["A"]
	if acc.resCash != 0 {
		t.Fatalf("expired cash not returned: %d", acc.resCash)
	}
	// Expired reservation cannot be confirmed later.
	if _, err := eng.Confirm("e2"); err == nil || err.Code != ErrInvalidTransition {
		t.Fatalf("confirm after expiry must fail, got %v", err)
	}
	// Sweep is idempotent.
	if got := eng.ExpireDue(); len(got) != 0 {
		t.Fatalf("second sweep must reclaim nothing, got %d", len(got))
	}
}

func TestSameInstantFrozenPriority(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	// Two reservations expire at the same instant; heap tie-break must be
	// the earlier-frozen (lower seq) order regardless of later operations.
	eng.Reserve(buyReq("p1", "A", "STK", 1_000_000, 1_000, 10))
	eng.Reserve(buyReq("p2", "A", "STK", 1_000_000, 1_000, 10))
	mc.Advance(11)
	got := eng.ExpireDue()
	if len(got) != 2 {
		t.Fatalf("want 2 expired, got %d", len(got))
	}
	if got[0].Seq >= got[1].Seq {
		t.Fatalf("same-instant expiry must favour frozen priority: %+v", got)
	}
}

func TestSnapshotRoundTripAndRecovery(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	eng.Reserve(buyReq("o1", "A", "STK", 1_000_000, 10_000, 100))
	eng.Reserve(buyReq("o2", "B", "STK", 1_000_000, 5_000, 0))
	eng.Confirm("o1")
	eng.Reserve(sellReq("o3", "A", "STK", 10_000_000, 100, 100))
	mc.Advance(50)
	s1 := eng.Snapshot()

	data, err := s1.Encode()
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeSnapshot(data)
	if err != nil {
		t.Fatal(err)
	}
	if !s1.Equal(decoded) {
		t.Fatal("gob round trip changed snapshot")
	}

	mc2 := &ManualClock{T: 50}
	var restored *Engine
	var rerr *Error
	restored, rerr = Restore(decoded, mc2)
	if rerr != nil {
		t.Fatalf("restore: %v", rerr)
	}
	s2 := restored.Snapshot()
	if !s1.Equal(s2) {
		t.Fatal("restored engine snapshot differs")
	}
	// Restored heap honours deadlines: o3 expires at t=100.
	mc2.Advance(51)
	expired := restored.ExpireDue()
	if len(expired) != 1 || expired[0].Order.IdemKey != "o3" {
		t.Fatalf("restored heap expiry wrong: %+v", expired)
	}
	// Audit sequence continues monotonically after restore.
	var o4 *OrderResult
	var o4err *Error
	o4, o4err = restored.Reserve(buyReq("o4", "B", "STK", 1_000_000, 1_000, 200))
	if o4err != nil {
		t.Fatal(o4err)
	}
	if o4.Seq <= s1.Seq {
		t.Fatalf("audit seq must continue after restore: %d <= %d", o4.Seq, s1.Seq)
	}
}

func TestSnapshotDeterminism(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	for i := 0; i < 8; i++ {
		key := string(rune('a' + i))
		eng.Reserve(buyReq(key, "A", "STK", 100_000, 100, 0))
	}
	a, _ := eng.Snapshot().Encode()
	b, _ := eng.Snapshot().Encode()
	if string(a) != string(b) {
		t.Fatal("snapshot encoding is not stable across calls")
	}
}
