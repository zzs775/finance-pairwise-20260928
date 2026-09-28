package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"

	cb "circuitbreaker"
)

// wireRule mirrors cb.RuleSet on the JSON wire.
type wireRule struct {
	Version  int64 `json:"version"`
	Horizon  int64 `json:"horizon"`
	Level1   int64 `json:"level1_bp"`
	Level2   int64 `json:"level2_bp"`
	Level3   int64 `json:"level3_bp"`
	Recover  int64 `json:"recover_bp"`
	Halt1    int64 `json:"halt1_ticks"`
	Halt2    int64 `json:"halt2_ticks"`
	Cooldown int64 `json:"cooldown_ticks"`
}

func (w wireRule) toDomain() cb.RuleSet {
	return cb.RuleSet{
		Version:  cb.Version(w.Version),
		Horizon:  cb.Duration(w.Horizon),
		Level1:   cb.BasisPoints(w.Level1),
		Level2:   cb.BasisPoints(w.Level2),
		Level3:   cb.BasisPoints(w.Level3),
		Recover:  cb.BasisPoints(w.Recover),
		Halt1:    cb.Duration(w.Halt1),
		Halt2:    cb.Duration(w.Halt2),
		Cooldown: cb.Duration(w.Cooldown),
	}
}

// wireEvent is one JSON object per input line.
type wireEvent struct {
	Kind        string   `json:"kind"`
	Time        int64    `json:"time"`
	Seq         int64    `json:"seq"`
	RuleVersion int64    `json:"rule_version"`
	Price       int64    `json:"price"`
	Rules       wireRule `json:"rules"`
}

func (w wireEvent) toDomain() (cb.Event, error) {
	ev := cb.Event{
		Time:        cb.Time(w.Time),
		Source:      cb.Seq(w.Seq),
		RuleVersion: cb.Version(w.RuleVersion),
		Price:       cb.Price(w.Price),
		Rules:       w.Rules.toDomain(),
	}
	switch w.Kind {
	case "trade":
		ev.Kind = cb.KindTrade
	case "reference":
		ev.Kind = cb.KindReference
	case "rules":
		ev.Kind = cb.KindRules
	case "recover":
		ev.Kind = cb.KindRecover
	default:
		return ev, fmt.Errorf("unknown event kind %q", w.Kind)
	}
	return ev, nil
}

// wireNotice is the JSON representation of a published notice.
type wireNotice struct {
	Time        int64  `json:"time"`
	State       string `json:"state"`
	Tradable    bool   `json:"tradable"`
	RuleVersion int64  `json:"rule_version"`
	Explanation string `json:"explanation"`
}

// streamFeeder reads newline-delimited JSON events and feeds an engine,
// flushing notices deterministically after each accepted line.
type streamFeeder struct {
	scanner *bufio.Scanner
	out     io.Writer
	enc     *json.Encoder
}

func newStreamFeeder(in io.Reader, out io.Writer) *streamFeeder {
	f := &streamFeeder{
		scanner: bufio.NewScanner(in),
		out:     out,
	}
	f.scanner.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	f.enc = json.NewEncoder(out)
	return f
}

func (f *streamFeeder) run(engine *cb.Engine, pub *cb.MemoryPublisher) error {
	line := 0
	for f.scanner.Scan() {
		line++
		raw := f.scanner.Bytes()
		if len(bytesTrimSpace(raw)) == 0 {
			continue
		}
		var w wireEvent
		if err := json.Unmarshal(raw, &w); err != nil {
			return fmt.Errorf("line %d: %w", line, err)
		}
		ev, err := w.toDomain()
		if err != nil {
			return fmt.Errorf("line %d: %w", line, err)
		}
		before := len(pub.Notices())
		if err := engine.Apply(ev); err != nil {
			fmt.Fprintf(os.Stderr, "rejected line %d: %v\n", line, err)
			continue
		}
		if err := f.flush(pub, before); err != nil {
			return err
		}
	}
	return f.scanner.Err()
}

func (f *streamFeeder) flush(pub *cb.MemoryPublisher, since int) error {
	for _, n := range pub.Notices()[since:] {
		if err := f.enc.Encode(wireNotice{
			Time:        int64(n.Time),
			State:       n.State.String(),
			Tradable:    n.Tradable,
			RuleVersion: int64(n.RuleVersion),
			Explanation: n.Explanation,
		}); err != nil {
			return err
		}
	}
	return nil
}

func bytesTrimSpace(b []byte) []byte {
	for len(b) > 0 && (b[0] == ' ' || b[0] == '\t' || b[0] == '\r' || b[0] == '\n') {
		b = b[1:]
	}
	for len(b) > 0 && (b[len(b)-1] == ' ' || b[len(b)-1] == '\t' || b[len(b)-1] == '\r' || b[len(b)-1] == '\n') {
		b = b[:len(b)-1]
	}
	return b
}
