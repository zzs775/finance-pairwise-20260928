// Package synth generates deterministic, purely local synthetic market data.
//
// All randomness comes from a seeded linear congruential generator, so a
// (Config, seed) pair always yields the identical byte sequence. No external
// services, clocks or randomness sources are used.
package synth

import "marketmerge/internal/types"

// DefaultVenueIDs is the four-venue set used by the test suite.
var DefaultVenueIDs = []string{"NYSE", "ARCA", "EDGX", "BATS"}

// Config describes a synthetic dataset.
type Config struct {
	Venues     int // number of venues (<= len(DefaultVenueIDs))
	Partitions int // quote partitions per venue; trade stream is extra
	Messages   int // baseline messages per partition
	Seed       int64
	// Gaps, when > 0, asks Generate to hold back and then refill some seqs,
	// exercising isolation and backfill. Duplicates re-deliver identical or
	// conflicting copies; Corrections emits correct/cancel pairs.
	Gaps        bool
	Duplicates  bool
	Corrections bool
	Conflicts   bool // include a conflicting duplicate (negative case)
	Shuffle     bool // deliver out of canonical order (still valid seqs)
}

// Dataset is a generated message set plus metadata about its scenarios.
type Dataset struct {
	Messages []types.Message
	// RefQuotes lists quote seqs that are later corrected or cancelled.
	RefQuotes []types.Message
	// Sources present, in stable order.
	Sources []types.Source
}

type rng struct{ state uint64 }

func newRNG(seed int64) *rng {
	s := uint64(seed)
	if s == 0 {
		s = 0x9E3779B97F4A7C15
	}
	return &rng{state: s}
}

// u63 returns a pseudo-random uint64 (Knuth MMIX constants).
func (r *rng) u64() uint64 {
	r.state = r.state*6364136223846793005 + 1442695040888963407
	return r.state
}

func (r *rng) intn(n int) int {
	if n <= 0 {
		return 0
	}
	return int(r.u64() % uint64(n))
}

const symbols = 4

// Generate produces a deterministic synthetic message set in DELIVERY order:
// source-ordered when Shuffle is false, otherwise a deterministic permutation
// that stays within a modest reorder distance.
func Generate(cfg Config) Dataset {
	if cfg.Venues <= 0 {
		cfg.Venues = 4
	}
	if cfg.Venues > len(DefaultVenueIDs) {
		cfg.Venues = len(DefaultVenueIDs)
	}
	if cfg.Partitions <= 0 {
		cfg.Partitions = 2
	}
	if cfg.Messages <= 0 {
		cfg.Messages = 12
	}
	r := newRNG(cfg.Seed)

	venues := DefaultVenueIDs[:cfg.Venues]
	var ds Dataset
	type stream struct {
		src  types.Source
		msgs []types.Message
	}
	var streams []stream

	symFor := func(i int) string {
		return symName('A' + r.intn(symbols))
	}

	for _, venue := range venues {
		// Quote partitions.
		for p := 0; p < cfg.Partitions; p++ {
			src := types.Source{Venue: venue, Partition: p}
			ds.Sources = append(ds.Sources, src)
			st := stream{src: src}
			seq := uint64(1)
			var ordered []types.Message
			for i := 0; i < cfg.Messages; i++ {
				sym := symFor(i)
				side := pickSide(r, i)
				price := int64(100 + r.intn(50))
				qty := int64(10 * (1 + r.intn(20)))
				t := int64(1 + i)
				m := types.Message{
					Venue: venue, Partition: p, Seq: seq, Time: t,
					Symbol: sym, Action: types.ActionQuote, Side: side,
					Price: price, Qty: qty,
				}
				if cfg.Corrections && i == 2 && seq > 0 {
					ds.RefQuotes = append(ds.RefQuotes, m)
					corr := m
					corr.Seq = seq + 1
					corr.Time = t + 1
					corr.Action = types.ActionCorrect
					corr.RefSeq = seq
					corr.Price = m.Price + 1
					corr.Qty = m.Qty + 5
					seq += 2
					i++
					ordered = append(ordered, m, corr)
					continue
				}
				seq++
				ordered = append(ordered, m)
			}
			st.msgs = orderDelivery(r, ordered, cfg)
			streams = append(streams, st)
		}
		// Dedicated trade partition (index cfg.Partitions).
		tradeSrc := types.Source{Venue: venue, Partition: cfg.Partitions}
		ds.Sources = append(ds.Sources, tradeSrc)
		ts := stream{src: tradeSrc}
		for i := 0; i < cfg.Messages/2; i++ {
			ts.msgs = append(ts.msgs, types.Message{
				Venue: venue, Partition: cfg.Partitions, Seq: uint64(i + 1),
				Time: int64(2 + 2*i), Symbol: symFor(i),
				Action: types.ActionTrade,
				Price:  int64(100 + r.intn(50)),
				Qty:    int64(5 * (1 + r.intn(10))),
			})
		}
		streams = append(streams, ts)
	}

	for _, st := range streams {
		msgs := st.msgs
		isQuote := st.src.Partition < cfg.Partitions
		// After a bounded block shuffle the original seq-1 record is still
		// at index 0 (bounded displacement never moves the first element),
		// so inserting the re-delivery at index 1 keeps it adjacent and
		// classifies it as a duplicate, not a rollback. Only quote partitions
		// receive duplicate scenarios.
		if cfg.Duplicates && isQuote && len(msgs) > 1 {
			msgs = insertCopy(msgs, 1, msgs[0])
		}
		ds.Messages = append(ds.Messages, msgs...)
	}
	// Conflicting duplicate on the very first stream: same seq, changed price.
	if cfg.Conflicts {
		bad := ds.Messages[0]
		if bad.Action != types.ActionQuote {
			return ds
		}
		bad.Price++
		ds.Messages = insertCopy(ds.Messages, 1, bad)
	}
	return ds
}

