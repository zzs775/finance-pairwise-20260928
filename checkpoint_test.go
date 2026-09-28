package circuitbreaker

import (
	"bytes"
	"errors"
	"path/filepath"
	"testing"
)

var errBoom = errors.New("simulated publish failure")

func scenarioEvents() []Event {
	rulesV2 := testRules()
	rulesV2.Version = 2
	rulesV2.Level1 = 400
	return []Event{
		{Kind: KindReference, Time: 1, Source: 1, RuleVersion: 2, Price: 10000},
		{Kind: KindRules, Time: 1, Source: 2, RuleVersion: 2, Rules: rulesV2},
		{Kind: KindTrade, Time: 2, Source: 3, RuleVersion: 2, Price: 9500},
		{Kind: KindTrade, Time: 3, Source: 4, RuleVersion: 2, Price: 9000},
		{Kind: KindTrade, Time: 20, Source: 5, RuleVersion: 2, Price: 9901},
		{Kind: KindRecover, Time: 21, Source: 6, RuleVersion: 2},
	}
}

func runScenario(t *testing.T, start []byte) (*Engine, []Notice) {
	t.Helper()
	var engine *Engine
	pub := NewMemoryPublisher()
	clock := NewLogicalClock(0)
	if start != nil {
		var err error
		engine, err = RestoreCheckpoint(start, clock, pub)
		if err != nil {
			t.Fatalf("restore: %v", err)
		}
	} else {
		var err error
		engine, err = NewEngine(clock, pub, testRules(), 10000)
		if err != nil {
			t.Fatalf("new: %v", err)
		}
	}
	for _, ev := range scenarioEvents() {
		if err := engine.Apply(ev); err != nil {
			t.Fatalf("apply seq %d: %v", ev.Source, err)
		}
	}
	return engine, pub.Notices()
}

func TestCheckpointReplayMatchesFullReplay(t *testing.T) {
	// Full replay through the whole scenario.
	_, fullNotices := runScenario(t, nil)

	// Replay only up to the checkpoint, snapshot, then continue on a
	// restored engine; the complete notice stream must be byte-equivalent.
	pub := NewMemoryPublisher()
	clock := NewLogicalClock(0)
	engine, err := NewEngine(clock, pub, testRules(), 10000)
	if err != nil {
		t.Fatal(err)
	}
	events := scenarioEvents()
	for _, ev := range events[:3] {
		if err := engine.Apply(ev); err != nil {
			t.Fatal(err)
		}
	}
	data, err := engine.MarshalCheckpoint()
	if err != nil {
		t.Fatal(err)
	}

	restoredPub := NewMemoryPublisher()
	restoredClock := NewLogicalClock(0)
	restored, err := RestoreCheckpoint(data, restoredClock, restoredPub)
	if err != nil {
		t.Fatalf("restore: %v", err)
	}
	for _, ev := range events[3:] {
		if err := restored.Apply(ev); err != nil {
			t.Fatalf("resumed apply: %v", err)
		}
	}

	combined := append(pub.Notices(), restoredPub.Notices()...)
	if len(combined) != len(fullNotices) {
		t.Fatalf("notice count: resumed %d vs full %d", len(combined), len(fullNotices))
	}
	for i := range fullNotices {
		if combined[i] != fullNotices[i] {
			t.Fatalf("notice %d differs: %+v vs %+v", i, combined[i], fullNotices[i])
		}
	}

	// Checkpoint must be deterministic: marshal twice, bytes identical.
	data2, err := restored.MarshalCheckpoint()
	if err != nil {
		t.Fatal(err)
	}
	fullEngine, _ := runScenario(t, nil)
	fullData, err := fullEngine.MarshalCheckpoint()
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data2, fullData) {
		t.Fatal("restored engine checkpoint differs from full-replay checkpoint")
	}
}

func TestCorruptCheckpointRejected(t *testing.T) {
	engine, _ := runScenario(t, nil)
	data, err := engine.MarshalCheckpoint()
	if err != nil {
		t.Fatal(err)
	}

	corrupt := append([]byte(nil), data...)
	corrupt[len(corrupt)-1] ^= 0xFF
	if _, err := RestoreCheckpoint(corrupt, NewLogicalClock(0), NewMemoryPublisher()); err == nil {
		t.Fatal("checksum-corrupt checkpoint must be refused")
	}

	truncated := data[:len(data)/2]
	if _, err := RestoreCheckpoint(truncated, NewLogicalClock(0), NewMemoryPublisher()); err == nil {
		t.Fatal("truncated checkpoint must be refused")
	}

	garbage := []byte{0, 0, 0, 1, 'x', 'y'}
	if _, err := RestoreCheckpoint(garbage, NewLogicalClock(0), NewMemoryPublisher()); err == nil {
		t.Fatal("garbage checkpoint must be refused")
	}
}

func TestSaveCheckpointAtomicAndReusable(t *testing.T) {
	engine, _ := runScenario(t, nil)
	path := filepath.Join(t.TempDir(), "state.ckpt")
	if err := SaveCheckpoint(path, engine); err != nil {
		t.Fatalf("save: %v", err)
	}
	restored, err := LoadCheckpointFile(path, NewLogicalClock(0), NewMemoryPublisher())
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if restored.StateName() != engine.StateName() {
		t.Fatalf("state mismatch: %s vs %s", restored.StateName(), engine.StateName())
	}
	if restored.Summary() != engine.Summary() {
		t.Fatalf("summary mismatch: %+v vs %+v", restored.Summary(), engine.Summary())
	}
}

func TestFailedPublishKeepsStateConsistent(t *testing.T) {
	pub := NewMemoryPublisher()
	clock := NewLogicalClock(0)
	engine, err := NewEngine(clock, pub, testRules(), 10000)
	if err != nil {
		t.Fatal(err)
	}
	pub.FailNext(errBoom)
	err = engine.Apply(trade(1, 1, 9500))
	if err == nil {
		t.Fatal("publish failure must surface")
	}
	if engine.StateName() != StateOpen {
		t.Fatalf("failed transition must roll back to open, got %s", engine.StateName())
	}
	if len(engine.timers.items) != 0 {
		t.Fatal("rolled-back transition must leave no halt timer")
	}
	// A later successful run from the same state must not see a published
	// tradable notice from the failed transition.
	_ = engine.Apply(trade(2, 2, 9500))
	if engine.StateName() != StateHaltedLevel1 {
		t.Fatalf("retry must halt cleanly, got %s", engine.StateName())
	}
	for _, n := range pub.Notices() {
		if n.Tradable && n.State != StateOpen {
			t.Fatal("failed publish leaked a tradable suspended notice")
		}
	}
}
