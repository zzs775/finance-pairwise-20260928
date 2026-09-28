// Package oracle implements the naive reference semantics: sort the entire
// message set into canonical order, then replay it from an empty state with
// no reorder window and an unbounded buffer.
//
// Because the streaming engine releases exactly the same messages in exactly
// canonical order (isolating sources across gaps and withholding their data),
// the oracle's event byte stream equals the engine's canonical output for any
// interleaving of the same input multiset.
package oracle

import (
	"bytes"
	"encoding/json"
	"sort"

	"marketmerge/internal/engine"
	"marketmerge/internal/types"
)

// Rejection records one message the canonical replay could not accept.
type Rejection struct {
	Venue     string          `json:"venue"`
	Partition int             `json:"partition"`
	Seq       uint64          `json:"seq"`
	Kind      types.ErrorKind `json:"kind"`
	Message   string          `json:"message"`
}

// Report is the oracle's canonical JSON output.
type Report struct {
	Events     []engine.Event `json:"events"`
	Rejections []Rejection    `json:"rejections"`
}

// Canonical ordering: logical time, then stable venue, partition, seq.
func sortAll(msgs []types.Message) {
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

// Oracle rebuilds canonical state from a complete, sorted message set.
type Oracle struct{}

// New creates an Oracle.
func New() *Oracle { return &Oracle{} }

// Rebuild sorts a copy of all messages and replays them from empty state.
// Classified rejections (invalid records, rollbacks, duplicates, invalid
// cancels/corrections) are captured rather than aborting the replay.
func (o *Oracle) Rebuild(msgs []types.Message) Report {
	sorted := append([]types.Message(nil), msgs...)
	sortAll(sorted)

	// The oracle accepts any out-of-order distance and buffers everything,
	// pre-registers every source, processes strictly in canonical order and
	// isolates sources across genuine sequence gaps.
	eng := engine.New(int(^uint(0)>>1), len(sorted)+1)
	srcs := sourceSet(sorted)
	eng.RegisterSources(srcs...)
	rep := Report{}
	for _, m := range sorted {
		evs, err := eng.Ingest(m)
		rep.Events = append(rep.Events, evs...)
		if err != nil {
			collect(&rep, m, err)
		}
	}
	drained, derr := eng.Drain()
	rep.Events = append(rep.Events, drained...)
	if derr != nil {
		collect(&rep, types.Message{}, derr)
	}
	return rep
}

func sourceSet(msgs []types.Message) []types.Source {
	seen := map[types.Source]bool{}
	var out []types.Source
	for _, m := range msgs {
		s := m.Source()
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Venue != out[j].Venue {
			return out[i].Venue < out[j].Venue
		}
		return out[i].Partition < out[j].Partition
	})
	return out
}

// Render emits canonical, deterministic JSON for byte-level comparison.
func (r Report) Render() []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(r)
	return buf.Bytes()
}

// EventsJSON renders just the event stream canonically.
func EventsJSON(evs []engine.Event) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(evs)
	return buf.Bytes()
}

func collect(rep *Report, m types.Message, err error) {
	// Unwrap joined apply errors individually.
	for _, part := range splitJoined(err) {
		kind, msg := classify(part)
		rep.Rejections = append(rep.Rejections, Rejection{
			Venue:     m.Venue,
			Partition: m.Partition,
			Seq:       m.Seq,
			Kind:      kind,
			Message:   msg,
		})
	}
}

func splitJoined(err error) []error {
	var out []error
	// engine.joinApplyErrs joins with "; "; types.Error messages never embed
	// that separator as part of their own text.
	if te, ok := err.(*types.Error); ok {
		return []error{te}
	}
	text := err.Error()
	for len(text) > 0 {
		i := indexSemicolon(text)
		var piece string
		if i < 0 {
			piece, text = text, ""
		} else {
			piece, text = text[:i], text[i+2:]
		}
		out = append(out, plainError(piece))
	}
	return out
}

type plainError string

func (p plainError) Error() string { return string(p) }

func indexSemicolon(s string) int {
	for i := 0; i+1 < len(s); i++ {
		if s[i] == ';' && s[i+1] == ' ' {
			return i
		}
	}
	return -1
}

func classify(err error) (types.ErrorKind, string) {
	if te, ok := err.(*types.Error); ok {
		return te.Kind, te.Message
	}
	for _, kind := range []types.ErrorKind{
		types.KindDuplicate, types.KindInvalidCancel, types.KindNegative,
		types.KindSeqRollback, types.KindSeqOverflow, types.KindGap,
		types.KindBufferOverflow, types.KindCorruptCheckpt,
		types.KindInvalidMessage,
	} {
		prefix := string(kind) + ": "
		if hasPrefix(err.Error(), prefix) {
			return kind, err.Error()[len(prefix):]
		}
	}
	return types.KindInvalidMessage, err.Error()
}

func hasPrefix(s, p string) bool {
	return len(s) >= len(p) && s[:len(p)] == p
}
