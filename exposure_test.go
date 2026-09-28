package cce

import (
	"errors"
	"testing"
	"time"
)

func evalVersion(t *testing.T, threshold, mta, ia int64, cur Currency) AgreementVersion {
	t.Helper()
	return AgreementVersion{
		NettingSetID: "NS",
		Version:      1,
		Currency:     cur,
		Start:        t0,
		End:          time.Time{},
		Params: Params{
			Threshold:         mustFixed(t, threshold, cur),
			MTA:               mustFixed(t, mta, cur),
			IndependentAmount: mustFixed(t, ia, cur),
		},
	}
}

func mkTrade(t *testing.T, id string, v int64, at time.Time) Trade {
	t.Helper()
	cur := Currency("USD")
	return Trade{
		ID:           id,
		NettingSetID: "NS",
		Currency:     cur,
		MtM:          mustFixed(t, v, cur),
		At:           at,
	}
}

func TestComputeStepsFormula(t *testing.T) {
	cur := Currency("USD")
	v := evalVersion(t, 1000, 250, 100, cur)
	trades := []Trade{
		mkTrade(t, "T1", 1200, t0.Add(time.Hour)),
		mkTrade(t, "T2", 800, t0.Add(2*time.Hour)),
	}
	collateral := mustFixed(t, 300, cur)

	steps, err := ComputeSteps(trades, v, collateral, t1)
	if err != nil {
		t.Fatal(err)
	}
	// net=2000, +IA=2100, -threshold=1100, -MTA=850, -collateral=550
	want := map[string]int64{
		"net":    2000,
		"ia":     2100,
		"thresh": 1100,
		"mta":    850,
		"expose": 550,
	}
	if steps.NetMtM.Value() != want["net"] ||
		steps.WithIndependentAmount.Value() != want["ia"] ||
		steps.AfterThreshold.Value() != want["thresh"] ||
		steps.AfterMTA.Value() != want["mta"] ||
		steps.Exposure.Value() != want["expose"] {
		t.Fatalf("unexpected steps: %+v", steps)
	}

	full, err := IndependentExposure(trades, v, collateral, t1)
	if err != nil {
		t.Fatal(err)
	}
	if full.Value() != steps.Exposure.Value() {
		t.Fatalf("full formula %d != steps %d", full.Value(), steps.Exposure.Value())
	}
	if mismatch := assertEquivalent(steps, trades, v, collateral); mismatch != "" {
		t.Fatal(mismatch)
	}
}

func TestMTABoundaryExposureZero(t *testing.T) {
	cur := Currency("USD")
	// post-threshold amount equals MTA exactly -> after-MTA is zero.
	v := evalVersion(t, 500, 300, 0, cur)
	trades := []Trade{mkTrade(t, "T1", 800, t1)}

	steps, err := ComputeSteps(trades, v, Zero(cur), t1)
	if err != nil {
		t.Fatal(err)
	}
	if steps.AfterThreshold.Value() != 300 {
		t.Fatalf("after threshold = %d", steps.AfterThreshold.Value())
	}
	if steps.AfterMTA.Value() != 0 || steps.Exposure.Value() != 0 {
		t.Fatalf("MTA boundary: afterMTA=%d exposure=%d",
			steps.AfterMTA.Value(), steps.Exposure.Value())
	}
}

func TestNegativeNetAndFullCollateral(t *testing.T) {
	cur := Currency("USD")
	v := evalVersion(t, 0, 0, 0, cur)
	trades := []Trade{
		mkTrade(t, "T1", -900, t1),
		mkTrade(t, "T2", 400, t1),
	}

	steps, err := ComputeSteps(trades, v, mustFixed(t, 10000, cur), t1)
	if err != nil {
		t.Fatal(err)
	}
	if steps.NetMtM.Value() != -500 {
		t.Fatalf("net = %d", steps.NetMtM.Value())
	}
	if steps.Exposure.Value() < 0 {
		t.Fatalf("negative exposure %d", steps.Exposure.Value())
	}
	if steps.Exposure.Value() != 0 {
		t.Fatalf("want floored zero, got %d", steps.Exposure.Value())
	}
}

func TestComputeStepsCurrencyAndOwnership(t *testing.T) {
	cur := Currency("USD")
	v := evalVersion(t, 0, 0, 0, cur)

	eurTrade := mkTrade(t, "T1", 10, t1)
	eurTrade.Currency = Currency("EUR")
	eurTrade.MtM = mustFixed(t, 10, Currency("EUR"))
	if _, err := ComputeSteps([]Trade{eurTrade}, v, Zero(cur), t1); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("eur trade under usd agreement: %v", err)
	}

	foreign := mkTrade(t, "T2", 10, t1)
	foreign.NettingSetID = "OTHER"
	if _, err := ComputeSteps([]Trade{foreign}, v, Zero(cur), t1); !errors.Is(err, ErrUnknownNettingSet) {
		t.Fatalf("trade owned by another set: %v", err)
	}

	if _, err := ComputeSteps(nil, v, mustFixed(t, 1, Currency("EUR")), t1); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("eur collateral: %v", err)
	}
}
