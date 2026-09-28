package circuitbreaker

import (
	"strings"
	"testing"
)

func testRules() RuleSet {
	return RuleSet{
		Version:  1,
		Horizon:  10,
		Level1:   500,
		Level2:   1000,
		Level3:   2000,
		Recover:  100,
		Halt1:    5,
		Halt2:    10,
		Cooldown: 3,
	}
}

func newTestEngine(t *testing.T) (*Engine, *MemoryPublisher, *LogicalClock) {
	t.Helper()
	clock := NewLogicalClock(0)
	pub := NewMemoryPublisher()
	engine, err := NewEngine(clock, pub, testRules(), 10000)
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	return engine, pub, clock
}

func trade(tm Time, seq Seq, price Price) Event {
	return Event{Kind: KindTrade, Time: tm, Source: seq, RuleVersion: 1, Price: price}
}

func TestThresholdEqualityTriggersLevel1(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	// 9500 vs 10000 = exactly 500 bps drop: level1 triggers on equality.
	if err := engine.Apply(trade(1, 1, 9500)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	notices := pub.Notices()
	if len(notices) != 1 {
		t.Fatalf("want 1 notice, got %d", len(notices))
	}
	if notices[0].State != StateHaltedLevel1 || notices[0].Tradable {
		t.Fatalf("want halted_level1 non-tradable, got %s tradable=%v",
			notices[0].State, notices[0].Tradable)
	}
}

func TestJustBelowThresholdStaysOpen(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	// drawdown floor(499/10000*10000)=499 bps stays open.
	if err := engine.Apply(trade(1, 1, 9501)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	if len(pub.Notices()) != 0 || engine.StateName() != StateOpen {
		t.Fatalf("market should stay open")
	}
}

func TestLevel2AndClosedBreaches(t *testing.T) {
	engine, _, _ := newTestEngine(t)
	if err := engine.Apply(trade(1, 1, 9000)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	if engine.StateName() != StateHaltedLevel2 {
		t.Fatalf("want halted_level2, got %s", engine.StateName())
	}
	if err := engine.Apply(trade(2, 2, 8000)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	if engine.StateName() != StateClosed {
		t.Fatalf("want closed, got %s", engine.StateName())
	}
}

func TestEscalationExtendsHalt(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	if err := engine.Apply(trade(1, 1, 9500)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	if err := engine.Apply(trade(2, 2, 9000)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	notices := pub.Notices()
	if notices[len(notices)-1].State != StateHaltedLevel2 {
		t.Fatalf("want escalation to level2, got %s", notices[len(notices)-1].State)
	}
	if got := notices[len(notices)-1].Explanation; !strings.Contains(got, "escalated") {
		t.Fatalf("explanation should record escalation, got %q", got)
	}
}

func TestTimeRegressionRejected(t *testing.T) {
	engine, _, _ := newTestEngine(t)
	if err := engine.Apply(trade(5, 1, 9900)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	err := engine.Apply(trade(4, 2, 9900))
	if err == nil {
		t.Fatal("expected time regression error")
	}
	if rejectReason(err) != RejectTimeRegress {
		t.Fatalf("want %s, got %v", RejectTimeRegress, err)
	}
}

func TestDuplicateIdempotentAndConflictRejected(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	ev := trade(1, 1, 9500)
	if err := engine.Apply(ev); err != nil {
		t.Fatalf("apply: %v", err)
	}
	// exact duplicate applies at most once.
	if err := engine.Apply(ev); err != nil {
		t.Fatalf("duplicate should be idempotent: %v", err)
	}
	if len(pub.Notices()) != 1 {
		t.Fatalf("duplicate must not publish twice, got %d", len(pub.Notices()))
	}
	// same seq, different payload: conflicting duplicate.
	conflict := trade(1, 1, 9000)
	err := engine.Apply(conflict)
	if rejectReason(err) != RejectDuplicateConflict {
		t.Fatalf("want duplicate conflict, got %v", err)
	}
}

func TestMissingReferenceForUnknownVersion(t *testing.T) {
	engine, _, _ := newTestEngine(t)
	ev := trade(1, 1, 9500)
	ev.RuleVersion = 2
	err := engine.Apply(ev)
	if rejectReason(err) != RejectIllegalRules {
		t.Fatalf("want illegal rules for unknown version, got %v", err)
	}
}

func TestHaltExpiryEntersCooldownThenReopens(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	if err := engine.Apply(trade(1, 1, 9500)); err != nil {
		t.Fatalf("apply: %v", err)
	}
	// halt1 lasts 5 ticks: at time 6 it expires into cooldown.
	if err := engine.Advance(6); err != nil {
		t.Fatalf("advance: %v", err)
	}
	if engine.StateName() != StateCooldown {
		t.Fatalf("want cooldown, got %s", engine.StateName())
	}
	// cooldown lasts 3 ticks: at time 9 market reopens.
	if err := engine.Advance(9); err != nil {
		t.Fatalf("advance: %v", err)
	}
	if engine.StateName() != StateOpen {
		t.Fatalf("want open, got %s", engine.StateName())
	}
	// no notice may ever claim tradable while suspended.
	for _, n := range pub.Notices() {
		if n.State != StateOpen && n.Tradable {
			t.Fatalf("tradable notice published in %s", n.State)
		}
	}
}

func TestNoTradableNoticeWhileHalted(t *testing.T) {
	engine, pub, _ := newTestEngine(t)
	_ = engine.Apply(trade(1, 1, 9000))
	_ = engine.Advance(20)
	for _, n := range pub.Notices() {
		if n.State != StateOpen && n.Tradable {
			t.Fatalf("tradable notice in state %s", n.State)
		}
	}
}

func rejectReason(err error) RejectReason {
	if r, ok := err.(*RejectError); ok {
		return r.Reason
	}
	return RejectReason("other")
}
