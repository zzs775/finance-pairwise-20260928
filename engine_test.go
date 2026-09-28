package cce

import (
	"errors"
	"testing"
	"time"
)

func vParams(t *testing.T, cur Currency, threshold, mta, ia int64) Params {
	t.Helper()
	return Params{
		Threshold:         mustFixed(t, threshold, cur),
		MTA:               mustFixed(t, mta, cur),
		IndependentAmount: mustFixed(t, ia, cur),
	}
}

func openVersion(t *testing.T, ns string, no int, cur Currency, start time.Time, p Params) AgreementVersion {
	t.Helper()
	return AgreementVersion{
		NettingSetID: ns,
		Version:      no,
		Currency:     cur,
		Start:        start,
		Params:       p,
	}
}

func windowVersion(t *testing.T, ns string, no int, cur Currency, start, end time.Time, p Params) AgreementVersion {
	t.Helper()
	return AgreementVersion{
		NettingSetID: ns,
		Version:      no,
		Currency:     cur,
		Start:        start,
		End:          end,
		Params:       p,
	}
}

func tradeAt(t *testing.T, id, ns string, cur Currency, mtm int64, at time.Time) Trade {
	t.Helper()
	return Trade{
		ID:           id,
		NettingSetID: ns,
		Currency:     cur,
		MtM:          mustFixed(t, mtm, cur),
		At:           at,
	}
}

func TestEngineMissingAgreement(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	if err := eng.UpsertTrade(tradeAt(t, "T1", "NS", usd, 100, t1)); err != nil {
		t.Fatal(err)
	}
	initial := eng.Current()
	_, err := eng.Commit(t2)
	if !errors.Is(err, ErrMissingAgreement) || ClassOf(err) != ClassAgreement {
		t.Fatalf("commit without agreement: %v", err)
	}
	if eng.Current() != initial {
		t.Fatal("failed commit published a partial snapshot")
	}
}

func TestEngineCrossCurrencyCollateral(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	if err := eng.UpsertAgreement(openVersion(t, "NS", 1, usd, t0, vParams(t, usd, 0, 0, 0))); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertTrade(tradeAt(t, "T1", "NS", usd, 100, t1)); err != nil {
		t.Fatal(err)
	}
	if err := eng.SetCollateral("NS", mustFixed(t, 50, Currency("EUR")), t1); err != nil {
		t.Fatal(err)
	}
	_, err := eng.Commit(t2)
	if !errors.Is(err, ErrCurrencyMismatch) || ClassOf(err) != ClassCurrency {
		t.Fatalf("cross currency collateral: %v", err)
	}
}

func TestEngineDuplicateVersions(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	v := openVersion(t, "NS", 7, usd, t0, vParams(t, usd, 10, 5, 0))
	if err := eng.UpsertAgreement(v); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertAgreement(v); err != nil {
		t.Fatalf("identical duplicate should be idempotent: %v", err)
	}
	different := v
	different.Params = vParams(t, usd, 999, 5, 0)
	err := eng.UpsertAgreement(different)
	if !errors.Is(err, ErrDuplicateVersion) || ClassOf(err) != ClassVersion {
		t.Fatalf("changed duplicate: %v", err)
	}
}

