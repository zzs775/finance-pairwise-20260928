package circuitbreaker

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/gob"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
)

const checkpointFormat uint32 = 1

// Checkpoint is the serialisable full state of an engine.
type Checkpoint struct {
	Format     uint32
	LastTime   Time
	ActiveRule Version
	Summary    InputSummary
	Window     []storedPoint
	Timers     []storedTimer
	Refs       []storedReference
	RuleKeys   []Version
	Rules      []storedRule
	State      State
	SeenSeq    []SeenEvent
}

type storedRule struct {
	Version Version
	Rules   RuleSet
}

type storedPoint struct {
	Time        Time
	Seq         Seq
	Price       Price
	RuleVersion Version
}

type storedReference struct {
	Version   Version
	Price     Price
	Effective Time
}

// SeenEvent is one deduplicated source record stored in a checkpoint.
type SeenEvent struct {
	Seq         Seq
	Kind        EventKind
	Time        Time
	RuleVersion Version
	Price       Price
	Rules       RuleSet
}

type storedTimer struct {
	Due   Time
	Kind  timerKind
	Fired bool
	ID    int64
}

// ErrCorruptCheckpoint is returned when a checkpoint fails validation.
var ErrCorruptCheckpoint = errors.New("corrupt checkpoint")

func (e *Engine) snapshot() Checkpoint {
	cp := Checkpoint{
		Format:     checkpointFormat,
		LastTime:   e.lastTime,
		ActiveRule: e.activeRule,
		Summary:    e.summary,
		State:      e.state,
	}
	for _, t := range e.timers.items {
		cp.Timers = append(cp.Timers, storedTimer{Due: t.due, Kind: t.kind, Fired: t.fired, ID: t.id})
	}
	for _, r := range e.refs.refs {
		cp.Refs = append(cp.Refs, storedReference{Version: r.version, Price: r.price, Effective: r.effective})
	}
	for v, r := range e.rules {
		cp.RuleKeys = append(cp.RuleKeys, v)
		cp.Rules = append(cp.Rules, storedRule{Version: v, Rules: r})
	}
	sort.Slice(cp.RuleKeys, func(i, j int) bool { return cp.RuleKeys[i] < cp.RuleKeys[j] })
	sort.Slice(cp.Rules, func(i, j int) bool { return cp.Rules[i].Version < cp.Rules[j].Version })
	for _, p := range e.win.points {
		cp.Window = append(cp.Window, storedPoint{
			Time: p.time, Seq: p.seq, Price: p.price, RuleVersion: p.ruleVersion,
		})
	}
	for seq, ev := range e.seenSeq {
		cp.SeenSeq = append(cp.SeenSeq, SeenEvent{
			Seq:         seq,
			Kind:        ev.Kind,
			Time:        ev.Time,
			RuleVersion: ev.RuleVersion,
			Price:       ev.Price,
			Rules:       ev.Rules,
		})
	}
	sort.Slice(cp.SeenSeq, func(i, j int) bool { return cp.SeenSeq[i].Seq < cp.SeenSeq[j].Seq })
	return cp
}

