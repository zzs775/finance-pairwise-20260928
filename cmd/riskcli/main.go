// Command riskcli runs a fully local, synthetic demonstration of the
// pre-trade multi-dimensional risk reservation engine. No network or real
// accounts are involved; time is an injected logical clock.
package main

import (
	"fmt"
	"os"
	"strings"

	risk "riskengine"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	cmd := "demo"
	if len(args) > 0 {
		cmd = args[0]
	}
	switch cmd {
	case "demo":
		return demo()
	case "help", "-h", "--help":
		usage()
		return nil
	default:
		return fmt.Errorf("unknown command %q (try 'riskcli help')", cmd)
	}
}

func usage() {
	fmt.Println(strings.TrimSpace(`
riskcli - local synthetic pre-trade risk reservation demo

usage:
  riskcli [demo]   run the scripted deterministic scenario
  riskcli help     show this help

The scenario exercises: happy-path reserve, classified rejections,
idempotent replay/conflict, confirm/release state machine, same-tick
expiry arbitration and canonical snapshot recovery.
`))
}

func syntheticSpecs() []risk.AccountSpec {
	return []risk.AccountSpec{
		{
			ID:        "SYN-1",
			CashLimit: 100_000_000_000,
			RiskLimit: 60_000_000_000,
			Positions: map[string]risk.Qty{"STK": 500_000_000},
		},
		{
			ID:        "SYN-2",
			CashLimit: 20_000_000_000,
			RiskLimit: 20_000_000_000,
			Positions: map[string]risk.Qty{},
		},
	}
}

func buy(key, acct, sym string, qty risk.Qty, price risk.Amount, exp int64) risk.OrderRequest {
	return risk.OrderRequest{AccountID: acct, Symbol: sym, Direction: risk.Buy,
		Qty: qty, Price: price, IdemKey: key, ExpiresAt: exp}
}

func sell(key, acct, sym string, qty risk.Qty, price risk.Amount, exp int64) risk.OrderRequest {
	r := buy(key, acct, sym, qty, price, exp)
	r.Direction = risk.Sell
	return r
}

func showRes(label string, res *risk.OrderResult, err *risk.Error) {
	if err != nil {
		fmt.Printf("%-28s -> rejected [%s] %s (audit seq=%d)\n", label, err.Code, err.Msg, err.Seq)
		return
	}
	fmt.Printf("%-28s -> %s seq=%d notional=%d expires=%d\n",
		label, res.Status, res.Seq, res.Cash, res.Order.ExpiresAt)
}

func demo() error {
	clock := &risk.ManualClock{}
	eng, err := risk.NewEngine(clock, syntheticSpecs())
	if err != nil {
		return err
	}

	fmt.Println("== logical time:", clock.Now(), "==")
	r1, e1 := eng.Reserve(buy("ORD-001", "SYN-1", "STK", 1_000_000, 30_000, 100))
	showRes("reserve buy ORD-001", r1, e1)

	_, e2 := eng.Reserve(buy("ORD-BAD-ACCT", "NOBODY", "STK", 1, 10, 100))
	showRes("reserve unknown account", nil, e2)

	_, e3 := eng.Reserve(buy("ORD-OVERFLOW", "SYN-1", "STK", risk.MaxQty, risk.MaxAmount, 100))
	showRes("reserve overflow notional", nil, e3)

	_, e4 := eng.Reserve(buy("ORD-NO-CASH", "SYN-1", "STK", 100_000_000, 2_000, 100))
	showRes("reserve cash short", nil, e4)

	_, e5 := eng.Reserve(buy("ORD-NO-RISK", "SYN-1", "STK", 100_000, 700_000, 100))
	showRes("reserve risk short", nil, e5)

	_, e6 := eng.Reserve(sell("ORD-NO-POS", "SYN-1", "STK", 600_000_000, 100, 100))
	showRes("reserve sell over position", nil, e6)

	// Idempotent replay then conflicting rebind.
	r7, e7 := eng.Reserve(buy("ORD-001", "SYN-1", "STK", 1_000_000, 30_000, 100))
	showRes("replay same ORD-001", r7, e7)
	_, e8 := eng.Reserve(buy("ORD-001", "SYN-1", "STK", 2_000_000, 30_000, 100))
	showRes("rebind ORD-001 (conflict)", nil, e8)

	// Expiring order demonstrates same-tick frozen-priority arbitration.
	r9, e9 := eng.Reserve(buy("ORD-TTL", "SYN-2", "STK", 100_000, 5_000, 5))
	showRes("reserve expiring ORD-TTL", r9, e9)

	clock.Advance(5)
	fmt.Println("== logical time:", clock.Now(), "==")
	c9, c9err := eng.Confirm("ORD-TTL")
	showRes("confirm ORD-TTL at deadline", c9, c9err)
	if expired := eng.ExpireDue(); len(expired) != 0 {
		fmt.Println("unexpected expiry at deadline tick")
	}

	c1, c1err := eng.Confirm("ORD-001")
	showRes("confirm ORD-001", c1, c1err)
	_, c1again := eng.Confirm("ORD-001")
	showRes("duplicate confirm ORD-001", c1, c1again)
	_, rl := eng.Release("ORD-001")
	showRes("release after confirm (reject)", nil, rl)

	r10, e10 := eng.Reserve(sell("ORD-SELL", "SYN-1", "STK", 50_000_000, 100, 0))
	showRes("reserve sell ORD-SELL", r10, e10)
	r10done, r10err := eng.Release("ORD-SELL")
	showRes("release ORD-SELL", r10done, r10err)

	snap := eng.Snapshot()
	data, encErr := snap.Encode()
	if encErr != nil {
		return encErr
	}
	decoded, decErr := risk.DecodeSnapshot(data)
	if decErr != nil {
		return decErr
	}
	restored, resErr := risk.Restore(decoded, clock)
	if resErr != nil {
		return resErr
	}
	if !restored.Snapshot().Equal(snap) {
		return fmt.Errorf("restored snapshot mismatch")
	}
	fmt.Printf("\nsnapshot: %d accounts, %d orders, %d audit entries, %d gob bytes\n",
		len(snap.Accounts), len(snap.Orders), len(snap.Audit), len(data))
	for _, a := range snap.Accounts {
		fmt.Printf("  account %s cash(committed=%d reserved=%d limit=%d) risk(committed=%d reserved=%d limit=%d)\n",
			a.ID, a.CommittedCa, a.ReservedCa, a.CashLimit,
			a.CommittedRi, a.ReservedRi, a.RiskLimit)
	}
	return nil
}
