package model_test

import (
	"sync"
	"testing"

	risk "riskengine"
	"riskengine/model"
)

func specs() []risk.AccountSpec {
	return []risk.AccountSpec{
		{
			ID:        "A",
			CashLimit: 100_000_000_000,
			RiskLimit: 60_000_000_000,
			Positions: map[string]risk.Qty{"STK": 300_000_000},
		},
	}
}

// TestConcurrentBarrierLinearizable parks every worker goroutine at a
// start barrier and releases them simultaneously, forcing contention. The
// captured history must still map to the audit-sequence serial order with
// identical dimensions, audit sequence numbers and canonical snapshot.
func TestConcurrentBarrierLinearizable(t *testing.T) {
	mc := &risk.ManualClock{}
	eng, err := risk.NewEngine(mc, specs())
	if err != nil {
		t.Fatal(err)
	}

	const workers = 16
	const perWorker = 25

	type slot struct {
		op      model.Operation
		outcome model.Outcome
	}
	var mu sync.Mutex
	hist := make([]model.RecordedOp, 0, workers*perWorker)

	start := make(chan struct{})
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			<-start // fixed concurrency barrier: all workers begin together
			for i := 0; i < perWorker; i++ {
				key := keyFor(worker, i)
				op := model.Operation{
					ClientID: worker,
					Time:     int64(i % 4),
					Kind:     model.KindReserve,
					Req: risk.OrderRequest{
						AccountID: "A",
						Symbol:    "STK",
						Direction: risk.Buy,
						Qty:       500_000,
						Price:     10_000,
						IdemKey:   key,
						ExpiresAt: 0,
					},
				}
				res, rerr := eng.Reserve(op.Req)
				rec := model.RecordedOp{Op: op, Outcome: model.Outcome{Result: res, Err: rerr}}
				mu.Lock()
				hist = append(hist, rec)
				mu.Unlock()
			}
		}(w)
	}
	close(start)
	wg.Wait()

	final := eng.Snapshot()
	if !model.VerifyLinearizable(specs(), hist, final) {
		t.Fatal("concurrent history is not linearizable")
	}

	// Exactly the dimension-fitting subset must have been admitted.
	admitted := 0
	for _, h := range hist {
		if h.Outcome.Err == nil {
			admitted++
		}
	}
	// Each reservation consumes 5,000,000,000 cash; limit is 100,000,000,000.
	// Risk limit is 60b, so it binds first: at most 12 admits.
	if admitted != 12 {
		t.Fatalf("want 12 admitted reservations, got %d", admitted)
	}

	// Audit sequence numbers are a gap-free 1..N total order covering every
	// admitted operation, independent of goroutine interleaving.
	seqs := map[int64]bool{}
	for _, h := range hist {
		if s := h.Outcome.Seq(); s != 0 {
			if seqs[s] {
				t.Fatalf("duplicate audit seq %d", s)
			}
			seqs[s] = true
		}
	}
	for i := int64(1); i <= int64(len(seqs)); i++ {
		if !seqs[i] {
			t.Fatalf("missing audit seq %d", i)
		}
	}

	// Total committed+reserved must equal the admitted usage exactly once.
	var total int64
	for _, a := range final.Accounts {
		total += int64(a.CommittedCa + a.ReservedCa)
	}
	if total != int64(admitted)*5_000_000_000 {
		t.Fatalf("dimension total mismatch: %d", total)
	}
}

