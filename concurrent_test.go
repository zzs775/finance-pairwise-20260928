package risk

import (
	"bytes"
	"fmt"
	"sync"
	"testing"
)

// op describes one operation replayed into the serial reference model.
type op struct {
	name string
	key  string
	now  uint64
	ord  Order
}

// call records one concurrent invocation together with its captured result and
// its position in the engine's audit log (the linearization point).
type call struct {
	op     op
	result Result
}

// TestConcurrentLinearizability fires a fixed number of goroutines from one
// start barrier, each running a deterministic script against distinct
// idempotency keys plus shared contention. The resulting audit order is then
// replayed into SerialModel: results, totals, audit sequence numbers and the
// canonical snapshot must all be identical.
func TestConcurrentLinearizability(t *testing.T) {
	const goroutines = 16

	e := NewEngine()
	ref := NewSerialModel()
	spec := AccountSpec{
		ID:       "acct1",
		Cash:     1_000_000,
		Risk:     3_000_000,
		Position: map[string]int64{"AAA": 2_000_000},
	}
	if err := e.AddAccount(spec); err != nil {
		t.Fatal(err)
	}
	if err := ref.AddAccount(spec); err != nil {
		t.Fatal(err)
	}

	var start sync.WaitGroup
	start.Add(1)
	var wg sync.WaitGroup
	wg.Add(goroutines)

	results := make([][]call, goroutines)

	for g := 0; g < goroutines; g++ {
		g := g
		go func() {
			defer wg.Done()
			start.Wait()
			local := make([]call, 0, 6)
			mk := func(o Order) Result {
				r := e.Reserve(o)
				local = append(local, call{
					op: op{name: "reserve", key: o.Key, ord: o}, result: r,
				})
				return r
			}
			keyA := fmt.Sprintf("g%d-a", g)
			keyB := fmt.Sprintf("g%d-b", g)
			keyS := fmt.Sprintf("g%d-s", g)
			mk(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
				Price: 10, Qty: 100, Key: keyA, ExpireAt: 1000})
			mk(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
				Price: 10, Qty: 100, Key: keyB, ExpireAt: 1000})
			// Duplicate-key retry: must replay the first answer.
			mk(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
				Price: 10, Qty: 100, Key: keyA, ExpireAt: 1000})
			mk(Order{Account: "acct1", Symbol: "AAA", Side: Sell,
				Price: 10, Qty: 50, Key: keyS, ExpireAt: 1000})

			cr := e.Confirm(keyA, 500)
			local = append(local, call{
				op: op{name: "confirm", key: keyA, now: 500}, result: cr,
			})
			// Repeated confirm concurrently-safe and idempotent.
			cr = e.Confirm(keyA, 500)
			local = append(local, call{
				op: op{name: "confirm", key: keyA, now: 500}, result: cr,
			})
			rl := e.Release(keyB, 500)
			local = append(local, call{
				op: op{name: "release", key: keyB, now: 500}, result: rl,
			})
			results[g] = local
		}()
	}

	start.Done()
	wg.Wait()

	// Nothing should have expired: all expiries are at tick 1000 and the
	// confirm/release calls use tick 500.
	if keys := e.ExpireDue(500); len(keys) != 0 {
		t.Fatalf("unexpected early expiry: %v", keys)
	}

	audit := e.Audit()

	// 1) Audit entries carry a dense, unique sequence 1..N.
	seen := make(map[uint64]bool, len(audit))
	for i, a := range audit {
		if a.Seq != uint64(i+1) {
			t.Fatalf("audit seq not dense at %d: %d", i, a.Seq)
		}
		if seen[a.Seq] {
			t.Fatalf("duplicate audit seq %d", a.Seq)
		}
		seen[a.Seq] = true
	}

	// 2) Replay the audited history in its exact linearization order into the
	// serial model and compare every audited result. Recover each successful
	// reservation's parameters from the goroutine call logs.
	firstOrder := make(map[string]Order, len(audit))
	for _, calls := range results {
		for _, c := range calls {
			if c.op.name == "reserve" && c.result.OK {
				firstOrder[c.op.key] = c.op.ord
			}
		}
	}

	for _, a := range audit {
		var rr Result
		switch a.Op {
		case "reserve":
			rr = ref.Reserve(firstOrder[a.Key])
		case "confirm":
			rr = ref.Confirm(a.Key, a.Tick)
		case "release":
			rr = ref.Release(a.Key, a.Tick)
		case "expire":
			rr = Result{OK: true, Code: CodeOK, Key: a.Key,
				Seq: a.Seq, Status: StatusExpired}
		}
		if !resultsEqual(rr, a.Result) {
			t.Fatalf("%s replay mismatch for %s: got %+v want %+v",
				a.Op, a.Key, rr, a.Result)
		}
	}

	// 3) Every goroutine-observed result must be explained by some legal
	// serial state: re-derive each call's answer by replaying all calls in
	// audit order then invoking the terminal op against that serial state is
	// complex; instead verify the idempotency/terminal invariants that the
	// serial order imposes.
	for _, calls := range results {
		for _, c := range calls {
			switch c.op.name {
			case "reserve":
				// Successful duplicate retries replay with Replay=true.
				if c.result.OK && !c.result.Replay {
					if c.result.Seq == 0 || c.result.Status != StatusReserved {
						t.Fatalf("first reserve result malformed: %+v", c.result)
					}
				}
				if !c.result.OK && c.result.Code == CodeDuplicateConflict {
					t.Fatalf("conflicting retry with identical params: %+v", c.result)
				}
			case "confirm":
				if !c.result.OK {
					t.Fatalf("unexpected failed confirm: %+v", c.result)
				}
			case "release":
				if !c.result.OK {
					t.Fatalf("unexpected failed release: %+v", c.result)
				}
			}
		}
	}

	// 4) Totals, audit sequences and canonical snapshots are identical.
	be, _ := e.Snapshot().Canonical()
	br, _ := ref.Snapshot().Canonical()
	if !bytes.Equal(be, br) {
		t.Fatalf("snapshots differ after concurrent run:\n%s\n----\n%s", be, br)
	}

	// Engine totals: committed + reserved never exceeded limits.
	acc := e.accounts["acct1"]
	if acc.cash.committed+acc.cash.reserved > acc.cashTot ||
		acc.risk.committed+acc.risk.reserved > acc.riskTot {
		t.Fatalf("limit invariant broken: %+v %+v", acc.cash, acc.risk)
	}
	p := acc.position["AAA"]
	if p.committed+p.reserved > p.total {
		t.Fatalf("position invariant broken: %+v", p)
	}

	// 5) Determinism: rerunning the same audited history on a fresh engine
	// yields the identical canonical snapshot.
	again := NewEngine()
	if err := again.AddAccount(spec); err != nil {
		t.Fatal(err)
	}
	for _, a := range audit {
		switch a.Op {
		case "reserve":
			again.Reserve(firstOrder[a.Key])
		case "confirm":
			again.Confirm(a.Key, a.Tick)
		case "release":
			again.Release(a.Key, a.Tick)
		case "expire":
			again.ExpireDue(a.Tick)
		}
	}
	ba, _ := again.Snapshot().Canonical()
	if !bytes.Equal(ba, be) {
		t.Fatalf("history replay is not deterministic")
	}
}

