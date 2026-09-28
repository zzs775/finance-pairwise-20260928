package riskengine

import (
	"bytes"
	"container/heap"
	"encoding/gob"
	"reflect"
	"sort"
)

// PositionSnapshot describes one instrument position dimension.
type PositionSnapshot struct {
	Symbol    string
	Held      Qty
	Committed Qty
	Reserved  Qty
}

// AccountSnapshot is the canonical state of one account.
type AccountSnapshot struct {
	ID          string
	CashLimit   Amount
	CommittedCa Amount
	ReservedCa  Amount
	RiskLimit   Amount
	CommittedRi Amount
	ReservedRi  Amount
	Positions   []PositionSnapshot
}

// OrderSnapshot is the canonical state of one reservation.
type OrderSnapshot struct {
	Seq       int64
	Key       string
	AccountID string
	Symbol    string
	Direction Direction
	Qty       Qty
	Price     Amount
	Status    Status
	ExpiresAt int64
	Cash      Amount
}

// AuditEntry records one linearized operation, including denials.
type AuditEntry struct {
	Seq    int64
	Key    string
	Status Status
	Time   int64
	Code   ErrCode
}

// Snapshot is the canonical full engine state.
type Snapshot struct {
	Seq      int64
	Time     int64
	Accounts []AccountSnapshot
	Orders   []OrderSnapshot
	Audit    []AuditEntry
}

// canonical sorts every slice into a stable order so equal states compare
// byte-for-byte regardless of map iteration or goroutine interleaving.
func (s *Snapshot) canonical() {
	sort.Slice(s.Accounts, func(i, j int) bool { return s.Accounts[i].ID < s.Accounts[j].ID })
	for i := range s.Accounts {
		sort.Slice(s.Accounts[i].Positions, func(a, b int) bool {
			return s.Accounts[i].Positions[a].Symbol < s.Accounts[i].Positions[b].Symbol
		})
	}
	sort.Slice(s.Orders, func(i, j int) bool { return s.Orders[i].Seq < s.Orders[j].Seq })
	sort.Slice(s.Audit, func(i, j int) bool { return s.Audit[i].Seq < s.Audit[j].Seq })
	for i := range s.Accounts {
		if len(s.Accounts[i].Positions) == 0 {
			s.Accounts[i].Positions = nil
		}
	}
	if len(s.Orders) == 0 {
		s.Orders = nil
	}
	if len(s.Audit) == 0 {
		s.Audit = nil
	}
}

// Equal reports whether two canonical snapshots are identical.
func (s *Snapshot) Equal(o *Snapshot) bool {
	if s == nil || o == nil {
		return s == o
	}
	a, b := *s, *o
	a.canonical()
	b.canonical()
	return reflect.DeepEqual(a, b)
}

// Wire types use only base integer kinds so gob serialisation never depends
// on named type registration and stays stable across package versions.
type wirePosition struct {
	Symbol    string
	Held      int64
	Committed int64
	Reserved  int64
}

type wireAccount struct {
	ID          string
	CashLimit   int64
	CommittedCa int64
	ReservedCa  int64
	RiskLimit   int64
	CommittedRi int64
	ReservedRi  int64
	Positions   []wirePosition
}

type wireOrder struct {
	Seq       int64
	Key       string
	AccountID string
	Symbol    string
	Direction int64
	Qty       int64
	Price     int64
	Status    int64
	ExpiresAt int64
	Cash      int64
}

type wireAudit struct {
	Seq    int64
	Key    string
	Status int64
	Time   int64
	Code   int64
}

type wireSnapshot struct {
	Seq      int64
	Time     int64
	Accounts []wireAccount
	Orders   []wireOrder
	Audit    []wireAudit
}

