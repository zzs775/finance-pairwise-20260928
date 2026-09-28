package circuitbreaker

import "testing"

func TestFrozenPrioritySameTimestamp(t *testing.T) {
	engine, pub, _ := newTestEngine(t)

	// All inputs at time 1. Rules switch to v2, reference for v2 installed,
	// trade under v2 at 5% drop. Regardless of the shuffled input order the
	// frozen priority must dominate.
	rulesV2 := testRules()
	rulesV2.Version = 2
	rulesV2.Horizon = 5
	rulesV2.Halt1 = 10
	events := []Event{
		{Kind: KindTrade, Time: 1, Source: 3, RuleVersion: 2, Price: 9500},
		{Kind: KindRules, Time: 1, Source: 2, RuleVersion: 2, Rules: rulesV2},
		{Kind: KindReference, Time: 1, Source: 1, RuleVersion: 2, Price: 10000},
	}
	if err := engine.ApplyBatch(events); err != nil {
		t.Fatalf("ApplyBatch: %v", err)
	}
	// reference v2 and rules v2 are applied before the trade, so the trade
	// resolves against reference v2 and halts at level1; the recovery command
	// runs last but fails because no v2 recovery trade exists yet.
	last := pub.Notices()[len(pub.Notices())-1]
	if last.State != StateHaltedLevel1 {
		t.Fatalf("trade must be evaluated under v2, got %s", last.State)
	}
	if engine.Summary().Accepted != 3 {
		t.Fatalf("want 3 accepted, got %d", engine.Summary().Accepted)
	}

	// At time 7 the halted market only retains trades within horizon=5, so
	// the 9500 trade is gone; a near-reference trade (processed first under
	// frozen ordering) satisfies recovery and reopens before the command.
	recoveryBatch := []Event{
		{Kind: KindRecover, Time: 7, Source: 5, RuleVersion: 2},
		{Kind: KindTrade, Time: 7, Source: 4, RuleVersion: 2, Price: 9991},
	}
	err := engine.ApplyBatch(recoveryBatch)
	if engine.StateName() != StateOpen {
		t.Fatalf("market should recover before the command, got %s (%v)", engine.StateName(), err)
	}
	if engine.Summary().Rejected != 1 {
		t.Fatalf("recovery command in open must be rejected, got %d", engine.Summary().Rejected)
	}
}

func TestTimerHeapStableOrdering(t *testing.T) {
	h := newTimerHeap()
	first := h.push(10, timerHaltExpire)
	second := h.push(10, timerCooldownExpire)
	third := h.push(12, timerHaltExpire)

	due := h.due(10)
	if len(due) != 2 {
		t.Fatalf("want 2 due timers, got %d", len(due))
	}
	if due[0].id != first.id || due[1].id != second.id {
		t.Fatalf("same-due timers must fire in insertion order")
	}
	// repeated due() must not fire the same timers again.
	if len(h.due(11)) != 0 {
		t.Fatal("expired timers fired twice")
	}
	if h.peek().id != third.id {
		t.Fatalf("remaining timer must be the t=12 entry")
	}
}

func TestTimerRemoveCancelsPending(t *testing.T) {
	h := newTimerHeap()
	h.push(5, timerHaltExpire)
	h.remove(timerHaltExpire)
	if len(h.due(100)) != 0 {
		t.Fatal("removed timer must never fire")
	}
}
