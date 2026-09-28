package risk

import "testing"

func newTestEngine(t *testing.T) *Engine {
	t.Helper()
	e := NewEngine()
	if err := e.AddAccount(AccountSpec{
		ID:   "acct1",
		Cash: 1000,
		Risk: 3000,
		Position: map[string]int64{
			"AAA": 50,
		},
	}); err != nil {
		t.Fatalf("AddAccount: %v", err)
	}
	return e
}

func TestReserveHappyPath(t *testing.T) {
	e := newTestEngine(t)
	r := e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100})
	if !r.OK || r.Status != StatusReserved || r.Seq != 1 {
		t.Fatalf("unexpected reserve result: %+v", r)
	}
	a := e.accounts["acct1"]
	if a.cash.reserved != 50 || a.risk.reserved != 50 {
		t.Fatalf("buckets wrong: %+v %+v", a.cash, a.risk)
	}
}

func TestMultiDimensionShortage(t *testing.T) {
	// notional 2000 fits once against the 3000 risk limit but not twice.
	o := func(key string) Order {
		return Order{Account: "acct1", Symbol: "AAA", Side: Buy,
			Price: 100, Qty: 20, Key: key, ExpireAt: 100}
	}
	// Risk is the binding dimension only when cash is looser: use a dedicated
	// engine with cash headroom so that risk (3000) rejects first.
	er := NewEngine()
	if err := er.AddAccount(AccountSpec{ID: "acct1", Cash: 100000, Risk: 3000,
		Position: map[string]int64{"AAA": 100000}}); err != nil {
		t.Fatal(err)
	}
	if r := er.Reserve(o("k1")); !r.OK {
		t.Fatalf("k1 should fit: %+v", r)
	}
	r := er.Reserve(o("k2"))
	if r.OK || r.Code != CodeInsufficientRisk {
		t.Fatalf("risk dimension should reject: %+v", r)
	}
	// A rejected reservation must not change any dimension.
	a := er.accounts["acct1"]
	if a.cash.reserved != 2000 || a.risk.reserved != 2000 {
		t.Fatalf("rejected call mutated state: %+v %+v", a.cash, a.risk)
	}

	// Cash is the binding dimension here: cash allows 1000, risk allows 3000.
	e2 := newTestEngine(t)
	r = e2.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 200, Qty: 6, Key: "kx", ExpireAt: 100})
	if r.OK || r.Code != CodeInsufficientCash {
		t.Fatalf("cash dimension should reject: %+v", r)
	}

	// Sell exceeding available holdings.
	e3 := newTestEngine(t)
	r = e3.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Sell,
		Price: 1, Qty: 51, Key: "ks", ExpireAt: 100})
	if r.OK || r.Code != CodeInsufficientPosition {
		t.Fatalf("position dimension should reject: %+v", r)
	}

	// Sell of an unknown symbol.
	r = e3.Reserve(Order{Account: "acct1", Symbol: "ZZZ", Side: Sell,
		Price: 1, Qty: 1, Key: "kz", ExpireAt: 100})
	if r.OK || r.Code != CodeInsufficientPosition {
		t.Fatalf("unknown symbol sell should reject: %+v", r)
	}
}

func TestClassification(t *testing.T) {
	e := newTestEngine(t)
	r := e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Side(9),
		Price: 1, Qty: 1, Key: "bad-side"})
	if r.OK || r.Code != CodeInvalidArgument {
		t.Fatalf("bad side: %+v", r)
	}
	r = e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: -1, Qty: 1, Key: "neg"})
	if r.OK || r.Code != CodeNegative {
		t.Fatalf("negative: %+v", r)
	}
	r = e.Reserve(Order{Account: "ghost", Symbol: "AAA", Side: Buy,
		Price: 1, Qty: 1, Key: "noacct"})
	if r.OK || r.Code != CodeUnknownAccount {
		t.Fatalf("unknown account: %+v", r)
	}
	r = e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 1, Qty: 1, Key: ""})
	if r.OK || r.Code != CodeInvalidArgument {
		t.Fatalf("empty key: %+v", r)
	}
}

