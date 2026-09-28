package merger

import (
	"sort"

	"marketmerge/internal/partition"
	"marketmerge/internal/types"
)

func trackerFromState(src types.Source, nextExpected uint64, digests []DigestDTO) *partition.Tracker {
	tr := partition.NewTracker()
	entries := make([]partition.DigestEntry, 0, len(digests))
	for _, d := range digests {
		entries = append(entries, partition.DigestEntry{Seq: d.Seq, Digest: d.Digest})
	}
	tr.RestoreState(src, nextExpected, entries)
	return tr
}

// SnapshotDTO is the deterministic JSON representation of Merger state.
type SnapshotDTO struct {
	Window  int              `json:"window"`
	Budget  int              `json:"budget"`
	Sources []SourceStateDTO `json:"sources"`
	Size    int              `json:"size"`
}

// SourceStateDTO is one source's serialized state.
type SourceStateDTO struct {
	Venue        string          `json:"venue"`
	Partition    int             `json:"partition"`
	NextExpected uint64          `json:"next_expected"`
	Watermark    uint64          `json:"watermark"`
	Isolated     bool            `json:"isolated"`
	Closed       bool            `json:"closed"`
	Applied      []DigestDTO     `json:"applied"`
	Backlog      []types.Message `json:"backlog"`
	Ready        []types.Message `json:"ready"`
	Held         []types.Message `json:"held"`
}

// DigestDTO records one applied seq and its payload digest.
type DigestDTO struct {
	Seq    uint64 `json:"seq"`
	Digest uint64 `json:"digest"`
}

// MarshalState serializes the merger deterministically.
func (m *Merger) MarshalState() SnapshotDTO {
	dto := SnapshotDTO{Window: int(m.window), Budget: m.budget, Size: m.size}
	for _, src := range m.Sources() {
		st := m.srcs[src]
		s := SourceStateDTO{
			Venue:        src.Venue,
			Partition:    src.Partition,
			NextExpected: st.tracker.Expected(src),
			Watermark:    st.watermark,
			Isolated:     st.isolated,
			Closed:       st.closed,
			Ready:        append([]types.Message(nil), st.ready...),
			Held:         append([]types.Message(nil), st.held...),
		}
		for _, e := range st.tracker.AppliedDigests(src) {
			s.Applied = append(s.Applied, DigestDTO{Seq: e.Seq, Digest: e.Digest})
		}
		seqs := make([]uint64, 0, len(st.backlog))
		for seq := range st.backlog {
			seqs = append(seqs, seq)
		}
		sort.Slice(seqs, func(i, j int) bool { return seqs[i] < seqs[j] })
		for _, seq := range seqs {
			s.Backlog = append(s.Backlog, st.backlog[seq])
		}
		dto.Sources = append(dto.Sources, s)
	}
	return dto
}

// UnmarshalState restores merger state from a DTO.
func (m *Merger) UnmarshalState(dto SnapshotDTO) {
	m.window = uint64(dto.Window)
	m.budget = dto.Budget
	m.srcs = map[types.Source]*srcState{}
	m.size = 0
	for _, s := range dto.Sources {
		src := types.Source{Venue: s.Venue, Partition: s.Partition}
		st := &srcState{
			tracker:   trackerFromState(src, s.NextExpected, s.Applied),
			backlog:   map[uint64]types.Message{},
			ready:     append([]types.Message(nil), s.Ready...),
			held:      append([]types.Message(nil), s.Held...),
			watermark: s.Watermark,
			isolated:  s.Isolated,
			closed:    s.Closed,
		}
		for _, msg := range s.Backlog {
			st.backlog[msg.Seq] = msg
		}
		m.size += len(st.backlog) + len(st.ready) + len(st.held)
		m.srcs[src] = st
	}
}
