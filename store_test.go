package cce

import (
	"sync"
	"testing"
	"time"
)

func sampleReports() []Report {
	return []Report{
		{
			NettingSetID: "B",
			Currency:     Currency("EUR"),
			State:        StateOpen,
			Steps: Steps{
				Version:  1,
				NetMtM:   fx(300, Currency("EUR")),
				Exposure: fx(300, Currency("EUR")),
			},
		},
		{
			NettingSetID: "A",
			Currency:     Currency("USD"),
			State:        StateMargining,
			Steps: Steps{
				Version:  2,
				NetMtM:   fx(10, Currency("USD")),
				Exposure: fx(10, Currency("USD")),
			},
		},
	}
}

func fx(v int64, cur Currency) Fixed {
	f, err := MkFixed(v, cur)
	if err != nil {
		panic(err)
	}
	return f
}

func TestSnapshotCanonicalOrderAndDefensiveCopy(t *testing.T) {
	store := NewStore()
	snap, err := store.publish(sampleReports(), t1)
	if err != nil {
		t.Fatal(err)
	}
	rs := snap.Reports()
	if rs[0].NettingSetID != "A" || rs[1].NettingSetID != "B" {
		t.Fatalf("not canonical: %s, %s", rs[0].NettingSetID, rs[1].NettingSetID)
	}
	// Mutating the returned slice must not corrupt the frozen snapshot.
	rs[0] = Report{}
	again := snap.Reports()
	if again[0].NettingSetID != "A" {
		t.Fatal("snapshot mutated through Reports() result")
	}
	if snap.Seq() != 1 {
		t.Fatalf("seq = %d", snap.Seq())
	}

	snap2, err := store.publish(sampleReports(), t2)
	if err != nil {
		t.Fatal(err)
	}
	if snap2.Seq() != 2 || !snap.EqualContent(snap2) {
		t.Fatal("identical publications must be content-equal with bumping seq")
	}
	if store.Current() != snap2 {
		t.Fatal("Current not latest")
	}
}

func TestConcurrentReadersSeeOneSnapshot(t *testing.T) {
	store := NewStore()
	if _, err := store.publish(sampleReports(), t1); err != nil {
		t.Fatal(err)
	}

	const readers = 16
	var wg sync.WaitGroup
	errCh := make(chan error, readers)
	for i := 0; i < readers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for k := 0; k < 200; k++ {
				snap := store.Current()
				seq := snap.Seq()
				var prev string
				for _, r := range snap.Reports() {
					// Every record read during this query must belong to
					// the same pinned snapshot and canonical order.
					if snap.Seq() != seq {
						errCh <- ErrClosedNettingSet
						return
					}
					if r.NettingSetID < prev {
						errCh <- ErrVersionGap
						return
					}
					prev = r.NettingSetID
				}
				if r, ok := snap.Report("A"); !ok || r.Currency != Currency("USD") {
					errCh <- ErrUnknownNettingSet
					return
				}
			}
		}()
	}
	wg.Wait()
	select {
	case err := <-errCh:
		t.Fatal(err)
	default:
	}
}

