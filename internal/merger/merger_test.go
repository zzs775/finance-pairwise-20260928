package merger

import (
	"testing"

	"marketmerge/internal/types"
)

func q(venue string, part int, seq uint64, t int64, sym string, side types.Side, px, qty int64) types.Message {
	return types.Message{
		Venue: venue, Partition: part, Seq: seq, Time: t, Symbol: sym,
		Action: types.ActionQuote, Side: side, Price: px, Qty: qty,
	}
}

func eqInt64(a, b []int64) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func eqStr(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func times(msgs []types.Message) []int64 {
	out := make([]int64, len(msgs))
	for i := range msgs {
		out[i] = msgs[i].Time
	}
	return out
}

func labels(msgs []types.Message) []string {
	out := make([]string, len(msgs))
	for i, m := range msgs {
		out[i] = m.Venue + uitoa(m.Seq)
	}
	return out
}

func TestGlobalBarrierOrder(t *testing.T) {
	mg := New(64, 1000)
	a := types.Source{Venue: "A", Partition: 0}
	b := types.Source{Venue: "B", Partition: 0}
	mg.RegisterSources(a, b)

	var all []types.Message
	push := func(m types.Message) {
		t.Helper()
		out, err := mg.Push(m)
		if err != nil {
			t.Fatalf("push %v/%d: %v", m.Venue, m.Seq, err)
		}
		all = append(all, out...)
	}

	// All of A (t=1,3,5) before any B.
	push(q("A", 0, 1, 1, "S", types.SideAsk, 100, 10))
	push(q("A", 0, 2, 3, "S", types.SideAsk, 100, 10))
	push(q("A", 0, 3, 5, "S", types.SideAsk, 100, 10))
	// B has not delivered anything yet: nothing is globally safe.
	if len(all) != 0 {
		t.Fatalf("expected nothing before B observed, got %v", times(all))
	}

	push(q("B", 0, 1, 2, "S", types.SideAsk, 100, 10))
	// Watermark = min(A max 5, B max 2) = 2 -> publish t=1,t=2.
	if !eqInt64(times(all), []int64{1, 2}) {
		t.Fatalf("after B1 expected [1 2], got %v", times(all))
	}

	push(q("B", 0, 2, 4, "S", types.SideAsk, 100, 10))
	// Watermark = min(5,4)=4 -> additionally publish t=3,t=4.
	if !eqInt64(times(all), []int64{1, 2, 3, 4}) {
		t.Fatalf("after B2 expected [1 2 3 4], got %v", times(all))
	}

	all = append(all, mg.FlushAll()...)
	if !eqInt64(times(all), []int64{1, 2, 3, 4, 5}) {
		t.Fatalf("full expected 1..5, got %v", times(all))
	}
}

func TestIsolationDeterminism(t *testing.T) {
	build := func() *Merger {
		mg := New(64, 1000)
		mg.RegisterSources(
			types.Source{Venue: "A", Partition: 0},
			types.Source{Venue: "B", Partition: 0},
		)
		return mg
	}
	b1 := q("B", 0, 1, 1, "S", types.SideAsk, 100, 10)
	b2 := q("B", 0, 2, 2, "S", types.SideAsk, 100, 10)
	b3 := q("B", 0, 3, 3, "S", types.SideAsk, 100, 10)
	a1 := q("A", 0, 1, 1, "S", types.SideAsk, 100, 10)
	a2 := q("A", 0, 2, 2, "S", types.SideAsk, 100, 10)
	a3 := q("A", 0, 3, 3, "S", types.SideAsk, 100, 10)

	ingest := func(order ...types.Message) []string {
		mg := build()
		var pub []types.Message
		for _, m := range order {
			out, err := mg.Push(m)
			if err != nil {
				t.Fatalf("push %v/%d: %v", m.Venue, m.Seq, err)
			}
			pub = append(pub, out...)
		}
		pub = append(pub, mg.FlushAll()...)
		return labels(pub)
	}

	want := []string{"A1", "B1", "A2", "B2", "A3", "B3"}

	// All of A first, then B with its gap opened (seq3) before backfill seq2.
	if got := ingest(a1, a2, a3, b1, b3, b2); !eqStr(got, want) {
		t.Fatalf("order1=%v want %v", got, want)
	}
	// B's gappy prefix first, healthy A afterwards, backfill last.
	if got := ingest(b1, b3, a1, a2, a3, b2); !eqStr(got, want) {
		t.Fatalf("order2=%v want %v", got, want)
	}
}

func TestGapWithholdsUntilHealed(t *testing.T) {
	mg := New(64, 1000)
	mg.RegisterSources(types.Source{Venue: "B", Partition: 0})
	b1 := q("B", 0, 1, 1, "S", types.SideAsk, 100, 10)
	b3 := q("B", 0, 3, 3, "S", types.SideAsk, 100, 10)
	b2 := q("B", 0, 2, 2, "S", types.SideAsk, 100, 10)

	push := func(m types.Message) []types.Message {
		t.Helper()
		out, err := mg.Push(m)
		if err != nil {
			t.Fatalf("push: %v", err)
		}
		return out
	}
	if out := push(b1); len(out) != 1 {
		t.Fatalf("healthy b1 should publish, got %d", len(out))
	}
	if b3out := push(b3); len(b3out) != 0 { // opens gap (seq2 missing)
		t.Fatalf("b3 must be withheld during gap, got %d", len(b3out))
	}
	if !mg.Isolated(types.Source{Venue: "B"}) {
		t.Fatal("B should be isolated after gap")
	}
	out := push(b2) // backfill heals; whole withheld interval flushes atomically
	if mg.Isolated(types.Source{Venue: "B"}) {
		t.Fatal("B should recover after backfill")
	}
	out = append(out, mg.FlushAll()...)
	if !eqStr(labels(out), []string{"B2", "B3"}) {
		t.Fatalf("healed flush=%v want [B2 B3]", labels(out))
	}
}
