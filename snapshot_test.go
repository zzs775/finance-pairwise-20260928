package risk

import "testing"

func TestSnapshotCanonicalStable(t *testing.T) {
	e := newTestEngine(t)
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100})
	b1, err := e.Snapshot().Canonical()
	if err != nil {
		t.Fatal(err)
	}
	b2, err := e.Snapshot().Canonical()
	if err != nil {
		t.Fatal(err)
	}
	if string(b1) != string(b2) {
		t.Fatalf("snapshot encoding is not deterministic")
	}
	if len(b1) == 0 {
		t.Fatalf("empty snapshot")
	}
}

func TestSnapshotRestoreRoundTrip(t *testing.T) {
	e := newTestEngine(t)
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100})
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Sell,
		Price: 10, Qty: 10, Key: "s1", ExpireAt: 200})
	e.Confirm("k1", 50)

	snap := e.Snapshot()

	got := NewEngine()
	if err := got.Restore(snap); err != nil {
		t.Fatalf("restore: %v", err)
	}
	b1, _ := snap.Canonical()
	b2, _ := got.Snapshot().Canonical()
	if string(b1) != string(b2) {
		t.Fatalf("snapshots differ after restore:\n%s\n----\n%s", b1, b2)
	}

	// Heap rebuilt: the live sell reservation still expires.
	keys := got.ExpireDue(200)
	if len(keys) != 1 || keys[0] != "s1" {
		t.Fatalf("expiry after restore: %v", keys)
	}
	// Confirmed reservation is not in the heap.
	keys = got.ExpireDue(1000)
	if len(keys) != 0 {
		t.Fatalf("confirmed item re-appeared: %v", keys)
	}

	// Idempotency survives restore.
	r := got.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100})
	if !r.OK || !r.Replay {
		t.Fatalf("idempotency lost on restore: %+v", r)
	}
	// Sequence continues after the restored value.
	r = got.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 1, Qty: 1, Key: "k9", ExpireAt: 300})
	if !r.OK || r.Seq != 5 {
		t.Fatalf("seq after restore: %+v", r)
	}
}
