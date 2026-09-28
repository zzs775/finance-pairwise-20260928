// Package partition tracks strict per-source sequence counters.
package partition

import (
	"math"

	"marketmerge/internal/types"
)

// Outcome classifies a message relative to the source frontier.
type Outcome int

const (
	// OutcomeAccept means seq equals the next expected value and the caller
	// may apply the message and then advance the frontier.
	OutcomeAccept Outcome = iota
	// OutcomeRedeliver means an identical message for seq was already applied.
	OutcomeRedeliver
	// OutcomeBuffered means seq is ahead of the frontier (gap in between);
	// the caller must hold it inside the reorder window.
	OutcomeBuffered
)

// Tracker validates strict increasing sequence numbers for each source.
type Tracker struct {
	first   uint64 // first expected sequence number of every source
	next    map[types.Source]uint64
	applied map[types.Source]map[uint64]uint64 // seq -> payload digest
	pending map[types.Source]map[uint64]uint64 // buffered-ahead seq -> digest
}

// NewTracker creates an empty sequence tracker.
func NewTracker() *Tracker {
	return &Tracker{
		first:   1,
		next:    map[types.Source]uint64{},
		applied: map[types.Source]map[uint64]uint64{},
		pending: map[types.Source]map[uint64]uint64{},
	}
}

func (t *Tracker) expected(src types.Source) uint64 {
	if n, ok := t.next[src]; ok {
		return n
	}
	return t.first
}

// Ingest validates one message against the source's expected sequence.
// It returns a *types.Error classification or nil.
//
// Ingest is the standalone, no-buffering mode: only strict in-order delivery
// is accepted. Out-of-order (gap) delivery returns OutcomeBuffered so callers
// using a reorder window can retain the message.
func (t *Tracker) Ingest(m types.Message) (Outcome, *types.Error) {
	return t.Examine(m)
}

// Examine classifies one message without mutating the frontier.
func (t *Tracker) Examine(m types.Message) (Outcome, *types.Error) {
	src := m.Source()
	if m.Seq == 0 {
		return 0, &types.Error{Kind: types.KindSeqRollback, Source: src,
			Message: "sequence numbers start at 1, got 0"}
	}
	if got, ok := t.appliedDigest(src, m.Seq); ok {
		if got == m.Digest() {
			return OutcomeRedeliver, nil
		}
		return 0, &types.Error{Kind: types.KindDuplicate, Source: src,
			Message: "conflicting re-delivery for seq " + uitoa(m.Seq)}
	}
	exp := t.expected(src)
	switch {
	case m.Seq < exp:
		return 0, &types.Error{Kind: types.KindSeqRollback, Source: src,
			Message: "seq " + uitoa(m.Seq) + " below expected " + uitoa(exp)}
	case m.Seq == exp:
		return OutcomeAccept, nil
	default:
		return OutcomeBuffered, nil
	}
}

// Expected returns the next sequence number expected from a source.
func (t *Tracker) Expected(src types.Source) uint64 {
	return t.expected(src)
}

// NotePending records a buffered, ahead-of-frontier message. An identical
// buffered copy is reported as OutcomeRedeliver; a differing copy is a
// duplicate-conflict error.
func (t *Tracker) NotePending(m types.Message) (Outcome, *types.Error) {
	src := m.Source()
	if got, ok := t.appliedDigest(src, m.Seq); ok {
		if got == m.Digest() {
			return OutcomeRedeliver, nil
		}
		return 0, &types.Error{Kind: types.KindDuplicate, Source: src,
			Message: "conflicting re-delivery for seq " + uitoa(m.Seq)}
	}
	pending := t.pending[src]
	if pending == nil {
		pending = map[uint64]uint64{}
		t.pending[src] = pending
	}
	if got, ok := pending[m.Seq]; ok {
		if got == m.Digest() {
			return OutcomeRedeliver, nil
		}
		return 0, &types.Error{Kind: types.KindDuplicate, Source: src,
			Message: "conflicting re-delivery for seq " + uitoa(m.Seq)}
	}
	pending[m.Seq] = m.Digest()
	return OutcomeBuffered, nil
}

// PendingDigest returns the recorded digest of a buffered ahead message.
func (t *Tracker) PendingDigest(src types.Source, seq uint64) (uint64, bool) {
	d, ok := t.pending[src][seq]
	return d, ok
}

// Advance commits seq as applied and moves the frontier to seq+1.
// It must be called in order, starting at Expected. Advancing past the
// maximum uint64 sequence is classified as KindSeqOverflow.
func (t *Tracker) Advance(m types.Message) *types.Error {
	src := m.Source()
	exp := t.expected(src)
	if m.Seq != exp {
		return &types.Error{Kind: types.KindSeqRollback, Source: src,
			Message: "advance at " + uitoa(m.Seq) + " expected " + uitoa(exp)}
	}
	applied := t.applied[src]
	if applied == nil {
		applied = map[uint64]uint64{}
		t.applied[src] = applied
	}
	applied[m.Seq] = m.Digest()
	if pending := t.pending[src]; pending != nil {
		delete(pending, m.Seq)
		if len(pending) == 0 {
			delete(t.pending, src)
		}
	}
	if m.Seq == math.MaxUint64 {
		t.next[src] = 0
		return &types.Error{Kind: types.KindSeqOverflow, Source: src,
			Message: "sequence space exhausted after seq " + uitoa(m.Seq)}
	}
	t.next[src] = m.Seq + 1
	return nil
}

// Prune forgets applied digests strictly below keep, bounding memory while
// the reorder window guarantees no older duplicate can still arrive.
func (t *Tracker) Prune(src types.Source, keep uint64) {
	applied := t.applied[src]
	for seq := range applied {
		if seq < keep {
			delete(applied, seq)
		}
	}
	if len(applied) == 0 {
		delete(t.applied, src)
	}
}

func (t *Tracker) appliedDigest(src types.Source, seq uint64) (uint64, bool) {
	d, ok := t.applied[src][seq]
	return d, ok
}

func uitoa(v uint64) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	return string(buf[i:])
}
