package risk

import "sort"

// refRes is one reservation in the reference model.
type refRes struct {
	order    Order
	status   Status
	seq      uint64
	expireAt uint64
	notional int64
}

// SerialModel is the deliberately simple single-threaded reference
// implementation. Concurrent histories must be equivalent to some legal
// serial execution of this model.
type SerialModel struct {
	accounts map[string]*account
	res      map[string]*refRes
	idem     map[string]idemEntry
	audit    []auditSnap
	seq      uint64
}

func NewSerialModel() *SerialModel {
	return &SerialModel{
		accounts: make(map[string]*account),
		res:      make(map[string]*refRes),
		idem:     make(map[string]idemEntry),
	}
}

func (m *SerialModel) AddAccount(spec AccountSpec) error {
	a, err := newAccount(spec)
	if err != nil {
		return err
	}
	m.accounts[spec.ID] = a
	return nil
}

func (m *SerialModel) Reserve(o Order) Result {
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
	if first, ok := m.idem[o.Key]; ok {
		if first.finger != finger {
			return fail(CodeDuplicateConflict,
				"idempotency key "+o.Key+" reused with different parameters")
		}
		r := first.result
		r.Replay = true
		return r
	}
	acc, ok := m.accounts[o.Account]
	if !ok {
		return fail(CodeUnknownAccount, "unknown account: "+o.Account)
	}
	notional, ok := o.notional()
	if !ok {
		return fail(CodeOverflow, "price*qty overflow for "+o.Key)
	}
	if code := acc.reserveClass(o.Side, o.Symbol, notional, o.Qty); code != CodeOK {
		return fail(code, "reservation rejected")
	}
	if code := acc.applyReserve(o.Side, o.Symbol, notional, o.Qty); code != CodeOK {
		return fail(code, "overflow applying reservation")
	}
	m.seq++
	m.res[o.Key] = &refRes{
		order: o, status: StatusReserved, seq: m.seq,
		expireAt: o.ExpireAt, notional: notional,
	}
	r := Result{OK: true, Code: CodeOK, Key: o.Key, Seq: m.seq, Status: StatusReserved}
	r.ReservationID = m.seq
	m.idem[o.Key] = idemEntry{finger: finger, result: r}
	m.audit = append(m.audit, auditSnap{
		Seq: m.seq, ReservationID: m.seq, Op: "reserve", Key: o.Key,
		Status: StatusReserved, Result: r,
	})
	return r
}

func (m *SerialModel) Confirm(key string, now uint64) Result {
	rec, ok := m.res[key]
	if !ok {
		return fail(CodeUnknownKey, "unknown reservation: "+key)
	}
	if rec.status == StatusConfirmed {
		return Result{OK: true, Code: CodeOK, Key: key, Seq: rec.seq,
			ReservationID: rec.seq, Status: StatusConfirmed, Replay: true}
	}
	if rec.status == StatusExpired {
		return fail(CodeInvalidState, "already expired")
	}
	if rec.status == StatusReleased {
		return fail(CodeInvalidState, "already released")
	}
	if now > rec.expireAt {
		return fail(CodeInvalidState, "expired before confirm")
	}
	acc := m.accounts[rec.order.Account]
	if code := acc.applyConfirm(rec.order.Side, rec.order.Symbol,
		rec.notional, rec.order.Qty); code != CodeOK {
		return fail(code, "overflow confirming")
	}
	rec.status = StatusConfirmed
	m.seq++
	r := Result{OK: true, Code: CodeOK, Key: key, Seq: m.seq,
		ReservationID: rec.seq, Status: StatusConfirmed}
	m.audit = append(m.audit, auditSnap{
		Seq: m.seq, ReservationID: rec.seq, Op: "confirm", Key: key,
		Status: StatusConfirmed,
		Result: r, Tick: now,
	})
	return r
}

