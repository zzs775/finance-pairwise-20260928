package engine_test

import (
	"bytes"
	"encoding/json"
	"testing"

	"marketmerge/internal/engine"
	"marketmerge/internal/oracle"
	"marketmerge/internal/synth"
	"marketmerge/internal/types"
)

func ingestSet(t *testing.T, eng *engine.Engine, msgs []types.Message) {
	t.Helper()
	for _, m := range msgs {
		if _, err := eng.Ingest(m); err != nil {
			// Classified rejections of intentionally bad records are fine in
			// dedicated tests; valid synthetic sets must not reject.
			t.Fatalf("unexpected reject for %+v: %v", m, err)
		}
	}
	if _, err := eng.Drain(); err != nil {
		t.Fatalf("drain: %v", err)
	}
}

func TestOracleMatchesStreaming_Plain(t *testing.T) {
	ds := synth.Generate(synth.Config{
		Venues: 4, Partitions: 2, Messages: 12, Seed: 1,
	})
	eng := engine.New(32, 1_000_000)
	eng.RegisterSources(ds.Sources...)
	ingestSet(t, eng, ds.Messages)

	rep := oracle.New().Rebuild(ds.Messages)
	if len(rep.Rejections) != 0 {
		t.Fatalf("oracle rejected clean input: %+v", rep.Rejections)
	}
	if !bytes.Equal(oracle.EventsJSON(eng.Events()),
		oracle.EventsJSON(rep.Events)) {
		t.Fatalf("streaming != oracle\n%s\n%s",
			oracle.EventsJSON(eng.Events()),
			oracle.EventsJSON(rep.Events))
	}
}

func TestOracleMatchesStreaming_GapsAndCorrections(t *testing.T) {
	ds := synth.Generate(synth.Config{
		Venues: 4, Partitions: 2, Messages: 24, Seed: 7,
		Gaps: true, Corrections: true, Duplicates: true, Shuffle: true,
	})
	eng := engine.New(64, 2_000_000)
	eng.RegisterSources(ds.Sources...)
	ingestSet(t, eng, ds.Messages)

	rep := oracle.New().Rebuild(ds.Messages)
	if len(rep.Rejections) != 0 {
		t.Fatalf("oracle rejected input: %+v", rep.Rejections)
	}
	if !bytes.Equal(oracle.EventsJSON(eng.Events()),
		oracle.EventsJSON(rep.Events)) {
		a, b := oracle.EventsJSON(eng.Events()),
			oracle.EventsJSON(rep.Events)
		t.Fatalf("streaming=%d oracle=%d bytes (first mismatch above)\n%s\n%s",
			len(eng.Events()), len(rep.Events), a, b)
	}
}

func TestDeterministicAcrossInterleavings(t *testing.T) {
	ds := synth.Generate(synth.Config{
		Venues: 4, Partitions: 2, Messages: 24, Seed: 7,
		Gaps: true, Corrections: true, Duplicates: true, Shuffle: true,
	})

	// Group messages by source, then concatenate per-source files in two
	// opposite orders to emulate different concurrent-read completion orders.
	bySource := map[types.Source][]types.Message{}
	var srcs []types.Source
	for _, m := range ds.Messages {
		s := m.Source()
		if _, ok := bySource[s]; !ok {
			srcs = append(srcs, s)
		}
		bySource[s] = append(bySource[s], m)
	}
	run := func(perm []types.Source) []byte {
		eng := engine.New(64, 2_000_000)
		eng.RegisterSources(ds.Sources...)
		for _, s := range perm {
			for _, m := range bySource[s] {
				if _, err := eng.Ingest(m); err != nil {
					t.Fatalf("reject: %v", err)
				}
			}
		}
		if _, err := eng.Drain(); err != nil {
			t.Fatalf("drain: %v", err)
		}
		return oracle.EventsJSON(eng.Events())
	}

	forward := append([]types.Source(nil), srcs...)
	reverse := append([]types.Source(nil), srcs...)
	for i, j := 0, len(reverse)-1; i < j; i, j = i+1, j-1 {
		reverse[i], reverse[j] = reverse[j], reverse[i]
	}
	if !bytes.Equal(run(forward), run(reverse)) {
		g, r := run(forward), run(reverse)
		var gg, rr []engine.Event
		_ = json.Unmarshal(g, &gg)
		_ = json.Unmarshal(r, &rr)
		for i := 0; i < len(gg) || i < len(rr); i++ {
			var gs, rs string
			if i < len(gg) {
				b, _ := json.Marshal(gg[i])
				gs = string(b)
			}
			if i < len(rr) {
				b, _ := json.Marshal(rr[i])
				rs = string(b)
			}
			if gs != rs {
				t.Fatalf("first mismatch at %d\nF=%s\nR=%s", i, gs, rs)
			}
		}
	}
}

func TestEventJSONShape(t *testing.T) {
	var ev engine.Event
	if err := json.Unmarshal([]byte(`{"time":1,"kind":"nbb"}`), &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Kind != engine.KindNBB {
		t.Fatalf("kind=%q", ev.Kind)
	}
}
