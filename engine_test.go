package riskengine

import "testing"

func testSpecs() []AccountSpec {
	return []AccountSpec{
		{
			ID:        "A",
			CashLimit: 100_000_000_000,
			RiskLimit: 80_000_000_000,
			Positions: map[string]Qty{"STK": 500_000_000},
		},
		{
			ID:        "B",
			CashLimit: 20_000_000_000,
			RiskLimit: 20_000_000_000,
			Positions: map[string]Qty{},
		},
	}
}

func buyReq(key, acct, sym string, qty Qty, price Amount, exp int64) OrderRequest {
	return OrderRequest{AccountID: acct, Symbol: sym, Direction: Buy, Qty: qty, Price: price, IdemKey: key, ExpiresAt: exp}
}

func sellReq(key, acct, sym string, qty Qty, price Amount, exp int64) OrderRequest {
	r := buyReq(key, acct, sym, qty, price, exp)
	r.Direction = Sell
	return r
}

func TestReserveHappyPath(t *testing.T) {
	mc := &ManualClock{}
	eng, err := NewEngine(mc, testSpecs())
	if err != nil {
		t.Fatal(err)
	}
	res, err := eng.Reserve(buyReq("k1", "A", "STK", 1_000_000, 30, 10))
	if err != nil {
		t.Fatalf("reserve: %v", err)
	}
	if res.Status != StatusReserved || res.Cash != 30_000_000 {
		t.Fatalf("unexpected result: %+v", res)
	}
	acc := eng.accounts["A"]
	if acc.resCash != 30_000_000 || acc.resRisk != 30_000_000 {
		t.Fatalf("reservation not debited: %+v", acc)
	}
}

func TestClassifiedRejections(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())

	cases := []struct {
		name string
		req  OrderRequest
		code ErrCode
	}{
		{"negative qty", buyReq("n1", "A", "STK", -1, 10, 5), ErrNegativeValue},
		{"zero price", buyReq("n2", "A", "STK", 1, 0, 5), ErrNegativeValue},
		{"unknown account", buyReq("n3", "X", "STK", 1, 10, 5), ErrUnknownAccount},
		{"cash short", buyReq("n4", "A", "STK", 100_000_000, 2000, 5), ErrInsufficientCash},
		{"risk short", buyReq("n5", "A", "STK", 1_000_000, 81_000, 5), ErrInsufficientLimit},
		{"position short", sellReq("n6", "A", "STK", 600_000_000, 1000, 5), ErrInsufficientPosition},
		{"expired in past", buyReq("n7", "A", "STK", 1, 1, 3), ErrOK},
	}
	for i, tc := range cases {
		if i == len(cases)-1 {
			mc.Advance(5)
			tc.code = ErrInvalidTransition
		}
		_, err := eng.Reserve(tc.req)
		if err == nil || err.Code != tc.code {
			t.Fatalf("%s: want %v, got %v", tc.name, tc.code, err)
		}
	}
}

func TestRejectionLeavesStateUntouched(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	before := eng.Snapshot()
	// Risk limit rejection even though cash and position exist.
	_, err := eng.Reserve(buyReq("big", "A", "STK", 1_000_000, 81_000, 100))
	if err == nil || err.Code != ErrInsufficientLimit {
		t.Fatalf("want risk rejection, got %v", err)
	}
	after := eng.Snapshot()
	if len(before.Orders) != len(after.Orders) {
		t.Fatal("order table changed after rejection")
	}
	for i := range before.Accounts {
		b, a := before.Accounts[i], after.Accounts[i]
		if b.CommittedCa != a.CommittedCa || b.ReservedCa != a.ReservedCa ||
			b.CommittedRi != a.CommittedRi || b.ReservedRi != a.ReservedRi ||
			len(b.Positions) != len(a.Positions) {
			t.Fatal("dimensions changed after rejected reservation")
		}
	}
}

func TestIdempotentReplayAndConflict(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	req := buyReq("k", "A", "STK", 1_000_000, 10_000, 100)
	r1, e1 := eng.Reserve(req)
	if e1 != nil {
		t.Fatal(e1)
	}
	r2, e2 := eng.Reserve(req)
	if e2 != nil || r1 != r2 {
		t.Fatal("idempotent replay must return the first result")
	}
	conf := req
	conf.Qty = 2_000_000
	_, err := eng.Reserve(conf)
	if err == nil || err.Code != ErrDuplicateConflict {
		t.Fatalf("want conflict, got %v", err)
	}
}

func TestStateMachineTerminalOnce(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	eng.Reserve(buyReq("c", "A", "STK", 1_000_000, 10_000, 100))

	if _, err := eng.Confirm("missing"); err == nil || err.Code != ErrUnknownOrder {
		t.Fatalf("want unknown order, got %v", err)
	}
	cf, err := eng.Confirm("c")
	if err != nil || cf.Status != StatusConfirmed {
		t.Fatalf("confirm: %v", err)
	}
	// Repeated confirm is idempotent and returns the first result.
	cf2, err := eng.Confirm("c")
	if err != nil || cf2 != cf {
		t.Fatal("repeated confirm must be idempotent")
	}
	// Any other terminal transition is rejected; each order ends once.
	if _, err := eng.Release("c"); err == nil || err.Code != ErrInvalidTransition {
		t.Fatalf("release after confirm must fail, got %v", err)
	}
	acc := eng.accounts["A"]
	if acc.committedCa != 10_000_000_000 || acc.resCash != 0 {
		t.Fatalf("committed state wrong: %+v", acc)
	}
}

func TestReleaseReturnsResources(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	if _, err := eng.Reserve(sellReq("s", "A", "STK", 200_000_000, 100, 100)); err != nil {
		t.Fatal(err)
	}
	if _, err := eng.Release("s"); err != nil {
		t.Fatal(err)
	}
	acc := eng.accounts["A"]
	if acc.resRisk != 0 || acc.resPosition["STK"] != 0 {
		t.Fatalf("resources not returned: %+v", acc)
	}
	// After release the full position can be reserved again.
	_, err := eng.Reserve(sellReq("s2", "A", "STK", 500_000_000, 100, 100))
	if err != nil {
		t.Fatalf("re-reserve after release: %v", err)
	}
}

func TestCommittedPlusReservedBounded(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	eng.Reserve(buyReq("a1", "A", "STK", 1_000_000, 40_000, 100)) // 40,000,000,000
	eng.Confirm("a1")
	// Committed 40b; only 60b cash room remains.
	if _, err := eng.Reserve(buyReq("a2", "A", "STK", 1_000_000, 61_000, 100)); err == nil || err.Code != ErrInsufficientCash {
		t.Fatalf("committed+reserved bound, got %v", err)
	}
	if _, err := eng.Reserve(buyReq("a3", "A", "STK", 1_000_000, 20_000, 100)); err != nil {
		t.Fatalf("exactly at limit should pass: %v", err)
	}
}

func TestOverflowClassified(t *testing.T) {
	mc := &ManualClock{}
	eng, _ := NewEngine(mc, testSpecs())
	req := buyReq("ov", "A", "STK", Qty(maxQty), Amount(maxAmt), 100)
	_, err := eng.Reserve(req)
	if err == nil || err.Code != ErrOverflow {
		t.Fatalf("want overflow, got %v", err)
	}
	// add64 / mul64 direct checks.
	if _, err := add64(1<<62, 1<<62); err == nil || err.Code != ErrOverflow {
		t.Fatal("add64 overflow")
	}
	if v, err := mul64(1<<62, 4); err == nil || v != 0 {
		t.Fatal("mul64 overflow")
	}
}