func (m *SerialModel) Release(key string, now uint64) Result {
	rec, ok := m.res[key]
	if !ok {
		return fail(CodeUnknownKey, "unknown reservation: "+key)
	}
	if rec.status == StatusReleased {
		return Result{OK: true, Code: CodeOK, Key: key, Seq: rec.seq,
			ReservationID: rec.seq, Status: StatusReleased, Replay: true}
	}
	if rec.status == StatusConfirmed {
		return fail(CodeInvalidState, "already confirmed")
	}
	if rec.status == StatusExpired {
		return fail(CodeInvalidState, "already expired")
	}
	acc := m.accounts[rec.order.Account]
	if code := acc.applyRelease(rec.order.Side, rec.order.Symbol,
		rec.notional, rec.order.Qty); code != CodeOK {
		return fail(code, "overflow releasing")
	}
	rec.status = StatusReleased
	m.seq++
	r := Result{OK: true, Code: CodeOK, Key: key, Seq: m.seq,
		ReservationID: rec.seq, Status: StatusReleased}
	m.audit = append(m.audit, auditSnap{
		Seq: m.seq, ReservationID: rec.seq, Op: "release", Key: key,
		Status: StatusReleased,
		Result: r, Tick: now,
	})
	return r
}

// ExpireDue reclaims every reservation with ExpireAt <= now and returns the
// reclaimed keys in stable (expireAt, seq) order.
func (m *SerialModel) ExpireDue(now uint64) []string {
	due := make([]*refRes, 0)
	for _, rec := range m.res {
		if rec.status == StatusReserved && rec.expireAt <= now {
			due = append(due, rec)
		}
	}
	sort.Slice(due, func(i, j int) bool {
		if due[i].expireAt != due[j].expireAt {
			return due[i].expireAt < due[j].expireAt
		}
		return due[i].seq < due[j].seq
	})
	keys := make([]string, 0, len(due))
	for _, rec := range due {
		acc := m.accounts[rec.order.Account]
		if code := acc.applyRelease(rec.order.Side, rec.order.Symbol,
			rec.notional, rec.order.Qty); code != CodeOK {
			continue
		}
		rec.status = StatusExpired
		m.seq++
		r := Result{OK: true, Code: CodeOK, Key: rec.order.Key, Seq: m.seq,
			ReservationID: rec.seq, Status: StatusExpired}
		m.audit = append(m.audit, auditSnap{
			Seq: m.seq, ReservationID: rec.seq, Op: "expire",
			Key:    rec.order.Key,
			Status: StatusExpired, Result: r, Tick: now,
		})
		keys = append(keys, rec.order.Key)
	}
	return keys
}

func (m *SerialModel) Snapshot() *Snapshot {
	e := NewEngine()
	e.seq = m.seq
	e.accounts = m.accounts
	for key, r := range m.res {
		e.res[key] = &res{
			order: r.order, status: r.status, seq: r.seq,
			expireAt: r.expireAt, notional: r.notional,
		}
	}
	for key, en := range m.idem {
		e.idem.put(key, en)
	}
	for _, a := range m.audit {
		e.audit = append(e.audit, auditEntry{
			Seq: a.Seq, ReservationID: a.ReservationID, Op: a.Op,
			Key: a.Key, Status: a.Status, Result: a.Result, Tick: a.Tick,
		})
	}
	return e.snapshotLocked()
}

func (m *SerialModel) Restore(s *Snapshot) error {
	e := NewEngine()
	if err := e.restoreLocked(s); err != nil {
		return err
	}
	m.seq = e.seq
	m.accounts = e.accounts
	m.idem = e.idem.entries
	m.audit = m.audit[:0]
	for _, a := range e.audit {
		m.audit = append(m.audit, auditSnap{
			Seq: a.Seq, ReservationID: a.ReservationID, Op: a.Op,
			Key: a.Key, Status: a.Status, Result: a.Result, Tick: a.Tick,
		})
	}
	m.res = make(map[string]*refRes, len(e.res))
	for key, r := range e.res {
		m.res[key] = &refRes{
			order: r.order, status: r.status, seq: r.seq,
			expireAt: r.expireAt, notional: r.notional,
		}
	}
	return nil
}
