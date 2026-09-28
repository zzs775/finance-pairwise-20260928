// Package merger merges venue-partition streams inside a bounded reorder
// window and performs a canonical global merge.
//
// Each source (venue, partition) carries strictly increasing sequence
// numbers. Observing a sequence ahead of the frontier opens a gap and
// isolates the source: none of its messages reach publication until the
// backfill is complete, at which point the entire withheld interval is
// released. Healthy, in-order messages are published only up to a global
// safe-time barrier, so messages from concurrent readers that arrive in
// arbitrary interleavings still emerge in canonical order
// (logical time, venue, partition, seq).
package merger

import (
	"sort"

	"marketmerge/internal/partition"
	"marketmerge/internal/types"
)

const (
	minInt64 = -1 << 63
	maxInt64 = 1<<63 - 1
)

// GapInfo names a source and its smallest missing sequence number.
type GapInfo struct {
	Source    types.Source
	MinGapSeq uint64
}

// WatermarkInfo names a source and its highest observed sequence number.
type WatermarkInfo struct {
	Source       types.Source
	Watermark    uint64
	NextExpected uint64
}

// OverflowDiagnostic reports backpressure state when the buffer budget is hit.
type OverflowDiagnostic struct {
	MinGap    []GapInfo
	Watermark []WatermarkInfo
	Buffered  int
	Budget    int
}

type srcState struct {
	tracker   *partition.Tracker
	backlog   map[uint64]types.Message // observed but not at the frontier
	ready     []types.Message          // healthy, frontier-advanced, publishable
	held      []types.Message          // isolated interval awaiting backfill
	watermark uint64                   // highest observed seq
	maxObsT   int64                    // max logical time of accepted msgs
	hasObsT   bool
	isolated  bool
	closed    bool
	lastTime  int64 // logical time of the last frontier-advanced message
	hasTime   bool
}

// Merger reorders messages per source, detects gaps and isolates sources.
type Merger struct {
	window uint64 // max out-of-order distance accepted from a healthy source
	budget int    // max total retained messages across all sources
	srcs   map[types.Source]*srcState
	size   int
}

// New creates a Merger with the given reorder window and buffer budget.
func New(window, budget int) *Merger {
	if window < 0 {
		window = 0
	}
	if budget < 0 {
		budget = 0
	}
	return &Merger{
		window: uint64(window),
		budget: budget,
		srcs:   map[types.Source]*srcState{},
	}
}

// Push buffers one message and returns messages now releasable in canonical
// order. A benign duplicate (identical re-delivery) returns nothing and no
// error. Nothing is ever dropped to make room: over-budget input fails with
// an *OverflowError carrying the minimum-gap/watermark diagnostic.
func (m *Merger) Push(msg types.Message) ([]types.Message, error) {
	if err := msg.Validate(); err != nil {
		return nil, err
	}
	src := msg.Source()
	st := m.state(src)

	outcome, perr := st.tracker.Examine(msg)
	if perr != nil {
		return nil, perr
	}
	if outcome == partition.OutcomeRedeliver {
		return nil, nil
	}
	// The first copy may already be queued (ready/held) while its digest is
	// outside the tracker's retained window; classify such re-delivery here.
	if outcome == partition.OutcomeAccept {
		if digest, seen := st.queuedDigest(msg.Seq); seen {
			if digest == msg.Digest() {
				return nil, nil
			}
			return nil, &types.Error{Kind: types.KindDuplicate, Source: src,
				Message: "conflicting re-delivery for queued seq " +
					uitoa(msg.Seq)}
		}
	}

	if outcome == partition.OutcomeBuffered {
		if _, dup := st.backlog[msg.Seq]; dup {
			if _, derr := st.tracker.NotePending(msg); derr != nil {
				return nil, derr
			}
			return nil, nil
		}
		exp := st.tracker.Expected(src)
		if !st.isolated && m.window > 0 && msg.Seq-exp > m.window {
			return nil, &types.Error{Kind: types.KindGap, Source: src,
				Message: "seq " + uitoa(msg.Seq) + " exceeds reorder window " +
					uitoa(m.window) + " from expected " + uitoa(exp)}
		}
		if m.size >= m.budget {
			return nil, m.overflowError(src)
		}
		if _, derr := st.tracker.NotePending(msg); derr != nil {
			return nil, derr
		}
		st.backlog[msg.Seq] = msg
		m.size++
		st.observeTime(msg.Time)
		if msg.Seq > st.watermark {
			st.watermark = msg.Seq
		}
		if !st.isolated {
			st.isolated = true
			// Any already-queued healthy messages are now part of the
			// withheld interval until the gap heals.
			st.held = append(st.ready, st.held...)
			st.ready = nil
		}
		return m.release()
	}

	// OutcomeAccept: contiguous in-order record. Reserve capacity first.
	if m.size >= m.budget {
		return nil, m.overflowError(src)
	}
	if terr := st.checkTime(msg); terr != nil {
		return nil, terr
	}
	if aerr := st.tracker.Advance(msg); aerr != nil {
		return nil, aerr
	}
	st.markTime(msg)
	if msg.Seq > st.watermark {
		st.watermark = msg.Seq
	}
	if st.isolated {
		st.held = append(st.held, msg)
	} else {
		st.ready = append(st.ready, msg)
	}
	m.size++
	st.observeTime(msg.Time)
	return m.release()
}

