package cce

import (
	"sort"
	"sync"
	"time"
)

// Snapshot isolation rule: every query reads fields of exactly one
// Snapshot; a Snapshot's maps and slices are never mutated after
// publication, so concurrent readers need no synchronization.

// Snapshot is an immutable, concurrently readable set of canonical reports.
type Snapshot struct {
	seq     int64
	at      time.Time
	reports map[string]Report
	order   []string
}

// Seq returns the monotonically increasing snapshot number.
func (s *Snapshot) Seq() int64 { return s.seq }

// At returns the publication time of the snapshot.
func (s *Snapshot) At() time.Time { return s.at }

// Report looks up one netting set report.
func (s *Snapshot) Report(nettingSet string) (Report, bool) {
	r, ok := s.reports[nettingSet]
	return r, ok
}

// Reports returns all reports in canonical (netting-set-id) order.
func (s *Snapshot) Reports() []Report {
	out := make([]Report, 0, len(s.order))
	for _, id := range s.order {
		out = append(out, s.reports[id])
	}
	return out
}

// EqualContent reports whether two snapshots carry identical canonical
// reports (ignoring publication sequence and time). Used to prove
// idempotent recomputation and deterministic scheduling equivalence.
func (s *Snapshot) EqualContent(other *Snapshot) bool {
	if other == nil {
		return false
	}
	if len(s.order) != len(other.order) {
		return false
	}
	for i, id := range s.order {
		if other.order[i] != id {
			return false
		}
		if !reportsEqual(s.reports[id], other.reports[id]) {
			return false
		}
	}
	return true
}

// Store serializes snapshot publication while allowing concurrent queries.
type Store struct {
	mu      sync.RWMutex
	current *Snapshot
}

// NewStore creates an empty store with a zero-value initial snapshot.
func NewStore() *Store {
	return &Store{current: newSnapshot(0, time.Time{}, nil)}
}

// Current returns the most recently published snapshot.
func (s *Store) Current() *Snapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.current
}

// publish atomically freezes a new immutable snapshot.
func (s *Store) publish(reports []Report, at time.Time) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	seq := s.current.seq + 1
	snap := newSnapshot(seq, at, reports)
	s.current = snap
	return snap, nil
}

func newSnapshot(seq int64, at time.Time, reports []Report) *Snapshot {
	canonical := make([]Report, len(reports))
	copy(canonical, reports)
	sort.SliceStable(canonical, func(i, j int) bool {
		return canonical[i].NettingSetID < canonical[j].NettingSetID
	})

	index := make(map[string]Report, len(canonical))
	order := make([]string, 0, len(canonical))
	for _, r := range canonical {
		index[r.NettingSetID] = r
		order = append(order, r.NettingSetID)
	}
	return &Snapshot{
		seq:     seq,
		at:      at,
		reports: index,
		order:   order,
	}
}

func reportsEqual(a, b Report) bool {
	if a.NettingSetID != b.NettingSetID ||
		a.Currency != b.Currency ||
		a.State != b.State ||
		a.Closed != b.Closed {
		return false
	}
	as, bs := a.Steps, b.Steps
	return as.Version == bs.Version &&
		as.TradeCount == bs.TradeCount &&
		as.NetMtM.Value() == bs.NetMtM.Value() &&
		as.IndependentAmount.Value() == bs.IndependentAmount.Value() &&
		as.WithIndependentAmount.Value() == bs.WithIndependentAmount.Value() &&
		as.Threshold.Value() == bs.Threshold.Value() &&
		as.AfterThreshold.Value() == bs.AfterThreshold.Value() &&
		as.MTA.Value() == bs.MTA.Value() &&
		as.AfterMTA.Value() == bs.AfterMTA.Value() &&
		as.Collateral.Value() == bs.Collateral.Value() &&
		as.Exposure.Value() == bs.Exposure.Value()
}