func TestIdempotencyReplayAndConflict(t *testing.T) {
	e := newTestEngine(t)
	o := Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100}
	first := e.Reserve(o)
	again := e.Reserve(o)
	if !again.OK || !again.Replay || again.Seq != first.Seq {
		t.Fatalf("replay must return first result: %+v vs %+v", first, again)
	}
	// No extra reservation on replay.
	if e.accounts["acct1"].cash.reserved != 50 {
		t.Fatalf("replay changed balances")
	}
	conflict := o
	conflict.Qty = 6
	r := e.Reserve(conflict)
	if r.OK || r.Code != CodeDuplicateConflict {
		t.Fatalf("parameter conflict: %+v", r)
	}
}

func TestStateMachineTerminalOnce(t *testing.T) {
	e := newTestEngine(t)
	o := Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100}
	e.Reserve(o)

	// Confirm promotes reserved -> committed.
	r := e.Confirm("k1", 100)
	if !r.OK || r.Status != StatusConfirmed {
		t.Fatalf("confirm: %+v", r)
	}
	a := e.accounts["acct1"]
	if a.cash.reserved != 0 || a.cash.committed != 50 ||
		a.risk.reserved != 0 || a.risk.committed != 50 {
		t.Fatalf("confirm balances wrong: %+v %+v", a.cash, a.risk)
	}
	// Repeated confirm is an idempotent replay.
	r = e.Confirm("k1", 100)
	if !r.OK || !r.Replay {
		t.Fatalf("duplicate confirm should replay: %+v", r)
	}
	// Confirm cannot be released; it is terminal.
	r = e.Release("k1", 100)
	if r.OK || r.Code != CodeInvalidState {
		t.Fatalf("confirmed release must fail: %+v", r)
	}
	// Expiry must not reclaim a confirmed item.
	if keys := e.ExpireDue(1000); len(keys) != 0 {
		t.Fatalf("confirmed item expired: %v", keys)
	}

	// Released reservation is also terminal.
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k2", ExpireAt: 100})
	r = e.Release("k2", 50)
	if !r.OK || r.Status != StatusReleased {
		t.Fatalf("release: %+v", r)
	}
	if a.cash.reserved != 0 || a.risk.reserved != 0 {
		t.Fatalf("release did not free balances: %+v %+v", a.cash, a.risk)
	}
	r = e.Release("k2", 50)
	if !r.OK || !r.Replay {
		t.Fatalf("duplicate release should replay: %+v", r)
	}
	r = e.Confirm("k2", 50)
	if r.OK || r.Code != CodeInvalidState {
		t.Fatalf("released confirm must fail: %+v", r)
	}

	// Unknown key operations.
	if r = e.Confirm("missing", 1); r.OK || r.Code != CodeUnknownKey {
		t.Fatalf("unknown confirm: %+v", r)
	}
	if r = e.Release("missing", 1); r.OK || r.Code != CodeUnknownKey {
		t.Fatalf("unknown release: %+v", r)
	}
}

func TestSellConfirmConsumesPosition(t *testing.T) {
	e := newTestEngine(t)
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Sell,
		Price: 10, Qty: 20, Key: "s1", ExpireAt: 100})
	p := e.accounts["acct1"].position["AAA"]
	if p.reserved != 20 || p.committed != 0 {
		t.Fatalf("sell reserve: %+v", p)
	}
	r := e.Confirm("s1", 100)
	if !r.OK {
		t.Fatalf("sell confirm: %+v", r)
	}
	if p.reserved != 0 || p.committed != 20 {
		t.Fatalf("sell confirm balances: %+v", p)
	}
	// Only 30 of 50 remain usable.
	r = e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Sell,
		Price: 10, Qty: 31, Key: "s2", ExpireAt: 100})
	if r.OK || r.Code != CodeInsufficientPosition {
		t.Fatalf("post-confirm availability wrong: %+v", r)
	}
}
