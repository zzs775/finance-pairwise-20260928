package cce

import (
	"errors"
	"testing"
	"time"
)

var (
	t0 = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	t1 = time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)
	t2 = time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	t3 = time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC)
)

func baseVersion(t *testing.T, no int, start, end time.Time) AgreementVersion {
	cur := Currency("USD")
	return AgreementVersion{
		NettingSetID: "NS",
		Version:      no,
		Currency:     cur,
		Start:        start,
		End:          end,
		Params: Params{
			Threshold:         mustFixed(t, 100, cur),
			MTA:               mustFixed(t, 10, cur),
			IndependentAmount: Zero(cur),
		},
	}
}

func TestVersionAtHalfOpenBoundaries(t *testing.T) {
	v1 := baseVersion(t, 1, t0, t1)
	v2 := baseVersion(t, 2, t1, time.Time{})
	ag, err := NewAgreement([]AgreementVersion{v2, v1})
	if err != nil {
		t.Fatal(err)
	}

	got, err := ag.VersionAt(t0)
	if err != nil || got.Version != 1 {
		t.Fatalf("at t0: version %d, %v", got.Version, err)
	}
	// End is exclusive: exactly t1 must already read version 2.
	got, err = ag.VersionAt(t1)
	if err != nil || got.Version != 2 {
		t.Fatalf("at boundary t1: version %d, %v", got.Version, err)
	}
	got, err = ag.VersionAt(t3)
	if err != nil || got.Version != 2 {
		t.Fatalf("open end: version %d, %v", got.Version, err)
	}

	before := t0.Add(-time.Nanosecond)
	if _, err := ag.VersionAt(before); !errors.Is(err, ErrMissingAgreement) {
		t.Fatalf("before first window: %v", err)
	}

	order := ag.Versions()
	if order[0].Version != 1 || order[1].Version != 2 {
		t.Fatalf("versions not canonical order: %v", order)
	}
	order[0] = AgreementVersion{}
	if ag.Versions()[0].Version != 1 {
		t.Fatal("Versions() must return a defensive copy")
	}
}

func TestDuplicateVersionRejected(t *testing.T) {
	v1 := baseVersion(t, 1, t0, t1)
	v2 := baseVersion(t, 1, t1, time.Time{})
	_, err := NewAgreement([]AgreementVersion{v1, v2})
	if !errors.Is(err, ErrDuplicateVersion) || ClassOf(err) != ClassVersion {
		t.Fatalf("duplicate version: %v", err)
	}
}

func TestOverlapAndGapRejected(t *testing.T) {
	v1 := baseVersion(t, 1, t0, t2) // ends at t2
	v2 := baseVersion(t, 2, t1, time.Time{})
	if _, err := NewAgreement([]AgreementVersion{v1, v2}); !errors.Is(err, ErrOverlappingWindow) {
		t.Fatalf("overlap: %v", err)
	}

	g1 := baseVersion(t, 1, t0, t1)
	g2 := baseVersion(t, 2, t2, time.Time{}) // gap between t1 and t2
	if _, err := NewAgreement([]AgreementVersion{g1, g2}); !errors.Is(err, ErrVersionGap) {
		t.Fatalf("gap: %v", err)
	}

	open := baseVersion(t, 1, t0, time.Time{})
	later := baseVersion(t, 2, t1, time.Time{})
	if _, err := NewAgreement([]AgreementVersion{open, later}); !errors.Is(err, ErrOverlappingWindow) {
		t.Fatalf("open window followed by another: %v", err)
	}
}

func TestAgreementCurrencyMismatch(t *testing.T) {
	v1 := baseVersion(t, 1, t0, t1)
	v2 := baseVersion(t, 2, t1, time.Time{})
	v2.Currency = Currency("EUR")
	_, err := NewAgreement([]AgreementVersion{v1, v2})
	if !errors.Is(err, ErrCurrencyMismatch) || ClassOf(err) != ClassCurrency {
		t.Fatalf("cross currency chain: %v", err)
	}
}

func TestEmptyAgreementRejected(t *testing.T) {
	if _, err := NewAgreement(nil); !errors.Is(err, ErrMissingAgreement) {
		t.Fatalf("empty: %v", err)
	}
}