func TestEngineRuleChangeVersionSelection(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	if err := eng.UpsertAgreement(windowVersion(t, "NS", 1, usd, t0, t2, vParams(t, usd, 1000, 100, 0))); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertAgreement(openVersion(t, "NS", 2, usd, t2, vParams(t, usd, 0, 0, 0))); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertTrade(tradeAt(t, "T-OLD", "NS", usd, 500, t1)); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertTrade(tradeAt(t, "T-NEW", "NS", usd, 300, t2.Add(time.Hour))); err != nil {
		t.Fatal(err)
	}

	// Valuation under version 1: threshold 1000 floors exposure and only
	// the v1 trade is visible.
	snap1, err := eng.Commit(t1.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	r1, ok := snap1.Report("NS")
	if !ok {
		t.Fatal("missing NS report")
	}
	if r1.Steps.Version != 1 || r1.Steps.TradeCount != 1 ||
		r1.Steps.NetMtM.Value() != 500 || r1.Steps.Exposure.Value() != 0 {
		t.Fatalf("v1 report: %+v", r1.Steps)
	}

	// Valuation under version 2: threshold 0 and only the v2 trade counts.
	snap2, err := eng.Commit(t3)
	if err != nil {
		t.Fatal(err)
	}
	r2, ok := snap2.Report("NS")
	if !ok {
		t.Fatal("missing NS report")
	}
	if r2.Steps.Version != 2 || r2.Steps.TradeCount != 1 ||
		r2.Steps.NetMtM.Value() != 300 || r2.Steps.Exposure.Value() != 300 {
		t.Fatalf("v2 report: %+v", r2.Steps)
	}
	if snap2.Reports()[0].NettingSetID != "NS" {
		t.Fatal("reports not canonical")
	}
}

func TestEngineCloseoutFreezeIsolatesLaterMtM(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	if err := eng.UpsertAgreement(openVersion(t, "NS", 1, usd, t0, vParams(t, usd, 0, 0, 0))); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertTrade(tradeAt(t, "T1", "NS", usd, 700, t1)); err != nil {
		t.Fatal(err)
	}
	if err := eng.Transition("NS", StateClosed, t2); err != nil {
		t.Fatal(err)
	}
	snap, err := eng.Commit(t3)
	if err != nil {
		t.Fatal(err)
	}
	r, _ := snap.Report("NS")
	if !r.Closed || r.State != StateClosed || r.Steps.Exposure.Value() != 700 {
		t.Fatalf("closeout report: %+v", r)
	}
	if !r.Steps.EvaluatedAt.Equal(t2) {
		t.Fatalf("closeout valuated at %s want %s", r.Steps.EvaluatedAt, t2)
	}

	// Later MtM cannot join the frozen set: upsert is rejected.
	err = eng.UpsertTrade(tradeAt(t, "T2", "NS", usd, 5000, t3))
	if !errors.Is(err, ErrClosedNettingSet) || ClassOf(err) != ClassState {
		t.Fatalf("upsert after close: %v", err)
	}

	snap2, err := eng.Commit(t3.Add(24 * time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	r2, _ := snap2.Report("NS")
	if r2.Steps.Exposure.Value() != 700 ||
		!r2.Steps.EvaluatedAt.Equal(r.Steps.EvaluatedAt) {
		t.Fatalf("frozen snapshot drifted: %+v", r2.Steps)
	}
}

func TestEngineIllegalTransitionNoPartialAudit(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	if err := eng.UpsertAgreement(openVersion(t, "NS", 1, usd, t0, vParams(t, usd, 0, 0, 0))); err != nil {
		t.Fatal(err)
	}
	if err := eng.UpsertTrade(tradeAt(t, "T1", "NS", usd, 10, t1)); err != nil {
		t.Fatal(err)
	}

	if err := eng.Transition("NS", StateDefaulted, t1); err != nil {
		t.Fatal(err)
	}
	if err := eng.Transition("NS", StateMargining, t2); !errors.Is(err, ErrInvalidTransition) {
		t.Fatalf("illegal migration: %v", err)
	}

	snap, err := eng.Commit(t2)
	if err != nil {
		t.Fatal(err)
	}
	r, _ := snap.Report("NS")
	if r.State != StateDefaulted {
		t.Fatalf("state after rejected migration: %s", r.State)
	}
}

func TestEngineTradeReassignedRejected(t *testing.T) {
	eng := NewEngine()
	usd := Currency("USD")
	first := tradeAt(t, "T1", "A", usd, 1, t1)
	if err := eng.UpsertTrade(first); err != nil {
		t.Fatal(err)
	}
	second := first
	second.NettingSetID = "B"
	err := eng.UpsertTrade(second)
	if !errors.Is(err, ErrDuplicateTrade) || ClassOf(err) != ClassTrade {
		t.Fatalf("trade reassignment: %v", err)
	}
}