// Isolated reports whether a source is currently isolated by an open gap.
func (m *Merger) Isolated(src types.Source) bool { return m.state(src).isolated }

// Drain returns everything currently releasable without new input.
func (m *Merger) Drain() []types.Message { out, _ := m.release(); return out }

// CloseSource declares no more messages will arrive for one source, releasing
// its hold on the global time barrier.
func (m *Merger) CloseSource(src types.Source) { m.state(src).closed = true }

// CloseAll marks every observed source complete (used at end of full replay).
func (m *Merger) CloseAll() {
	for _, src := range m.Sources() {
		m.srcs[src].closed = true
	}
}

// FlushAll marks sources complete and releases everything still buffered that
// can be published (isolated sources with open gaps stay withheld).
func (m *Merger) FlushAll() []types.Message {
	m.CloseAll()
	out, _ := m.release()
	return out
}

// ReplayAll deterministically replays a complete, known message set.
//
// It sorts into canonical (time, venue, partition, seq) order and feeds each
// record through the exact same validation, sequence-frontier, gap-isolation
// and merge state machine as streaming Push, only without the bounded window
// and buffer budget (the full input is already in memory). Sources with open
// gaps stay isolated and their messages never reach the output, exactly as in
// streaming operation.
func ReplayAll(msgs []types.Message) ([]types.Message, []*types.Error) {
	mg := New(int(^uint(0)>>1), len(msgs)+1)
	sorted := append([]types.Message(nil), msgs...)
	sortCanonical(sorted)

	srcSet := map[types.Source]bool{}
	for _, msg := range sorted {
		srcSet[msg.Source()] = true
	}
	srcs := make([]types.Source, 0, len(srcSet))
	for s := range srcSet {
		srcs = append(srcs, s)
	}
	sortSources(srcs)
	mg.RegisterSources(srcs...)

	var published []types.Message
	var errs []*types.Error
	for _, msg := range sorted {
		out, err := mg.Push(msg)
		published = append(published, out...)
		if te, ok := err.(*types.Error); ok && te != nil {
			errs = append(errs, te)
		}
	}
	published = append(published, mg.FlushAll()...)
	return published, errs
}

// Diagnostic returns the current backpressure picture without mutation.
func (m *Merger) Diagnostic() OverflowDiagnostic {
	d := OverflowDiagnostic{Buffered: m.size, Budget: m.budget}
	for _, src := range m.Sources() {
		st := m.srcs[src]
		if st.isolated {
			d.MinGap = append(d.MinGap, GapInfo{
				Source: src, MinGapSeq: st.tracker.Expected(src)})
		}
		d.Watermark = append(d.Watermark, WatermarkInfo{
			Source:       src,
			Watermark:    st.watermark,
			NextExpected: st.tracker.Expected(src),
		})
	}
	return d
}

