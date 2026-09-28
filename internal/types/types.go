// Package types defines the wire-level message model and classified errors
// for the consolidated market-data engine.
package types

import (
	"encoding/json"
	"hash/fnv"
	"strconv"
)

// Action enumerates the message kinds carried by a venue partition stream.
type Action string

const (
	ActionQuote   Action = "quote"    // set/refresh executable quote
	ActionCancel  Action = "cancel"   // cancel the referenced quote version
	ActionTrade   Action = "trade"    // executed print
	ActionCorrect Action = "correct"  // correct a previously applied version
)

// Side is the quote/trade side.
type Side string

const (
	SideBid Side = "bid"
	SideAsk Side = "ask"
)

// Source identifies one ordered partition stream of one venue.
type Source struct {
	Venue     string `json:"venue"`
	Partition int    `json:"partition"`
}

// Message is one record from a local JSONL partition file.
// Price is expressed in integer tick units (no floating point).
type Message struct {
	Venue     string `json:"venue"`
	Partition int    `json:"partition"`
	Seq       uint64 `json:"seq"`
	Time      int64  `json:"time"`
	Symbol    string `json:"symbol"`
	Action    Action `json:"action"`
	Side      Side   `json:"side,omitempty"`
	Price     int64  `json:"price"`
	Qty       int64  `json:"qty"`
	RefSeq    uint64 `json:"ref_seq,omitempty"` // cancel/correct target version
}

// Source returns the ordered stream this message belongs to.
func (m Message) Source() Source {
	return Source{Venue: m.Venue, Partition: m.Partition}
}

// String renders a stable source identifier.
func (s Source) String() string {
	return s.Venue + "#" + strconv.Itoa(s.Partition)
}

// Validate checks field-level invariants independent of stream state.
func (m Message) Validate() *Error {
	src := m.Source()
	switch m.Action {
	case ActionQuote:
		if m.Side != SideBid && m.Side != SideAsk {
			return &Error{Kind: KindInvalidMessage, Source: src, Message: "quote requires side bid|ask"}
		}
		if m.Price <= 0 || m.Qty <= 0 {
			return &Error{Kind: KindNegative, Source: src, Message: "quote requires positive price and qty"}
		}
	case ActionTrade:
		if m.Price <= 0 || m.Qty <= 0 {
			return &Error{Kind: KindNegative, Source: src, Message: "trade requires positive price and qty"}
		}
	case ActionCorrect:
		if m.Side != SideBid && m.Side != SideAsk {
			return &Error{Kind: KindInvalidMessage, Source: src, Message: "correct requires side bid|ask"}
		}
		if m.Price <= 0 || m.Qty <= 0 {
			return &Error{Kind: KindNegative, Source: src, Message: "correct requires positive price and qty"}
		}
		if m.RefSeq == 0 {
			return &Error{Kind: KindInvalidCancel, Source: src, Message: "correct requires ref_seq"}
		}
	case ActionCancel:
		if m.RefSeq == 0 {
			return &Error{Kind: KindInvalidCancel, Source: src, Message: "cancel requires ref_seq"}
		}
	default:
		return &Error{Kind: KindInvalidMessage, Source: src, Message: "unknown action: " + string(m.Action)}
	}
	if m.Symbol == "" {
		return &Error{Kind: KindInvalidMessage, Source: src, Message: "empty symbol"}
	}
	if m.Price < 0 || m.Qty < 0 {
		return &Error{Kind: KindNegative, Source: src, Message: "negative price/qty"}
	}
	return nil
}

// Digest is a stable 64-bit fingerprint of the payload carried by a seq.
// Two messages with the same seq but different digests are a duplicate
// conflict; identical digests are benign re-delivery of the same event.
func (m Message) Digest() uint64 {
	// Encode only payload fields, never the routing identity (which is fixed
	// for a given source) or seq (the map key).
	payload := struct {
		Time   int64  `json:"t"`
		Symbol string `json:"s"`
		Action Action `json:"a"`
		Side   Side   `json:"d"`
		Price  int64  `json:"p"`
		Qty    int64  `json:"q"`
		RefSeq uint64 `json:"r"`
	}{m.Time, m.Symbol, m.Action, m.Side, m.Price, m.Qty, m.RefSeq}
	b, _ := json.Marshal(payload)
	h := fnv.New64a()
	h.Write(b)
	return h.Sum64()
}

// ErrorKind classifies validation failures for deterministic reporting.
type ErrorKind string

const (
	KindDuplicate      ErrorKind = "duplicate_conflict" // same seq, different payload
	KindInvalidCancel  ErrorKind = "invalid_cancel"     // cancel/correct of unknown version
	KindNegative       ErrorKind = "negative_value"     // negative price/qty
	KindSeqRollback    ErrorKind = "seq_rollback"       // seq below expected
	KindSeqOverflow    ErrorKind = "seq_overflow"       // seq increment would overflow
	KindGap            ErrorKind = "gap_detected"       // missing sequence numbers
	KindBufferOverflow ErrorKind = "buffer_overflow"    // bounded budget exceeded
	KindCorruptCheckpt ErrorKind = "corrupt_checkpoint" // checkpoint cannot be decoded
	KindInvalidMessage ErrorKind = "invalid_message"    // malformed record fields
)

// Error is a classified engine error.
type Error struct {
	Kind    ErrorKind
	Source  Source
	Message string
}

func (e *Error) Error() string { return string(e.Kind) + ": " + e.Message }
