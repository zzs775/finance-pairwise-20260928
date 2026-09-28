package nbbo

import (
	"sort"

	"marketmerge/internal/types"
)

// QuoteDTO is one live quote version in a snapshot.
type QuoteDTO struct {
	Venue     string     `json:"venue"`
	Partition int        `json:"partition"`
	Side      types.Side `json:"side"`
	Price     int64      `json:"price"`
	Qty       int64      `json:"qty"`
	Time      int64      `json:"time"`
	Tag       uint64     `json:"tag"`
}

// TradeAggDTO is a symbol's cumulative print summary.
type TradeAggDTO struct {
	Trades   int      `json:"trades"`
	Volume   int64    `json:"volume"`
	Notional int64    `json:"notional"`
	Last     QuoteDTO `json:"last"`
}

// SymbolStateDTO serializes one symbol's full state.
type SymbolStateDTO struct {
	Symbol string      `json:"symbol"`
	Quotes []QuoteDTO  `json:"quotes"`
	Trades TradeAggDTO `json:"trades"`
}

// SnapshotDTO is the deterministic book snapshot.
type SnapshotDTO struct {
	Symbols []SymbolStateDTO `json:"symbols"`
}

// MarshalState serializes the book deterministically.
func (b *Book) MarshalState() SnapshotDTO {
	dto := SnapshotDTO{}
	for _, sym := range b.Symbols() {
		s := b.syms[sym]
		sd := SymbolStateDTO{Symbol: sym, Trades: aggToDTO(s.trades)}
		type liveQuote struct {
			k qKey
			q Quote
		}
		live := make([]liveQuote, 0, len(s.quotes))
		for k, q := range s.quotes {
			live = append(live, liveQuote{k, q})
		}
		sort.Slice(live, func(i, j int) bool {
			a, c := live[i].k, live[j].k
			if a.side != c.side {
				return a.side < c.side
			}
			if a.venue != c.venue {
				return a.venue < c.venue
			}
			return a.partition < c.partition
		})
		for _, item := range live {
			sd.Quotes = append(sd.Quotes, quoteToDTO(item.q, item.k.side))
		}
		dto.Symbols = append(dto.Symbols, sd)
	}
	return dto
}

// UnmarshalState restores book state from a snapshot.
func (b *Book) UnmarshalState(dto SnapshotDTO) {
	b.syms = map[string]*symState{}
	for _, sd := range dto.Symbols {
		s := &symState{quotes: map[qKey]Quote{}}
		for _, qd := range sd.Quotes {
			k := qKey{venue: qd.Venue, partition: qd.Partition, side: qd.Side}
			s.quotes[k] = quoteFromDTO(qd)
		}
		s.trades = aggFromDTO(sd.Trades)
		b.syms[sd.Symbol] = s
	}
}

func quoteToDTO(q Quote, side ...types.Side) QuoteDTO {
	var s types.Side
	if len(side) > 0 {
		s = side[0]
	}
	return QuoteDTO{Venue: q.Venue, Partition: q.Partition, Side: s,
		Price: q.Price, Qty: q.Qty, Time: q.Time, Tag: q.Tag}
}

func quoteFromDTO(qd QuoteDTO) Quote {
	return Quote{Venue: qd.Venue, Partition: qd.Partition,
		Price: qd.Price, Qty: qd.Qty, Time: qd.Time, Tag: qd.Tag}
}

func aggToDTO(a TradeAgg) TradeAggDTO {
	return TradeAggDTO{Trades: a.Trades, Volume: a.Volume,
		Notional: a.Notional, Last: quoteToDTO(a.Last)}
}

func aggFromDTO(a TradeAggDTO) TradeAgg {
	return TradeAgg{Trades: a.Trades, Volume: a.Volume,
		Notional: a.Notional, Last: quoteFromDTO(a.Last)}
}