// Sources lists every observed source in stable (venue, partition) order.
func (m *Merger) Sources() []types.Source {
	srcs := make([]types.Source, 0, len(m.srcs))
	for src := range m.srcs {
		srcs = append(srcs, src)
	}
	sortSources(srcs)
	return srcs
}

// Expected returns the next sequence number expected from a source.
func (m *Merger) Expected(src types.Source) uint64 {
	return m.state(src).tracker.Expected(src)
}

func (m *Merger) state(src types.Source) *srcState {
	st := m.srcs[src]
	if st == nil {
		st = &srcState{
			tracker: partition.NewTracker(),
			backlog: map[uint64]types.Message{},
		}
		m.srcs[src] = st
	}
	return st
}

// checkTime enforces non-decreasing logical time along a source's sequence.
// This is what makes per-seq release equal to per-time release and lets the
// global k-way time merge be computed from each source's frontier head.
func (st *srcState) checkTime(msg types.Message) *types.Error {
	if st.hasTime && msg.Time < st.lastTime {
		return &types.Error{Kind: types.KindInvalidMessage,
			Source: msg.Source(),
			Message: "logical time regression at seq " + uitoa(msg.Seq) +
				": " + itoa64(msg.Time) + " < " + itoa64(st.lastTime)}
	}
	return nil
}

func (st *srcState) markTime(msg types.Message) {
	st.hasTime = true
	st.lastTime = msg.Time
}

func (st *srcState) observeTime(t int64) {
	if !st.hasObsT || t > st.maxObsT {
		st.hasObsT = true
		st.maxObsT = t
	}
}

// queuedDigest reports the digest of a seq currently sitting in the ready or
// held (frontier-advanced but unpublished) queues.
func (st *srcState) queuedDigest(seq uint64) (uint64, bool) {
	for _, m := range st.ready {
		if m.Seq == seq {
			return m.Digest(), true
		}
	}
	for _, m := range st.held {
		if m.Seq == seq {
			return m.Digest(), true
		}
	}
	return 0, false
}

// RegisterSources pre-declares every source that will participate. A
// registered source with no frontier message yet holds the global barrier at
// -inf, so a source cannot publish ahead of another source that simply has
// not been read yet. Pre-register all partition files before streaming.
func (m *Merger) RegisterSources(srcs ...types.Source) {
	for _, src := range srcs {
		m.state(src)
	}
}