func pickSide(r *rng, i int) types.Side {
	if r.intn(2) == 0 {
		return types.SideBid
	}
	return types.SideAsk
}

func symName(b int) string {
	if b < 'A' {
		b = 'A'
	}
	return "SYM" + string(rune(b))
}

// insertCopy inserts cp at index at into msgs, returning a new slice.
func insertCopy(msgs []types.Message, at int, cp types.Message) []types.Message {
	out := make([]types.Message, 0, len(msgs)+1)
	out = append(out, msgs[:at]...)
	out = append(out, cp)
	out = append(out, msgs[at:]...)
	return out
}

// block is a delivery-atomic group: a quote and the correction that
// references it always move and arrive together.
type block struct{ msgs []types.Message }

// orderDelivery applies controlled perturbations: a bounded late backfill
// (gap) followed by a bounded block shuffle.
func orderDelivery(r *rng, msgs []types.Message, cfg Config) []types.Message {
	blocks := buildBlocks(msgs)
	if cfg.Gaps && len(blocks) > 6 {
		blocks = delayBlock(blocks)
	}
	if cfg.Shuffle {
		blocks = shuffleBlocks(r, blocks)
	}
	var out []types.Message
	for _, b := range blocks {
		out = append(out, b.msgs...)
	}
	return out
}

// buildBlocks glues each correction onto the block holding its referenced
// quote; all other messages become singleton blocks.
func buildBlocks(msgs []types.Message) []block {
	var blocks []block
	for _, m := range msgs {
		if m.Action == types.ActionCorrect && len(blocks) > 0 &&
			blocks[len(blocks)-1].msgs[0].Seq == m.RefSeq {
			blocks[len(blocks)-1].msgs = append(blocks[len(blocks)-1].msgs, m)
			continue
		}
		blocks = append(blocks, block{msgs: []types.Message{m}})
	}
	return blocks
}

// delayBlock moves one standalone quote block three positions later, opening a
// sequence gap that is healed when the delayed block arrives within window.
func delayBlock(blocks []block) []block {
	target := -1
	for i := range blocks {
		if len(blocks[i].msgs) == 1 && blocks[i].msgs[0].Action == types.ActionQuote {
			target = i
			if i >= 2 {
				break
			}
		}
	}
	if target < 0 || target+3 >= len(blocks) {
		return blocks
	}
	late := blocks[target]
	var out []block
	out = append(out, blocks[:target]...)
	out = append(out, blocks[target+1:target+4]...)
	out = append(out, late)
	out = append(out, blocks[target+4:]...)
	return out
}

// shuffleBlocks applies a seeded permutation where each block moves at most
// three positions and block 0 never moves (so the seq-1 block stays first and
// a duplicate inserted afterwards at index 1 remains adjacent). Forward
// passes with RNG-separated offsets avoid unbounded cascade displacement.
func shuffleBlocks(r *rng, blocks []block) []block {
	for pass := 0; pass < 2; pass++ {
		for i := 1; i+1 < len(blocks); i++ {
			if r.intn(2) == 0 {
				blocks[i], blocks[i+1] = blocks[i+1], blocks[i]
			}
		}
	}
	return blocks
}