func (s *Snapshot) toWire() wireSnapshot {
	w := wireSnapshot{Seq: s.Seq, Time: s.Time}
	for _, a := range s.Accounts {
		wa := wireAccount{
			ID:          a.ID,
			CashLimit:   int64(a.CashLimit),
			CommittedCa: int64(a.CommittedCa),
			ReservedCa:  int64(a.ReservedCa),
			RiskLimit:   int64(a.RiskLimit),
			CommittedRi: int64(a.CommittedRi),
			ReservedRi:  int64(a.ReservedRi),
		}
		for _, p := range a.Positions {
			wa.Positions = append(wa.Positions, wirePosition{
				Symbol:    p.Symbol,
				Held:      int64(p.Held),
				Committed: int64(p.Committed),
				Reserved:  int64(p.Reserved),
			})
		}
		w.Accounts = append(w.Accounts, wa)
	}
	for _, o := range s.Orders {
		w.Orders = append(w.Orders, wireOrder{
			Seq:       o.Seq,
			Key:       o.Key,
			AccountID: o.AccountID,
			Symbol:    o.Symbol,
			Direction: int64(o.Direction),
			Qty:       int64(o.Qty),
			Price:     int64(o.Price),
			Status:    int64(o.Status),
			ExpiresAt: o.ExpiresAt,
			Cash:      int64(o.Cash),
		})
	}
	for _, a := range s.Audit {
		w.Audit = append(w.Audit, wireAudit{
			Seq:    a.Seq,
			Key:    a.Key,
			Status: int64(a.Status),
			Time:   a.Time,
			Code:   int64(a.Code),
		})
	}
	return w
}

func (w wireSnapshot) toSnapshot() *Snapshot {
	s := &Snapshot{Seq: w.Seq, Time: w.Time}
	for _, a := range w.Accounts {
		sa := AccountSnapshot{
			ID:          a.ID,
			CashLimit:   Amount(a.CashLimit),
			CommittedCa: Amount(a.CommittedCa),
			ReservedCa:  Amount(a.ReservedCa),
			RiskLimit:   Amount(a.RiskLimit),
			CommittedRi: Amount(a.CommittedRi),
			ReservedRi:  Amount(a.ReservedRi),
		}
		for _, p := range a.Positions {
			sa.Positions = append(sa.Positions, PositionSnapshot{
				Symbol:    p.Symbol,
				Held:      Qty(p.Held),
				Committed: Qty(p.Committed),
				Reserved:  Qty(p.Reserved),
			})
		}
		s.Accounts = append(s.Accounts, sa)
	}
	for _, o := range w.Orders {
		s.Orders = append(s.Orders, OrderSnapshot{
			Seq:       o.Seq,
			Key:       o.Key,
			AccountID: o.AccountID,
			Symbol:    o.Symbol,
			Direction: Direction(o.Direction),
			Qty:       Qty(o.Qty),
			Price:     Amount(o.Price),
			Status:    Status(o.Status),
			ExpiresAt: o.ExpiresAt,
			Cash:      Amount(o.Cash),
		})
	}
	for _, a := range w.Audit {
		s.Audit = append(s.Audit, AuditEntry{
			Seq:    a.Seq,
			Key:    a.Key,
			Status: Status(a.Status),
			Time:   a.Time,
			Code:   ErrCode(a.Code),
		})
	}
	return s
}

