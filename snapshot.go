package risk

import (
	"bytes"
	"encoding/json"
)

// Snapshot is a canonical, restorable point-in-time image of engine state.
type Snapshot struct {
	Seq      uint64                 `json:"seq"`
	Accounts map[string]accountSnap `json:"accounts"`
	Reservs  map[string]resSnap     `json:"reservations"`
	Idem     map[string]idemSnap    `json:"idem"`
	Audit    []auditSnap            `json:"audit"`
}

type accountSnap struct {
	CashTotal int64                   `json:"cash_total"`
	RiskTotal int64                   `json:"risk_total"`
	CashRes   int64                   `json:"cash_reserved"`
	CashCom   int64                   `json:"cash_committed"`
	RiskRes   int64                   `json:"risk_reserved"`
	RiskCom   int64                   `json:"risk_committed"`
	Positions map[string]positionSnap `json:"positions"`
}

type positionSnap struct {
	Total     int64 `json:"total"`
	Reserved  int64 `json:"reserved"`
	Committed int64 `json:"committed"`
}

type resSnap struct {
	Order    Order  `json:"order"`
	Status   Status `json:"status"`
	Seq      uint64 `json:"seq"`
	ExpireAt uint64 `json:"expire_at"`
	Notional int64  `json:"notional"`
}

type idemSnap struct {
	Finger string `json:"finger"`
	Result Result `json:"result"`
}

type auditSnap struct {
	Seq           uint64 `json:"seq"`
	ReservationID uint64 `json:"reservation_id"`
	Op            string `json:"op"`
	Key           string `json:"key"`
	Status        Status `json:"status"`
	Result        Result `json:"result"`
	Tick          uint64 `json:"tick"`
}

// Canonical returns a deterministic JSON encoding of the snapshot.
func (s *Snapshot) Canonical() ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(s); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func (e *Engine) snapshotLocked() *Snapshot {
	s := &Snapshot{
		Seq:      e.seq,
		Accounts: make(map[string]accountSnap, len(e.accounts)),
		Reservs:  make(map[string]resSnap, len(e.res)),
		Idem:     make(map[string]idemSnap, len(e.idem.entries)),
		Audit:    make([]auditSnap, 0, len(e.audit)),
	}
	for id, a := range e.accounts {
		as := accountSnap{
			CashTotal: a.cashTot,
			RiskTotal: a.riskTot,
			CashRes:   a.cash.reserved,
			CashCom:   a.cash.committed,
			RiskRes:   a.risk.reserved,
			RiskCom:   a.risk.committed,
			Positions: make(map[string]positionSnap, len(a.position)),
		}
		for sym, p := range a.position {
			as.Positions[sym] = positionSnap{
				Total:     p.total,
				Reserved:  p.reserved,
				Committed: p.committed,
			}
		}
		s.Accounts[id] = as
	}
	for key, r := range e.res {
		s.Reservs[key] = resSnap{
			Order:    r.order,
			Status:   r.status,
			Seq:      r.seq,
			ExpireAt: r.expireAt,
			Notional: r.notional,
		}
	}
	for key, en := range e.idem.entries {
		s.Idem[key] = idemSnap{Finger: en.finger, Result: en.result}
	}
	for _, a := range e.audit {
		s.Audit = append(s.Audit, auditSnap{
			Seq: a.Seq, ReservationID: a.ReservationID, Op: a.Op,
			Key: a.Key, Status: a.Status, Result: a.Result, Tick: a.Tick,
		})
	}
	return s
}

func (e *Engine) restoreLocked(s *Snapshot) error {
	if s == nil {
		return classified(CodeInvalidArgument, "nil snapshot")
	}
	e.seq = s.Seq
	e.accounts = make(map[string]*account, len(s.Accounts))
	e.res = make(map[string]*res, len(s.Reservs))
	e.idem = newIdemTable()
	e.heap = newExpiryHeap()
	e.audit = make([]auditEntry, 0, len(s.Audit))

	for id, as := range s.Accounts {
		a := &account{
			id:       id,
			cashTot:  as.CashTotal,
			riskTot:  as.RiskTotal,
			cash:     buckets{reserved: as.CashRes, committed: as.CashCom},
			risk:     buckets{reserved: as.RiskRes, committed: as.RiskCom},
			position: make(map[string]*position, len(as.Positions)),
		}
		for sym, p := range as.Positions {
			a.position[sym] = &position{
				total: p.Total, reserved: p.Reserved, committed: p.Committed,
			}
		}
		e.accounts[id] = a
	}
	for key, rs := range s.Reservs {
		e.res[key] = &res{
			order:    rs.Order,
			status:   rs.Status,
			seq:      rs.Seq,
			expireAt: rs.ExpireAt,
			notional: rs.Notional,
		}
		if rs.Status == StatusReserved {
			e.heap.push(expiryItem{
				key: key, expireAt: rs.ExpireAt, seq: rs.Seq,
			})
		}
	}
	for key, is := range s.Idem {
		e.idem.put(key, idemEntry{finger: is.Finger, result: is.Result})
	}
	for _, a := range s.Audit {
		e.audit = append(e.audit, auditEntry{
			Seq: a.Seq, ReservationID: a.ReservationID, Op: a.Op,
			Key: a.Key, Status: a.Status, Result: a.Result, Tick: a.Tick,
		})
	}
	return nil
}