func (e *Engine) load(cp Checkpoint, clock Clock, pub Publisher) (*Engine, error) {
	rules := make(map[Version]RuleSet, len(cp.Rules))
	for _, sr := range cp.Rules {
		rules[sr.Version] = sr.Rules
	}
	if len(rules) != len(cp.Rules) {
		return nil, fmt.Errorf("%w: duplicate rule version in checkpoint", ErrCorruptCheckpoint)
	}
	initial, ok := rules[cp.ActiveRule]
	if !ok {
		return nil, fmt.Errorf("%w: missing active rule version", ErrCorruptCheckpoint)
	}
	ne := &Engine{
		clock:      clock,
		pub:        pub,
		lastTime:   cp.LastTime,
		seenSeq:    make(map[Seq]Event, len(cp.SeenSeq)),
		win:        newWindow(initial.Horizon),
		timers:     newTimerHeap(),
		refs:       newRefBook(),
		activeRule: cp.ActiveRule,
		rules:      rules,
		state:      cp.State,
		summary:    cp.Summary,
	}
	for _, p := range cp.Window {
		ne.win.points = append(ne.win.points, tradePoint{
			time: p.Time, seq: p.Seq, price: p.Price, ruleVersion: p.RuleVersion,
		})
	}
	var maxID int64
	for _, st := range cp.Timers {
		t := &scheduledTimer{id: st.ID, due: st.Due, kind: st.Kind, fired: st.Fired}
		ne.timers.items = append(ne.timers.items, t)
		if st.ID > maxID {
			maxID = st.ID
		}
	}
	ne.timers.counter = maxID
	timerHeapInit(ne.timers)
	for _, r := range cp.Refs {
		ne.refs.refs = append(ne.refs.refs, reference{version: r.Version, price: r.Price, effective: r.Effective})
	}
	for _, s := range cp.SeenSeq {
		ne.seenSeq[s.Seq] = Event{
			Kind:        s.Kind,
			Time:        s.Time,
			Source:      s.Seq,
			RuleVersion: s.RuleVersion,
			Price:       s.Price,
			Rules:       s.Rules,
		}
	}
	if adv, ok := clock.(interface{ Advance(Time) error }); ok {
		if err := adv.Advance(cp.LastTime); err != nil {
			return nil, err
		}
	}
	return ne, nil
}

// MarshalCheckpoint produces a self-describing, checksummed byte snapshot.
func (e *Engine) MarshalCheckpoint() ([]byte, error) {
	cp := e.snapshot()
	var body bytes.Buffer
	if err := gob.NewEncoder(&body).Encode(cp); err != nil {
		return nil, err
	}
	sum := sha256.Sum256(body.Bytes())
	out := make([]byte, 0, 4+body.Len()+32)
	hdr := make([]byte, 4)
	binary.BigEndian.PutUint32(hdr, cp.Format)
	out = append(out, hdr...)
	out = append(out, body.Bytes()...)
	out = append(out, sum[:]...)
	return out, nil
}

func decodeCheckpoint(data []byte) (Checkpoint, error) {
	var cp Checkpoint
	if len(data) < 4+32 {
		return cp, fmt.Errorf("%w: truncated", ErrCorruptCheckpoint)
	}
	format := binary.BigEndian.Uint32(data[:4])
	if format != checkpointFormat {
		return cp, fmt.Errorf("%w: unknown format %d", ErrCorruptCheckpoint, format)
	}
	body := data[4 : len(data)-32]
	sum := data[len(data)-32:]
	want := sha256.Sum256(body)
	if !bytes.Equal(sum, want[:]) {
		return cp, fmt.Errorf("%w: checksum mismatch", ErrCorruptCheckpoint)
	}
	if err := gob.NewDecoder(bytes.NewReader(body)).Decode(&cp); err != nil {
		return cp, fmt.Errorf("%w: %v", ErrCorruptCheckpoint, err)
	}
	if cp.Format != checkpointFormat || cp.ActiveRule < 0 {
		return cp, fmt.Errorf("%w: invalid header", ErrCorruptCheckpoint)
	}
	return cp, nil
}

// RestoreCheckpoint rebuilds an engine from MarshalCheckpoint bytes.
func RestoreCheckpoint(data []byte, clock Clock, pub Publisher) (*Engine, error) {
	cp, err := decodeCheckpoint(data)
	if err != nil {
		return nil, err
	}
	var zero Engine
	return zero.load(cp, clock, pub)
}

// SaveCheckpoint atomically writes path; a pre-existing complete file is
// never replaced when encoding or writing fails.
func SaveCheckpoint(path string, e *Engine) (err error) {
	data, err := e.MarshalCheckpoint()
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".checkpoint-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpName)
		}
	}()
	if _, err = tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err = tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err = tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

// LoadCheckpointFile reads and restores a checkpoint from path.
func LoadCheckpointFile(path string, clock Clock, pub Publisher) (*Engine, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	return RestoreCheckpoint(data, clock, pub)
}
