package merger

import (
	"testing"

	"marketmerge/internal/types"
)

// TestGapOpenTimingIsOrderIndependent pins the core invariant: healthy
// sources must not publish a snapshot at time T once any registered source
// has accepted a message with seq beyond a hole whose missing record could
// (by source monotonic time) carry time <= T.
func TestGapOpenTimingIsOrderIndependent(t *testing.T) {
	// Source A healthy: t=1,2,3,4,5.
	// Source B: seq1 t=1, seq3 t=5 arrives BEFORE seq2 t=2 (a gap), then
	// seq2 t=2 backfills.
	A := types.Source{Venue: "A", Partition: 0}
	B := types.Source{Venue: "B", Partition: 0}

	run := func(seq ...types.Message) []string {
		mg := New(64, 1000)
		mg.RegisterSources(A, B)
		var pub []types.Message
		for _, m := range seq {
			out, err := mg.Push(m)
			if err != nil {
				t.Fatalf("push %s%d: %v", m.Venue, m.Seq, err)
			}
			pub = append(pub, out...)
		}
		pub = append(pub, mg.FlushAll()...)
		return labels(pub)
	}

	a := func(seq uint64, tt int64) types.Message {
		return q("A", 0, seq, tt, "S", types.SideAsk, 100, 10)
	}
	b := func(seq uint64, tt int64) types.Message {
		return q("B", 0, seq, tt, "S", types.SideAsk, 100, 10)
	}

	// All A first, then B1, B3 (gap), B2 backfill.
	forward := run(
		a(1, 1), a(2, 2), a(3, 3), a(4, 4), a(5, 5),
		b(1, 1), b(3, 5), b(2, 2),
	)
	// B gappy first, A interleaved, backfill last.
	reverse := run(
		b(1, 1), b(3, 5),
		a(1, 1), a(2, 2), a(3, 3), a(4, 4), a(5, 5),
		b(2, 2),
	)
	want := []string{"A1", "B1", "A2", "B2", "A3", "A4", "A5", "B3"}
	if !eqStr(forward, want) {
		t.Fatalf("forward=%v want %v", forward, want)
	}
	if !eqStr(reverse, want) {
		t.Fatalf("reverse=%v want %v", reverse, want)
	}
}
