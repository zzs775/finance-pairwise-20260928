// Package engine wires validation, bounded merging, gap isolation, the NBBO
// book and deterministic checkpoints into one consolidated market engine.
//
// Canonical event order is (logical time, venue, partition, seq); the merger
// guarantees this order, so concurrent readers feeding Push in different
// interleavings produce byte-identical canonical output.
package engine

import (
	"errors"
	"strings"

	"marketmerge/internal/checkpoint"
	"marketmerge/internal/merger"
	"marketmerge/internal/nbbo"
	"marketmerge/internal/types"
)

// Event kinds.
const (
	KindNBB   = "nbb"
	KindNBO   = "nbo"
	KindTrade = "trade"
)

// Level is one published best-price level.
type Level struct {
	Venue     string `json:"venue"`
	Partition int    `json:"partition"`
	Price     int64  `json:"price"`
	Qty       int64  `json:"qty"`
}

// Event is one canonical output record.
type Event struct {
	Time      int64  `json:"time"`
	Venue     string `json:"venue"`
	Partition int    `json:"partition"`
	Seq       uint64 `json:"seq"`
	Symbol    string `json:"symbol"`
	Kind      string `json:"kind"`
	Bid       *Level `json:"bid,omitempty"`
	Ask       *Level `json:"ask,omitempty"`
	// Trade summary fields (zero unless Kind == KindTrade).
	TradePrice int64 `json:"trade_price,omitempty"`
	TradeQty   int64 `json:"trade_qty,omitempty"`
	Volume     int64 `json:"volume,omitempty"`
	Notional   int64 `json:"notional,omitempty"`
}

// Engine is the deterministic consolidated market-data engine.
type Engine struct {
	merger    *merger.Merger
	book      *nbbo.Book
	events    []Event
	window    int
	budget    int
	applyErrs []*types.Error
}

// New creates an Engine with the given reorder window and buffer budget.
func New(window, budget int) *Engine {
	return &Engine{
		merger: merger.New(window, budget),
		book:   nbbo.NewBook(),
		window: window,
		budget: budget,
	}
}

// RegisterSources pre-declares every source so the global merge correctly
// waits for sources that simply have not been read yet.
func (e *Engine) RegisterSources(srcs ...types.Source) {
	e.merger.RegisterSources(srcs...)
}

// Ingest feeds one raw message. Validation/sequence errors are classified and
// returned; returned events are canonical and already in publication order.
func (e *Engine) Ingest(m types.Message) (events []Event, err error) {
	released, err := e.merger.Push(m)
	if err != nil {
		return nil, err
	}
	return e.applyReleased(released), e.joinApplyErrs()
}

// Drain applies anything the merger can currently publish.
func (e *Engine) Drain() ([]Event, error) {
	out := e.applyReleased(e.merger.FlushAll())
	return out, e.joinApplyErrs()
}

func (e *Engine) applyReleased(msgs []types.Message) []Event {
	var out []Event
	for _, m := range msgs {
		if e.merger.Isolated(m.Source()) {
			// Defensive: the merger never releases isolated messages.
			continue
		}
		res, applyErr := e.book.Apply(m)
		if applyErr != nil {
			// Referential violations (cancel/correct of a non-live version)
			// are classified errors; the offending message is not applied.
			var te *types.Error
			if errors.As(applyErr, &te) {
				e.applyErrs = append(e.applyErrs, te)
			}
			continue
		}
		evs := e.buildEvents(m, res)
		e.events = append(e.events, evs...)
		out = append(out, evs...)
	}
	return out
}

func (e *Engine) buildEvents(m types.Message, res nbbo.Result) []Event {
	base := Event{Time: m.Time, Venue: m.Venue, Partition: m.Partition,
		Seq: m.Seq, Symbol: m.Symbol}
	if m.Action == types.ActionTrade && res.Trade != nil {
		ev := base
		ev.Kind = KindTrade
		ev.TradePrice = m.Price
		ev.TradeQty = m.Qty
		ev.Volume = res.Trade.Volume
		ev.Notional = res.Trade.Notional
		return []Event{ev}
	}
	// Emit post-change NBB and NBO snapshots for the symbol whenever the
	// message could have affected either side. Quote/cancel/correct each
	// emits a snapshot pair when the top actually changed.
	bid, ask := e.book.Best(m.Symbol)
	var out []Event
	if res.NBBChanged {
		ev := base
		ev.Kind = KindNBB
		if !bid.Empty() {
			lvl := Level{bid.Venue, bid.Partition, bid.Price, bid.Qty}
			ev.Bid = &lvl
		}
		out = append(out, ev)
	}
	if res.NBOChanged {
		ev := base
		ev.Kind = KindNBO
		if !ask.Empty() {
			lvl := Level{ask.Venue, ask.Partition, ask.Price, ask.Qty}
			ev.Ask = &lvl
		}
		out = append(out, ev)
	}
	return out
}

// Events returns the cumulative canonical output in publication order.
func (e *Engine) Events() []Event { return e.events }

// Book exposes the book for direct oracle/test inspection.
func (e *Engine) Book() *nbbo.Book { return e.book }

// LastApplyError returns and clears the most recent referential apply error.
func (e *Engine) LastApplyError() error { return e.joinApplyErrs() }

func (e *Engine) joinApplyErrs() error {
	if len(e.applyErrs) == 0 {
		return nil
	}
	msgs := make([]string, len(e.applyErrs))
	for i, te := range e.applyErrs {
		msgs[i] = te.Error()
	}
	err := errors.New(strings.Join(msgs, "; "))
	e.applyErrs = nil
	return err
}

// Isolated reports a source's current isolation status.
func (e *Engine) Isolated(src types.Source) bool { return e.merger.Isolated(src) }

// OverflowDiag returns the current backpressure diagnostic.
func (e *Engine) OverflowDiag() merger.OverflowDiagnostic {
	return e.merger.Diagnostic()
}

// StateDTO is the full serializable engine state.
type StateDTO struct {
	Window int                `json:"window"`
	Budget int                `json:"budget"`
	Merger merger.SnapshotDTO `json:"merger"`
	Book   nbbo.SnapshotDTO   `json:"book"`
	Events []Event            `json:"events"`
}

// Checkpoint serializes full engine state into a self-verifying frame.
func (e *Engine) Checkpoint() ([]byte, error) {
	dto := StateDTO{
		Window: e.window,
		Budget: e.budget,
		Merger: e.merger.MarshalState(),
		Book:   e.book.MarshalState(),
		Events: append([]Event(nil), e.events...),
	}
	return checkpoint.Encode(dto)
}

// Restore loads engine state from a checkpoint frame.
func (e *Engine) Restore(frame []byte) error {
	var dto StateDTO
	if err := checkpoint.Decode(frame, &dto); err != nil {
		return err
	}
	e.window = dto.Window
	e.budget = dto.Budget
	e.merger = merger.New(dto.Window, dto.Budget)
	e.merger.UnmarshalState(dto.Merger)
	e.book = nbbo.NewBook()
	e.book.UnmarshalState(dto.Book)
	e.events = append([]Event(nil), dto.Events...)
	return nil
}

// AsOverflow extracts the merger backpressure diagnostic.
func AsOverflow(err error) (merger.OverflowDiagnostic, bool) {
	return merger.DiagnosticOf(err)
}

// IsClassified reports whether err carries one of the engine classifications.
func IsClassified(err error, kind types.ErrorKind) bool {
	var te *types.Error
	if errors.As(err, &te) {
		return te.Kind == kind
	}
	return false
}
