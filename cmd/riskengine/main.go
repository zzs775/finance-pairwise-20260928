package main

import (
	"fmt"
	"os"

	risk "riskengine"
)

func main() {
	e := risk.NewEngine()

	if err := e.AddAccount(risk.AccountSpec{
		ID:       "acct-local-1",
		Cash:     1_000_000,
		Risk:     3_000_000,
		Position: map[string]int64{"AAA": 500, "BBB": 200},
	}); err != nil {
		fail(err)
	}

	orders := []risk.Order{
		{Account: "acct-local-1", Symbol: "AAA", Side: risk.Buy,
			Price: 1000, Qty: 100, Key: "order-1", ExpireAt: 100},
		{Account: "acct-local-1", Symbol: "BBB", Side: risk.Sell,
			Price: 2000, Qty: 40, Key: "order-2", ExpireAt: 100},
		{Account: "acct-local-1", Symbol: "AAA", Side: risk.Buy,
			Price: 1000, Qty: 100, Key: "order-1", ExpireAt: 100}, // replay
		{Account: "acct-local-1", Symbol: "AAA", Side: risk.Buy,
			Price: 5_000_000, Qty: 1, Key: "order-3", ExpireAt: 100}, // rejected
		{Account: "acct-local-1", Symbol: "AAA", Side: risk.Sell,
			Price: 1000, Qty: 50, Key: "order-4", ExpireAt: 50}, // expires
	}
	for _, o := range orders {
		r := e.Reserve(o)
		fmt.Printf("reserve %-8s -> ok=%-5v code=%-22v replay=%-5v seq=%d\n",
			o.Key, r.OK, r.Code.String(), r.Replay, r.Seq)
	}

	fmt.Printf("confirm  order-1 @100 -> %s\n", status(e.Confirm("order-1", 100)))
	fmt.Printf("confirm  order-1 @100 -> %s (idempotent)\n", status(e.Confirm("order-1", 100)))
	fmt.Printf("expire   @49         -> %v\n", e.ExpireDue(49))
	fmt.Printf("expire   @50         -> %v\n", e.ExpireDue(50))
	fmt.Printf("release  order-2 @60 -> %s\n", status(e.Release("order-2", 60)))

	snap, err := e.Snapshot().Canonical()
	if err != nil {
		fail(err)
	}
	if err := os.WriteFile("snapshot.json", snap, 0o644); err != nil {
		fail(err)
	}
	fmt.Println("wrote canonical snapshot to snapshot.json")

	restored := risk.NewEngine()
	if err := restored.Restore(e.Snapshot()); err != nil {
		fail(err)
	}
	b, _ := restored.Snapshot().Canonical()
	fmt.Printf("restore round-trip identical: %v\n", string(b) == string(snap))
}

func status(r risk.Result) string {
	return fmt.Sprintf("ok=%v code=%s status=%s replay=%v",
		r.OK, r.Code.String(), r.Status.String(), r.Replay)
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "error:", err)
	os.Exit(1)
}
