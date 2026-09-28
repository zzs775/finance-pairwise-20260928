package circuitbreaker

import "testing"

func TestIllegalRuleSetsRejected(t *testing.T) {
	cases := []RuleSet{
		{},
		{Version: 1, Horizon: 10, Level1: 100, Level2: 100, Level3: 200, Recover: 10, Halt1: 1, Halt2: 1, Cooldown: 1},
		{Version: 1, Horizon: 10, Level1: 100, Level2: 200, Level3: 150, Recover: 10, Halt1: 1, Halt2: 1, Cooldown: 1},
		{Version: 1, Horizon: 10, Level1: 100, Level2: 200, Level3: 300, Recover: 100, Halt1: 1, Halt2: 1, Cooldown: 1},
		{Version: 1, Horizon: 10, Level1: 100, Level2: 200, Level3: 300, Recover: 10, Halt1: 5, Halt2: 4, Cooldown: 1},
		{Version: 1, Horizon: 0, Level1: 100, Level2: 200, Level3: 300, Recover: 10, Halt1: 1, Halt2: 1, Cooldown: 1},
	}
	for i, r := range cases {
		if err := r.Validate(); err == nil {
			t.Fatalf("case %d should be illegal", i)
		}
	}
}

func TestReferenceVersionOnlyAffectsLaterEvents(t *testing.T) {
	engine, pub, _ := newTestEngine(t)

	// New reference v2 becomes effective at t=5. A v2 trade at t=4 must be
	// rejected for missing reference; an identical trade at t=5 succeeds.
	install := Event{Kind: KindReference, Time: 5, Source: 1, RuleVersion: 2, Price: 20000}
	if err := engine.Apply(install); err != nil {
		t.Fatalf("install reference: %v", err)
	}
	rulesV2 := testRules()
	rulesV2.Version = 2
	switchV2 := Event{Kind: KindRules, Time: 5, Source: 2, RuleVersion: 2, Rules: rulesV2}
	if err := engine.Apply(switchV2); err != nil {
		t.Fatalf("switch rules: %v", err)
	}

	early := Event{Kind: KindTrade, Time: 4, Source: 3, RuleVersion: 2, Price: 19000}
	if err := engine.Apply(early); rejectReason(err) != RejectTimeRegress {
		t.Fatalf("earlier trade must be time regression, got %v", err)
	}

	// Trade under v1 at t=6 still resolves against v1 reference 10000:
	// a 5% drop halts even though v2 reference exists.
	if err := engine.Apply(install); err != nil {
		t.Fatalf("identical reference should be idempotent: %v", err)
	}
	if err := engine.Apply(trade(6, 4, 9500)); err != nil {
		t.Fatalf("v1 trade: %v", err)
	}
	if engine.StateName() != StateHaltedLevel1 {
		t.Fatalf("v1 reference must remain in force for v1 events, got %s", engine.StateName())
	}

	// Rule version must move forward.
	old := Event{Kind: KindRules, Time: 7, Source: 9, RuleVersion: 1, Rules: testRules()}
	if err := engine.Apply(old); rejectReason(err) != RejectIllegalRules {
		t.Fatalf("rule downgrade must be illegal, got %v", err)
	}

	// Redefining v2 with another price is a conflicting duplicate, even at
	// a later time, because the source seq carries a different payload.
	conflict := Event{Kind: KindReference, Time: 8, Source: 1, RuleVersion: 2, Price: 20001}
	conflict.Price = 20001
	if err := engine.Apply(conflict); rejectReason(err) != RejectDuplicateConflict {
		t.Fatalf("conflicting reference version, got %v", err)
	}
	if n := len(pub.Notices()); n != 1 {
		t.Fatalf("exactly one halt notice expected, got %d", n)
	}
}

func TestReferencePriceAtBoundary(t *testing.T) {
	b := newRefBook()
	if err := b.install(reference{version: 7, price: 12345, effective: 10}); err != nil {
		t.Fatal(err)
	}
	if _, ok := b.priceAt(9, 7); ok {
		t.Fatal("reference must not be visible before effective time")
	}
	if p, ok := b.priceAt(10, 7); !ok || p != 12345 {
		t.Fatalf("reference visible exactly at effective time, got %d ok=%v", p, ok)
	}
}