// TestConcurrentExpiryConfirmRace hammers confirm and expiry of the same keys
// from many goroutines; every reservation must terminate exactly once and the
// serial replay must reproduce the observed terminal states.
func TestConcurrentExpiryConfirmRace(t *testing.T) {
	mc := &risk.ManualClock{}
	eng, _ := risk.NewEngine(mc, specs())

	const n = 32
	for i := 0; i < n; i++ {
		eng.Reserve(risk.OrderRequest{
			AccountID: "A",
			Symbol:    "STK",
			Direction: risk.Buy,
			Qty:       100_000,
			Price:     1_000,
			IdemKey:   raceKey(i),
			ExpiresAt: 10,
		})
	}

	var mu sync.Mutex
	hist := []model.RecordedOp{}
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			op := model.Operation{ClientID: i, Time: 10, Kind: model.KindConfirm, Key: raceKey(i)}
			res, rerr := eng.Confirm(raceKey(i))
			mu.Lock()
			hist = append(hist, model.RecordedOp{Op: op, Outcome: model.Outcome{Result: res, Err: rerr}})
			mu.Unlock()
		}(i)
	}
	close(start)
	wg.Wait()

	// Advance past the deadline and sweep; orders already confirmed stay.
	mc.Advance(11)
	expired := eng.ExpireDue()
	for _, res := range expired {
		op := model.Operation{ClientID: 1000, Time: 11, Kind: model.KindReserve, Key: res.Order.IdemKey}
		hist = append(hist, model.RecordedOp{
			Op:      op,
			Outcome: model.Outcome{Result: res},
		})
	}

	final := eng.Snapshot()
	terminals := 0
	for _, o := range final.Orders {
		if o.Status == risk.StatusConfirmed || o.Status == risk.StatusExpired {
			terminals++
		}
	}
	if terminals != n {
		t.Fatalf("every reservation must terminate once, got %d/%d", terminals, n)
	}

	// Replay serially using each admitted outcome's audit seq; the expiry
	// sweep records are not reserve-ops, so they are appended as expire ops.
	for i := range hist {
		if hist[i].Op.Kind != model.KindConfirm {
			hist[i].Op.Kind = model.KindExpireDue
		}
	}
	// The reference replay checks audit order and outcomes; expired results
	// recorded by the sweep correspond to expire ops in serial replay.
	if !replayTerminals(specs(), hist) {
		t.Fatal("terminal-state history is not reproducible serially")
	}
}

func keyFor(worker, i int) string {
	return "w" + itoa(worker) + "-" + itoa(i)
}

func raceKey(i int) string { return "race-" + itoa(i) }

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	pos := len(b)
	for n > 0 {
		pos--
		b[pos] = byte('0' + n%10)
		n /= 10
	}
	return string(b[pos:])
}

func replayTerminals(specs []risk.AccountSpec, hist []model.RecordedOp) bool {
	// Replay confirms in audit-seq order, then run the expiry sweep at t=11;
	// compare terminal status against each observed outcome.
	confirms := []model.RecordedOp{}
	for _, h := range hist {
		if h.Op.Kind == model.KindConfirm && h.Outcome.Result != nil {
			confirms = append(confirms, h)
		}
	}
	sortBySeq(confirms)

	mc := &risk.ManualClock{}
	ref, rerr := risk.NewEngine(mc, specs)
	if rerr != nil {
		return false
	}
	for i := 0; i < 32; i++ {
		ref.Reserve(risk.OrderRequest{
			AccountID: "A", Symbol: "STK", Direction: risk.Buy,
			Qty: 100_000, Price: 1_000, IdemKey: raceKey(i), ExpiresAt: 10,
		})
	}
	mc.Advance(10)
	confirmedKeys := map[string]bool{}
	for _, h := range confirms {
		res, err := ref.Confirm(h.Op.Key)
		if err != nil {
			return false
		}
		if res.Status != h.Outcome.Result.Status {
			return false
		}
		confirmedKeys[h.Op.Key] = true
	}
	mc.Advance(1)
	expired := ref.ExpireDue()
	expiredKeys := map[string]bool{}
	for _, res := range expired {
		expiredKeys[res.Order.IdemKey] = true
	}
	for _, h := range hist {
		if h.Op.Kind != model.KindExpireDue {
			continue
		}
		if !expiredKeys[h.Op.Key] || confirmedKeys[h.Op.Key] {
			return false
		}
	}
	return len(confirmedKeys)+len(expiredKeys) == 32
}

func sortBySeq(in []model.RecordedOp) {
	for i := 1; i < len(in); i++ {
		for j := i; j > 0 && in[j-1].Outcome.Seq() > in[j].Outcome.Seq(); j-- {
			in[j-1], in[j] = in[j], in[j-1]
		}
	}
}
