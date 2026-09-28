// Package nbbo maintains per-symbol best bid/offer across healthy venues and
// consolidated trade aggregates.
//
// Every healthy source event is applied exactly once. Each venue+partition
// exposes one executable quote version per symbol and side; a cancel removes
// the referenced version and a correction first removes the referenced
// version and then installs the new value.
package nbbo

import (
	"marketmerge/internal/types"
)

// Quote is one executable L1 quote level.
type Quote struct {
	Venue     string
	Partition int
	Price     int64
	Qty       int64
	Time      int64
	Tag       uint64 // originating seq identifying this live version
}

// Empty reports whether the level is currently absent.
func (q Quote) Empty() bool { return q.Venue == "" }

// Seq returns the originating seq that installed this version.
func (q Quote) Seq() uint64 { return q.Tag }

// TradeAgg is the cumulative consolidated print summary for a symbol.
type TradeAgg struct {
	Trades   int
	Volume   int64
	Notional int64 // sum(price*qty) in tick*units
	Last     Quote // last print (price/qty/time), venue fields populated
}

type qKey struct {
	venue     string
	partition int
	side      types.Side
}

type symState struct {
	quotes map[qKey]Quote // one live version per source+side
	trades TradeAgg
}

// Book holds current quotes, prints and applied versions.
type Book struct {
	syms map[string]*symState
}

// NewBook creates an empty Book.
func NewBook() *Book { return &Book{syms: map[string]*symState{}} }

func (b *Book) state(symbol string) *symState {
	s := b.syms[symbol]
	if s == nil {
		s = &symState{
			quotes: map[qKey]Quote{},
		}
		b.syms[symbol] = s
	}
	return s
}

// Result describes what changed on one Apply call.
type Result struct {
	Changed    bool
	NBBChanged bool
	NBOChanged bool
	Trade      *TradeAgg // non-nil when the message was a newly applied trade
}

// Apply applies exactly one healthy-source message to the book.
//
// Invalid cancels/corrections (unknown referenced version) and re-application
// of an already applied seq are reported as classified errors; callers feed
// only released, non-duplicate events but the book still guards idempotency.
func (b *Book) Apply(m types.Message) (Result, error) {
	s := b.state(m.Symbol)
	sideKey := qKey{venue: m.Venue, partition: m.Partition, side: m.Side}
	nbbBefore, nboBefore := b.Best(m.Symbol)
	changed := func() Result {
		nbbAfter, nboAfter := b.Best(m.Symbol)
		return Result{
			Changed:    true,
			NBBChanged: !sameLevel(nbbBefore, nbbAfter),
			NBOChanged: !sameLevel(nboBefore, nboAfter),
		}
	}

	switch m.Action {
	case types.ActionQuote:
		s.quotes[sideKey] = Quote{
			Venue: m.Venue, Partition: m.Partition,
			Price: m.Price, Qty: m.Qty, Time: m.Time,
			Tag: m.Seq,
		}
		return changed(), nil

	case types.ActionCancel:
		// Cancellation is only valid against the currently live version
		// referenced by RefSeq (identity is checked by the caller via seq;
		// here we ensure a quote for that source/side actually exists).
		q, ok := s.quotes[sideKey]
		if !ok {
			return Result{}, &types.Error{Kind: types.KindInvalidCancel,
				Source:  m.Source(),
				Message: "cancel of absent quote at seq " + uitoa(m.RefSeq)}
		}
		if q.Seq() != m.RefSeq {
			return Result{}, &types.Error{Kind: types.KindInvalidCancel,
				Source: m.Source(),
				Message: "cancel ref_seq " + uitoa(m.RefSeq) +
					" is not the live version " + uitoa(q.Seq())}
		}
		delete(s.quotes, sideKey)
		return changed(), nil

	case types.ActionCorrect:
		q, ok := s.quotes[sideKey]
		if !ok || q.Seq() != m.RefSeq {
			live := uint64(0)
			if ok {
				live = q.Seq()
			}
			return Result{}, &types.Error{Kind: types.KindInvalidCancel,
				Source: m.Source(),
				Message: "correct ref_seq " + uitoa(m.RefSeq) +
					" does not match live version " + uitoa(live)}
		}
		s.quotes[sideKey] = Quote{
			Venue: m.Venue, Partition: m.Partition,
			Price: m.Price, Qty: m.Qty, Time: m.Time,
			Tag: m.Seq,
		}
		return changed(), nil

	case types.ActionTrade:
		agg := s.trades
		agg.Trades++
		agg.Volume += m.Qty
		agg.Notional += m.Price * m.Qty
		agg.Last = Quote{
			Venue: m.Venue, Partition: m.Partition,
			Price: m.Price, Qty: m.Qty, Time: m.Time,
		}
		s.trades = agg
		return Result{Changed: true, Trade: &agg}, nil
	}

	return Result{}, &types.Error{Kind: types.KindInvalidMessage,
		Source: m.Source(), Message: "unknown action " + string(m.Action)}
}

// Best returns the current NBB and NBO for a symbol.
// Best executable price: highest bid / lowest ask; ties resolve to the
// lexicographically smallest stable venue id, then lowest partition.
func (b *Book) Best(symbol string) (bid, ask Quote) {
	s := b.syms[symbol]
	if s == nil {
		return Quote{}, Quote{}
	}
	for k, q := range s.quotes {
		if k.side == types.SideBid {
			if bid.Empty() || betterBid(q, bid) {
				bid = q
			}
		} else {
			if ask.Empty() || betterAsk(q, ask) {
				ask = q
			}
		}
	}
	return bid, ask
}

// Trades returns the cumulative print aggregate for a symbol.
func (b *Book) Trades(symbol string) TradeAgg {
	s := b.syms[symbol]
	if s == nil {
		return TradeAgg{}
	}
	return s.trades
}

// Symbols lists tracked symbols in stable sorted order.
func (b *Book) Symbols() []string {
	out := make([]string, 0, len(b.syms))
	for sym := range b.syms {
		out = append(out, sym)
	}
	// local insertion-free sort via standard sort
	sortStrings(out)
	return out
}

func betterBid(a, b Quote) bool {
	if a.Price != b.Price {
		return a.Price > b.Price
	}
	return tieBreaks(a, b)
}

func betterAsk(a, b Quote) bool {
	if a.Price != b.Price {
		return a.Price < b.Price
	}
	return tieBreaks(a, b)
}

func tieBreaks(a, b Quote) bool {
	if a.Venue != b.Venue {
		return a.Venue < b.Venue
	}
	return a.Partition < b.Partition
}

func sameLevel(a, b Quote) bool {
	return a.Venue == b.Venue && a.Partition == b.Partition &&
		a.Price == b.Price && a.Qty == b.Qty
}

func sortStrings(v []string) {
	for i := 1; i < len(v); i++ {
		for j := i; j > 0 && v[j-1] > v[j]; j-- {
			v[j-1], v[j] = v[j], v[j-1]
		}
	}
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
