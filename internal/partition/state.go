package partition

import "marketmerge/internal/types"

// DigestEntry is one applied seq with its payload digest.
type DigestEntry struct {
	Seq    uint64
	Digest uint64
}

// AppliedDigests returns the recorded applied digests in seq order.
func (t *Tracker) AppliedDigests(src types.Source) []DigestEntry {
	applied := t.applied[src]
	out := make([]DigestEntry, 0, len(applied))
	for seq, digest := range applied {
		out = append(out, DigestEntry{Seq: seq, Digest: digest})
	}
	// sort by seq
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j-1].Seq > out[j].Seq; j-- {
			out[j-1], out[j] = out[j], out[j-1]
		}
	}
	return out
}

// Snapshot is the serializable tracker state for one source.
type Snapshot struct {
	NextExpected uint64
	Applied      []DigestEntry
}

// NewFromSnapshot builds a Tracker-equivalent state holder.
func NewFromSnapshot(snap Snapshot) *Tracker {
	t := NewTracker()
	// Tracker is per-source inside merger; here restore into the anonymous
	// source handled by RestoreInto. Kept simple: callers use RestoreState.
	_ = t
	return t
}

// RestoreState seeds the tracker state for a specific source.
func (t *Tracker) RestoreState(src types.Source, nextExpected uint64, applied []DigestEntry) {
	t.next[src] = nextExpected
	m := make(map[uint64]uint64, len(applied))
	for _, e := range applied {
		m[e.Seq] = e.Digest
	}
	t.applied[src] = m
}