// TestFixedScheduleEquivalentToFullRecompute models serial publication with
// interleaved readers: each committed snapshot must equal a from-scratch full
// recomputation for the same as-of time, and readers pinning an old snapshot
// must keep observing its frozen content.
func TestFixedScheduleEquivalentToFullRecompute(t *testing.T) {
	usd := Currency("USD")
	buildEngine := func() *Engine {
		eng := NewEngine()
		mustNil(t, eng.UpsertAgreement(openVersion(t, "NS", 1, usd, t0,
			vParams(t, usd, 100, 25, 0))))
		mustNil(t, eng.UpsertTrade(tradeAt(t, "T1", "NS", usd, 200, t1)))
		return eng
	}

	primary := buildEngine()
	committed := make([]*Snapshot, 0, 4)
	for i := 0; i < 3; i++ {
		// Same staged inputs every round: duplicate recommit is idempotent
		// in content while bumping seq.
		snap, err := primary.Commit(t2)
		if err != nil {
			t.Fatal(err)
		}
		committed = append(committed, snap)

		// Independent full recomputation from a fresh engine.
		ref := buildEngine()
		refSnap, err := ref.Commit(t2)
		if err != nil {
			t.Fatal(err)
		}
		if !snap.EqualContent(refSnap) {
			t.Fatalf("commit %d differs from full recomputation", i)
		}
	}
	for i := 1; i < len(committed); i++ {
		if !committed[i-1].EqualContent(committed[i]) {
			t.Fatal("repeated commits not idempotent in content")
		}
		if committed[i].Seq() <= committed[i-1].Seq() {
			t.Fatal("seq must increase per commit")
		}
	}

	// A newly published snapshot must not disturb older pinned snapshots.
	primary.SetCollateral("NS", mustFixed(t, 50, usd), t2)
	latest, err := primary.Commit(t3)
	if err != nil {
		t.Fatal(err)
	}
	old := committed[0]
	oldReport, _ := old.Report("NS")
	if oldReport.Steps.Collateral.Value() != 0 {
		t.Fatal("old snapshot changed after later publication")
	}
	newReport, _ := latest.Report("NS")
	if newReport.Steps.Collateral.Value() != 50 {
		t.Fatalf("new snapshot collateral = %d", newReport.Steps.Collateral.Value())
	}
}

// TestInputOrderIndependence proves shuffled insertion order yields identical
// canonical reports.
func TestInputOrderIndependence(t *testing.T) {
	usd := Currency("USD")
	eur := Currency("EUR")
	makeInputs := func() ([]AgreementVersion, []Trade, []Fixed) {
		agreements := []AgreementVersion{
			openVersion(t, "NS-1", 1, usd, t0, vParams(t, usd, 100, 10, 5)),
			openVersion(t, "NS-2", 1, eur, t0, vParams(t, eur, 200, 20, 0)),
		}
		trades := []Trade{
			tradeAt(t, "T1", "NS-1", usd, 500, t1),
			tradeAt(t, "T2", "NS-1", usd, -200, t1.Add(time.Hour)),
			tradeAt(t, "T3", "NS-2", eur, 900, t1),
		}
		collateral := []Fixed{
			mustFixed(t, 75, usd),
			mustFixed(t, 100, eur),
		}
		return agreements, trades, collateral
	}

	run := func(agreements []AgreementVersion, trades []Trade, collateral []Fixed) *Snapshot {
		eng := NewEngine()
		for _, a := range agreements {
			if err := eng.UpsertAgreement(a); err != nil {
				t.Fatal(err)
			}
		}
		for _, tr := range trades {
			if err := eng.UpsertTrade(tr); err != nil {
				t.Fatal(err)
			}
		}
		eng.SetCollateral("NS-1", collateral[0], t2)
		eng.SetCollateral("NS-2", collateral[1], t2)
		snap, err := eng.Commit(t2)
		if err != nil {
			t.Fatal(err)
		}
		return snap
	}

	a1, tr1, c1 := makeInputs()
	first := run(a1, tr1, c1)

	a2, tr2, c2 := makeInputs()
	// Reverse every input batch.
	reverseAgreements(a2)
	reverseTrades(tr2)
	second := run(a2, tr2, c2)

	if !first.EqualContent(second) {
		t.Fatal("input order changed canonical reports")
	}
}

func mustNil(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func reverseAgreements(s []AgreementVersion) {
	for i, j := 0, len(s)-1; i < j; i, j = i+1, j-1 {
		s[i], s[j] = s[j], s[i]
	}
}

func reverseTrades(s []Trade) {
	for i, j := 0, len(s)-1; i < j; i, j = i+1, j-1 {
		s[i], s[j] = s[j], s[i]
	}
}
