package risk

import (
	"math"
	"testing"
)

func TestExpiryReclaim(t *testing.T) {
	e := newTestEngine(t)
	mk := func(key string, expireAt uint64, qty int64) {
		r := e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
			Price: 10, Qty: qty, Key: key, ExpireAt: expireAt})
		if !r.OK {
			t.Fatalf("reserve %s: %+v", key, r)
		}
	}
	mk("a", 50, 5) // 50
	mk("b", 30, 5) // 50
	mk("c", 80, 5) // 50
	mk("d", 30, 5) // same tick as b, later seq

	keys := e.ExpireDue(40)
	want := []string{"b", "d"}
	if len(keys) != len(want) {
		t.Fatalf("due keys: %v want %v", keys, want)
	}
	for i := range want {
		if keys[i] != want[i] {
			t.Fatalf("stable order: %v want %v", keys, want)
		}
	}
	if got := e.accounts["acct1"].risk.reserved; got != 100 {
		t.Fatalf("expiry freed wrong amount: %d", got)
	}

	// Expired reservations are terminal.
	if r := e.Confirm("b", 40); r.OK || r.Code != CodeInvalidState {
		t.Fatalf("expired confirm: %+v", r)
	}
	// Idempotent re-sweep reclaims nothing.
	if keys = e.ExpireDue(40); len(keys) != 0 {
		t.Fatalf("repeat sweep: %v", keys)
	}
	// Remaining items still expire at their own deadline.
	keys = e.ExpireDue(100)
	if len(keys) != 2 || keys[0] != "a" || keys[1] != "c" {
		t.Fatalf("final sweep order: %v", keys)
	}
	if got := e.accounts["acct1"].risk.reserved; got != 0 {
		t.Fatalf("all reserves should be freed: %d", got)
	}
}

func TestFreezeWinsSameTick(t *testing.T) {
	e := newTestEngine(t)
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k1", ExpireAt: 100})

	// Confirm at exactly the expiry tick wins over expiry (freeze priority).
	r := e.Confirm("k1", 100)
	if !r.OK || r.Status != StatusConfirmed {
		t.Fatalf("confirm at freeze tick: %+v", r)
	}
	if keys := e.ExpireDue(100); len(keys) != 0 {
		t.Fatalf("frozen reservation must not expire: %v", keys)
	}

	// One tick later confirmation is refused and expiry reclaims.
	e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: 10, Qty: 5, Key: "k2", ExpireAt: 100})
	if r = e.Confirm("k2", 101); r.OK || r.Code != CodeInvalidState {
		t.Fatalf("late confirm: %+v", r)
	}
	// The refused confirm must not have changed state; expiry now reclaims.
	keys := e.ExpireDue(101)
	if len(keys) != 1 || keys[0] != "k2" {
		t.Fatalf("expire after late confirm: %v", keys)
	}
}

func TestHeapStableTieBreak(t *testing.T) {
	h := newExpiryHeap()
	h.push(expiryItem{key: "late-seq", expireAt: 10, seq: 5})
	h.push(expiryItem{key: "early-seq", expireAt: 10, seq: 1})
	h.push(expiryItem{key: "sooner", expireAt: 5, seq: 9})
	order := []string{}
	for h.len() > 0 {
		it, _ := h.pop()
		order = append(order, it.key)
	}
	want := []string{"sooner", "early-seq", "late-seq"}
	for i := range want {
		if order[i] != want[i] {
			t.Fatalf("heap order: %v want %v", order, want)
		}
	}
}

func TestNotionalOverflow(t *testing.T) {
	e := newTestEngine(t)
	r := e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: math.MaxInt64, Qty: 2, Key: "big", ExpireAt: 100})
	if r.OK || r.Code != CodeOverflow {
		t.Fatalf("notional overflow: %+v", r)
	}
	r = e.Reserve(Order{Account: "acct1", Symbol: "AAA", Side: Buy,
		Price: math.MaxInt64, Qty: 1, Key: "big2", ExpireAt: 100})
	if r.OK || r.Code != CodeInsufficientRisk {
		t.Fatalf("huge notional vs small risk limit: %+v", r)
	}
}