// TestConcurrentScarceContention sizes every dimension so that exactly one
// order can fit, then has many goroutines race for it. Exactly one distinct
// reservation must win regardless of scheduling; the shared-key retries must
// replay that single winner.
func TestConcurrentScarceContention(t *testing.T) {
	const racers = 32

	e := NewEngine()
	if err := e.AddAccount(AccountSpec{
		ID:       "tight",
		Cash:     1000,
		Risk:     1000,
		Position: map[string]int64{"AAA": 100},
	}); err != nil {
		t.Fatal(err)
	}

	var start sync.WaitGroup
	start.Add(1)
	var wg sync.WaitGroup
	wg.Add(racers)

	var mu sync.Mutex
	accepted := make(map[string]int)
	shared := make(map[string]int) // results for the contended shared key

	for g := 0; g < racers; g++ {
		g := g
		go func() {
			defer wg.Done()
			start.Wait()
			var r Result
			if g%2 == 0 {
				// All even goroutines contend on the SAME idempotency key.
				r = e.Reserve(Order{Account: "tight", Symbol: "AAA", Side: Buy,
					Price: 10, Qty: 100, Key: "shared", ExpireAt: 1000})
				mu.Lock()
				shared[fmt.Sprintf("%v:%v", r.OK, r.Replay)]++
				mu.Unlock()
			} else {
				r = e.Reserve(Order{Account: "tight", Symbol: "AAA", Side: Buy,
					Price: 10, Qty: 100, Key: fmt.Sprintf("uniq-%d", g),
					ExpireAt: 1000})
			}
			if r.OK {
				mu.Lock()
				accepted[r.Key]++
				mu.Unlock()
			}
		}()
	}
	start.Done()
	wg.Wait()

	if len(accepted) != 1 {
		t.Fatalf("exactly one distinct key must win, got: %v", accepted)
	}
	// If the winner is "shared", all even-goroutine retries also report OK as
	// replays. Count only non-replay wins for the scarcity assertion.
	firstWins := 0
	uniqWins := 0
	for key, n := range accepted {
		if key == "shared" {
			firstWins = 1
		} else {
			uniqWins += n
		}
	}
	if firstWins+uniqWins != 1 {
		t.Fatalf("exactly one first-call win expected, got first=%d uniq=%d",
			firstWins, uniqWins)
	}

	// The shared key is either the single winner (one first + many replays)
	// or everyone lost to a unique-key winner.
	if winner, ok := accepted["shared"]; ok {
		if winner != racers/2 {
			t.Fatalf("all even racers should observe the shared winner: %d", winner)
		}
		if shared["true:false"] != 1 || shared["true:true"] != racers/2-1 {
			t.Fatalf("shared-key replay split wrong: %v", shared)
		}
	}

	// Balances: the single winner reserved exactly the capacity.
	acc := e.accounts["tight"]
	if acc.cash.reserved != 1000 || acc.risk.reserved != 1000 ||
		acc.cash.committed != 0 || acc.risk.committed != 0 {
		t.Fatalf("post-contention balances wrong: %+v %+v", acc.cash, acc.risk)
	}

	// Nothing fits afterwards and the losing call must not mutate state.
	r := e.Reserve(Order{Account: "tight", Symbol: "AAA", Side: Buy,
		Price: 1, Qty: 1, Key: "after", ExpireAt: 1000})
	if r.OK || (r.Code != CodeInsufficientCash && r.Code != CodeInsufficientRisk) {
		t.Fatalf("post-contention reserve: %+v", r)
	}

	// Snapshot agrees with the serial model replaying the audit history.
	ref := NewSerialModel()
	if err := ref.AddAccount(AccountSpec{
		ID: "tight", Cash: 1000, Risk: 1000,
		Position: map[string]int64{"AAA": 100},
	}); err != nil {
		t.Fatal(err)
	}
	audit := e.Audit()
	// Recover winning order parameters from the audit result keys by issuing
	// the same two possible order shapes.
	shape := func(key string) Order {
		return Order{Account: "tight", Symbol: "AAA", Side: Buy,
			Price: 10, Qty: 100, Key: key, ExpireAt: 1000}
	}
	for _, a := range audit {
		rr := ref.Reserve(shape(a.Key))
		if !resultsEqual(rr, a.Result) {
			t.Fatalf("contention replay mismatch on %s: %+v vs %+v",
				a.Key, rr, a.Result)
		}
	}
	be, _ := e.Snapshot().Canonical()
	br, _ := ref.Snapshot().Canonical()
	if !bytes.Equal(be, br) {
		t.Fatalf("contention snapshots differ")
	}
}

func resultsEqual(a, b Result) bool {
	return a.OK == b.OK && a.Code == b.Code && a.Key == b.Key &&
		a.Seq == b.Seq && a.Status == b.Status && a.Replay == b.Replay
}
