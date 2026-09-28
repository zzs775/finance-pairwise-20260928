package risk

import (
	"sync"
	"time"
)

// res is the live record of one reservation.
type res struct {
	order    Order
	status   Status
	seq      uint64
	expireAt uint64
	notional int64
}

// auditEntry is one serialized state transition, in the exact linearization
// order of the engine.
type auditEntry struct {
	Seq           uint64
	ReservationID uint64
	Op            string
	Key           string
	Status        Status
	Result        Result
	Tick          uint64
	Wall          int64
}

// Engine is the concurrent-safe multi-dimensional pre-trade risk engine.
type Engine struct {
	mu       sync.Mutex
	accounts map[string]*account
	res      map[string]*res
	idem     *idemTable
	heap     *expiryHeap
	audit    []auditEntry
	seq      uint64
}

func NewEngine() *Engine {
	return &Engine{
		accounts: make(map[string]*account),
		res:      make(map[string]*res),
		idem:     newIdemTable(),
		heap:     newExpiryHeap(),
	}
}

// AddAccount registers a synthetic account. Re-adding the same account id
// replaces its state only when the id is currently unknown.
func (e *Engine) AddAccount(spec AccountSpec) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.accounts[spec.ID]; exists {
		return classified(CodeDuplicateConflict, "account already exists: "+spec.ID)
	}
	a, err := newAccount(spec)
	if err != nil {
		return err
	}
	e.accounts[spec.ID] = a
	return nil
}

func (e *Engine) Reserve(o Order) Result {
	e.mu.Lock()
	defer e.mu.Unlock()

	if o.Key == "" {
		return fail(CodeInvalidArgument, "empty idempotency key")
	}
	if !o.Side.valid() {
		return fail(CodeInvalidArgument, "unknown order side")
	}
	if o.Price < 0 || o.Qty < 0 {
		return fail(CodeNegative, "negative price or quantity")
	}
	if o.Account == "" || o.Symbol == "" {
		return fail(CodeInvalidArgument, "empty account or symbol")
	}

	finger := o.finger()
	if first, ok := e.idem.get(o.Key); ok {
		if first.finger != finger {
			return fail(CodeDuplicateConflict,
				"idempotency key "+o.Key+" reused with different parameters")
		}
		r := first.result
		r.Replay = true
		return r
	}

	acc, ok := e.accounts[o.Account]
	if !ok {
		return fail(CodeUnknownAccount, "unknown account: "+o.Account)
	}

	notional, ok := o.notional()
	if !ok {
		return fail(CodeOverflow, "price*qty overflow for "+o.Key)
	}

	// Phase 1: validate all three dimensions without touching any state.
	if code := acc.reserveClass(o.Side, o.Symbol, notional, o.Qty); code != CodeOK {
		return fail(code, "reservation "+o.Key+" rejected on a risk dimension")
	}

	// Phase 2: every dimension fits; apply all debits atomically.
	if code := acc.applyReserve(o.Side, o.Symbol, notional, o.Qty); code != CodeOK {
		return fail(code, "overflow while applying reservation "+o.Key)
	}

	e.seq++
	rec := &res{
		order:    o,
		status:   StatusReserved,
		seq:      e.seq,
		expireAt: o.ExpireAt,
		notional: notional,
	}
	e.res[o.Key] = rec
	e.heap.push(expiryItem{key: o.Key, expireAt: o.ExpireAt, seq: e.seq})
	r := Result{
		OK:            true,
		Code:          CodeOK,
		Key:           o.Key,
		Seq:           e.seq,
		ReservationID: e.seq,
		Status:        StatusReserved,
	}
	e.idem.put(o.Key, idemEntry{finger: finger, result: r})
	e.appendAudit("reserve", r, 0)
	return r
}