// Encode serialises the snapshot to a deterministic binary form.
func (s *Snapshot) Encode() ([]byte, error) {
	cp := *s
	cp.canonical()
	var buf bytes.Buffer
	if err := gob.NewEncoder(&buf).Encode(cp.toWire()); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// DecodeSnapshot parses the output of Encode.
func DecodeSnapshot(data []byte) (*Snapshot, error) {
	var w wireSnapshot
	if err := gob.NewDecoder(bytes.NewReader(data)).Decode(&w); err != nil {
		return nil, err
	}
	s := w.toSnapshot()
	s.canonical()
	return s, nil
}

func (e *Engine) buildSnapshotLocked() *Snapshot {
	s := &Snapshot{Seq: e.seq, Time: e.clock.Now()}
	ids := make([]string, 0, len(e.accounts))
	for id := range e.accounts {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		acc := e.accounts[id]
		syms := map[string]struct{}{}
		for sym := range acc.spec.Positions {
			syms[sym] = struct{}{}
		}
		for sym := range acc.committed {
			syms[sym] = struct{}{}
		}
		for sym := range acc.resPosition {
			syms[sym] = struct{}{}
		}
		ordered := make([]string, 0, len(syms))
		for sym := range syms {
			ordered = append(ordered, sym)
		}
		sort.Strings(ordered)
		ps := make([]PositionSnapshot, 0, len(ordered))
		for _, sym := range ordered {
			ps = append(ps, PositionSnapshot{
				Symbol:    sym,
				Held:      acc.spec.Positions[sym],
				Committed: acc.committed[sym],
				Reserved:  acc.resPosition[sym],
			})
		}
		s.Accounts = append(s.Accounts, AccountSnapshot{
			ID:          id,
			CashLimit:   acc.spec.CashLimit,
			CommittedCa: acc.committedCa,
			ReservedCa:  acc.resCash,
			RiskLimit:   acc.spec.RiskLimit,
			CommittedRi: acc.committedRi,
			ReservedRi:  acc.resRisk,
			Positions:   ps,
		})
	}
	for _, res := range e.orders {
		r := res.Order
		s.Orders = append(s.Orders, OrderSnapshot{
			Seq:       res.Seq,
			Key:       r.IdemKey,
			AccountID: r.AccountID,
			Symbol:    r.Symbol,
			Direction: r.Direction,
			Qty:       r.Qty,
			Price:     r.Price,
			Status:    res.Status,
			ExpiresAt: r.ExpiresAt,
			Cash:      res.Cash,
		})
	}
	s.Audit = append(s.Audit, e.audit...)
	s.canonical()
	return s
}

// Snapshot returns a canonical, deterministic state snapshot.
func (e *Engine) Snapshot() *Snapshot {
	e.lock()
	defer e.unlock()
	return e.buildSnapshotLocked()
}

// Restore rebuilds an engine from a canonical snapshot.
func Restore(s *Snapshot, clock Clock) (*Engine, *Error) {
	if clock == nil {
		return nil, codeErr(ErrNegativeValue, "nil clock")
	}
	e := &Engine{
		mu:       make(chan struct{}, 1),
		clock:    clock,
		accounts: map[string]*account{},
		orders:   map[string]*OrderResult{},
		heap:     newExpiryHeap(),
		seq:      s.Seq,
		audit:    append([]AuditEntry(nil), s.Audit...),
	}
	for _, as := range s.Accounts {
		acc := &account{
			spec: AccountSpec{
				ID:        as.ID,
				CashLimit: as.CashLimit,
				RiskLimit: as.RiskLimit,
				Positions: map[string]Qty{},
			},
			committedCa: as.CommittedCa,
			resCash:     as.ReservedCa,
			committedRi: as.CommittedRi,
			resRisk:     as.ReservedRi,
			committed:   map[string]Qty{},
			resPosition: map[string]Qty{},
		}
		for _, p := range as.Positions {
			acc.spec.Positions[p.Symbol] = p.Held
			acc.committed[p.Symbol] = p.Committed
			acc.resPosition[p.Symbol] = p.Reserved
		}
		e.accounts[as.ID] = acc
	}
	for _, o := range s.Orders {
		req := OrderRequest{
			AccountID: o.AccountID,
			Symbol:    o.Symbol,
			Direction: o.Direction,
			Qty:       o.Qty,
			Price:     o.Price,
			IdemKey:   o.Key,
			ExpiresAt: o.ExpiresAt,
		}
		res := &OrderResult{Seq: o.Seq, Status: o.Status, Order: req, Cash: o.Cash}
		e.orders[o.Key] = res
		if o.Status == StatusReserved && o.ExpiresAt != 0 {
			heap.Push(e.heap, expiryItem{expiresAt: o.ExpiresAt, seq: o.Seq, key: o.Key})
		}
	}
	return e, nil
}