// release advances contiguous backlog prefixes and then globally merges
// release advances contiguous backlog prefixes, flushes healed gaps, then
// k-way merges healthy ready queues in canonical order.
func (m *Merger) release() ([]types.Message, error) {
	// 1) Move contiguous backlog messages across each frontier.
	for _, src := range m.Sources() {
		st := m.srcs[src]
		for {
			exp := st.tracker.Expected(src)
			msg, ok := st.backlog[exp]
			if !ok {
				break
			}
			if terr := st.checkTime(msg); terr != nil {
				return nil, terr
			}
			if aerr := st.tracker.Advance(msg); aerr != nil {
				st.isolated = true
				break
			}
			st.markTime(msg)
			delete(st.backlog, exp)
			if st.isolated {
				st.held = append(st.held, msg)
			} else {
				st.ready = append(st.ready, msg)
			}
		}
		if st.isolated && st.tracker.Expected(src) > st.watermark {
			st.isolated = false
			st.ready = append(st.ready, st.held...)
			st.held = nil
		}
		// Retain applied digests across the reorder window plus one so that a
		// late identical duplicate within the window is recognized rather than
		// misread as a rollback.
		keep := st.tracker.Expected(src)
		if m.window > 0 && keep >= m.window+1 {
			keep -= m.window + 1
		} else {
			keep = 1
		}
		st.tracker.Prune(src, keep)
	}

	// 2) Global watermark merge. Each source's logical time is non-decreasing
	// along its sequence, so once a source has accepted a message at time W
	// it can never later supply a message with an earlier logical time. The
	// global safe time is therefore the minimum max-accepted-time across all
	// open sources; an open source that has accepted nothing yet pins it to
	// -inf. Closed sources impose no bound. Healthy ready messages with time
	// <= watermark are canonical-minimum published; isolated sources are
	// skipped (their data only enters ready when the gap heals).
	watermark := int64(maxInt64)
	for _, src := range m.Sources() {
		st := m.srcs[src]
		if st.closed {
			continue
		}
		var bound int64
		if st.isolated {
			// Gap open: the missing seq's time is unknown but, by source
			// monotonicity, at least the time of the last frontier message.
			// That time is the held front (if any) else the last advance.
			if len(st.held) > 0 {
				bound = st.held[0].Time
			} else if st.hasTime {
				bound = st.lastTime
			} else {
				watermark = minInt64
				break
			}
		} else {
			if !st.hasObsT {
				watermark = minInt64
				break
			}
			bound = st.maxObsT
		}
		if bound < watermark {
			watermark = bound
		}
	}

	// Publish healthy ready messages with Time <= watermark, choosing the
	// canonical minimum across sources each round.
	var visible []types.Message
	for {
		var best *types.Message
		for _, src := range m.Sources() {
			st := m.srcs[src]
			if st.isolated {
				continue
			}
			if len(st.ready) == 0 {
				continue
			}
			head := st.ready[0]
			if head.Time > watermark {
				continue
			}
			if best == nil || less(head, *best) {
				h := head
				best = &h
			}
		}
		if best == nil {
			break
		}
		win := *best
		st := m.srcs[win.Source()]
		st.ready = st.ready[1:]
		m.size--
		visible = append(visible, win)
	}
	return visible, nil
}

// less compares canonical publication order (time, venue, partition, seq).
func less(a, b types.Message) bool {
	if a.Time != b.Time {
		return a.Time < b.Time
	}
	if a.Venue != b.Venue {
		return a.Venue < b.Venue
	}
	if a.Partition != b.Partition {
		return a.Partition < b.Partition
	}
	return a.Seq < b.Seq
}

func (m *Merger) overflowError(src types.Source) error {
	err := &types.Error{Kind: types.KindBufferOverflow, Source: src,
		Message: "buffer budget " + itoa(m.budget) +
			" exhausted; oldest messages are retained, not dropped"}
	return &OverflowError{Err: err, Diag: m.Diagnostic()}
}

// OverflowError attaches the minimum-gap/watermark diagnostic.
type OverflowError struct {
	Err  *types.Error
	Diag OverflowDiagnostic
}

func (e *OverflowError) Error() string { return e.Err.Error() }
func (e *OverflowError) Unwrap() error { return e.Err }

// DiagnosticOf extracts the backpressure diagnostic if present.
func DiagnosticOf(err error) (OverflowDiagnostic, bool) {
	if oe, ok := err.(*OverflowError); ok {
		return oe.Diag, true
	}
	return OverflowDiagnostic{}, false
}

func sortSources(srcs []types.Source) {
	sort.Slice(srcs, func(i, j int) bool {
		if srcs[i].Venue != srcs[j].Venue {
			return srcs[i].Venue < srcs[j].Venue
		}
		return srcs[i].Partition < srcs[j].Partition
	})
}

func sortCanonical(msgs []types.Message) {
	sort.SliceStable(msgs, func(i, j int) bool {
		a, b := msgs[i], msgs[j]
		if a.Time != b.Time {
			return a.Time < b.Time
		}
		if a.Venue != b.Venue {
			return a.Venue < b.Venue
		}
		if a.Partition != b.Partition {
			return a.Partition < b.Partition
		}
		return a.Seq < b.Seq
	})
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

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [24]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}

func itoa64(v int64) string { return itoa(int(v)) }