func (e *Engine) Confirm(key string, now uint64) Result {
	e.mu.Lock()
	defer e.mu.Unlock()

	rec, ok := e.res[key]
	if !ok {
		return fail(CodeUnknownKey, "unknown reservation: "+key)
	}
	if rec.status == StatusConfirmed {
		return Result{OK: true, Code: CodeOK, Key: key, Seq: rec.seq,
			ReservationID: rec.seq, Status: StatusConfirmed, Replay: true}
	}
	if rec.status == StatusExpired {
		return fail(CodeInvalidState, "reservation "+key+" already expired")
	}
	if rec.status == StatusReleased {
		return fail(CodeInvalidState, "reservation "+key+" already released")
	}
	// Freeze wins at the same logical tick.
	if now > rec.expireAt {
		return fail(CodeInvalidState, "reservation "+key+" expired before confirm")
	}

	acc := e.accounts[rec.order.Account]
	if code := acc.applyConfirm(rec.order.Side, rec.order.Symbol,
		rec.notional, rec.order.Qty); code != CodeOK {
		return fail(code, "overflow while confirming "+key)
	}
	rec.status = StatusConfirmed
	e.heap.remove(key)
	e.seq++
	r := Result{OK: true, Code: CodeOK, Key: key, Seq: e.seq,
		ReservationID: rec.seq, Status: StatusConfirmed}
	e.appendAudit("confirm", r, now)
	return r
}

func (e *Engine) Release(key string, now uint64) Result {
	e.mu.Lock()
	defer e.mu.Unlock()

	rec, ok := e.res[key]
	if !ok {
		return fail(CodeUnknownKey, "unknown reservation: "+key)
	}
	if rec.status == StatusReleased {
		return Result{OK: true, Code: CodeOK, Key: key, Seq: rec.seq,
			ReservationID: rec.seq, Status: StatusReleased, Replay: true}
	}
	if rec.status == StatusConfirmed {
		return fail(CodeInvalidState, "reservation "+key+" already confirmed")
	}
	if rec.status == StatusExpired {
		return fail(CodeInvalidState, "reservation "+key+" already expired")
	}

	acc := e.accounts[rec.order.Account]
	if code := acc.applyRelease(rec.order.Side, rec.order.Symbol,
		rec.notional, rec.order.Qty); code != CodeOK {
		return fail(code, "overflow while releasing "+key)
	}
	rec.status = StatusReleased
	e.heap.remove(key)
	e.seq++
	r := Result{OK: true, Code: CodeOK, Key: key, Seq: e.seq,
		ReservationID: rec.seq, Status: StatusReleased}
	e.appendAudit("release", r, now)
	return r
}

// ExpireDue reclaims every reservation with ExpireAt <= now and returns the
// reclaimed keys in stable heap order.
func (e *Engine) ExpireDue(now uint64) []string {
	e.mu.Lock()
	defer e.mu.Unlock()

	reclaimed := make([]string, 0)
	for {
		item, ok := e.heap.peek()
		if !ok || item.expireAt > now {
			break
		}
		e.heap.pop()
		rec, exists := e.res[item.key]
		if !exists {
			continue
		}
		// Only live reservations can expire. Confirmed items are removed
		// from the heap on confirmation; released items linger and are
		// discarded here without touching balances again.
		if rec.status != StatusReserved {
			continue
		}
		acc := e.accounts[rec.order.Account]
		if code := acc.applyRelease(rec.order.Side, rec.order.Symbol,
			rec.notional, rec.order.Qty); code != CodeOK {
			// Unreachable: balances can only overflow on a prior bug.
			continue
		}
		rec.status = StatusExpired
		e.seq++
		r := Result{OK: true, Code: CodeOK, Key: item.key, Seq: e.seq,
			ReservationID: rec.seq,
			Status:        StatusExpired}
		e.appendAudit("expire", r, now)
		reclaimed = append(reclaimed, item.key)
	}
	return reclaimed
}

func (e *Engine) Snapshot() *Snapshot {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.snapshotLocked()
}

func (e *Engine) Restore(s *Snapshot) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.restoreLocked(s)
}

func (e *Engine) appendAudit(op string, r Result, tick uint64) {
	e.audit = append(e.audit, auditEntry{
		Seq:           r.Seq,
		ReservationID: r.ReservationID,
		Op:            op,
		Key:           r.Key,
		Status:        r.Status,
		Result:        r,
		Tick:          tick,
		Wall:          time.Now().UnixNano(),
	})
}

// Audit returns a copy of the audit log in linearization order.
func (e *Engine) Audit() []auditEntry {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]auditEntry, len(e.audit))
	copy(out, e.audit)
	return out
}

func fail(code Code, msg string) Result {
	return Result{OK: false, Code: code, Status: StatusNone}
}
