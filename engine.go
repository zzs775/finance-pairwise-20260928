package riskengine

import "container/heap"

type account struct {
	spec        AccountSpec
	committedCa Amount
	resCash     Amount
	committedRi Amount
	resRisk     Amount
	committed   map[string]Qty
	resPosition map[string]Qty
}

// Engine is the concurrent pre-trade multi-dimensional risk reservation engine.
// A single mutex token defines the unique linearization point of every
// operation; wall time and goroutine scheduling cannot influence outcomes.
type Engine struct {
	mu       chan struct{}
	clock    Clock
	accounts map[string]*account
	orders   map[string]*OrderResult
	heap     *expiryHeap
	seq      int64
	audit    []AuditEntry
}

// NewEngine constructs an engine with the given clock and account specs.
func NewEngine(clock Clock, specs []AccountSpec) (*Engine, *Error) {
	if clock == nil {
		return nil, codeErr(ErrNegativeValue, "nil clock")
	}
	e := &Engine{
		mu:       make(chan struct{}, 1),
		clock:    clock,
		accounts: make(map[string]*account),
		orders:   make(map[string]*OrderResult),
		heap:     newExpiryHeap(),
	}
	for _, spec := range specs {
		if spec.ID == "" {
			return nil, codeErr(ErrNegativeValue, "empty account id")
		}
		if _, dup := e.accounts[spec.ID]; dup {
			return nil, codeErr(ErrDuplicateConflict, "duplicate account: "+spec.ID)
		}
		if spec.CashLimit < 0 || spec.RiskLimit < 0 {
			return nil, codeErr(ErrNegativeValue, "negative limit for "+spec.ID)
		}
		positions := make(map[string]Qty, len(spec.Positions))
		for sym, q := range spec.Positions {
			if q < 0 {
				return nil, codeErr(ErrNegativeValue, "negative holding for "+spec.ID+"/"+sym)
			}
			positions[sym] = q
		}
		spec.Positions = positions
		e.accounts[spec.ID] = &account{
			spec:        spec,
			committed:   map[string]Qty{},
			resPosition: map[string]Qty{},
		}
	}
	return e, nil
}

func (e *Engine) lock()   { e.mu <- struct{}{} }
func (e *Engine) unlock() { <-e.mu }

// nextSeq assigns the next audit sequence at the linearization point.
func (e *Engine) nextSeq() int64 {
	e.seq++
	return e.seq
}

func (e *Engine) record(key string, status Status, code ErrCode) AuditEntry {
	seq := e.nextSeq()
	en := AuditEntry{Seq: seq, Key: key, Status: status, Time: e.clock.Now(), Code: code}
	e.audit = append(e.audit, en)
	return en
}

// cashOf computes the notional qty*price with checked arithmetic.
func cashOf(req OrderRequest) (Amount, *Error) {
	v, err := mul64(int64(req.Qty), int64(req.Price))
	if err != nil {
		return 0, err
	}
	return Amount(v), nil
}

func sameRequest(a, b OrderRequest) bool {
	return a.AccountID == b.AccountID && a.Symbol == b.Symbol && a.Direction == b.Direction &&
		a.Qty == b.Qty && a.Price == b.Price && a.ExpiresAt == b.ExpiresAt
}

// Reserve atomically validates and debits cash, position and risk dimensions.
func (e *Engine) Reserve(req OrderRequest) (*OrderResult, *Error) {
	e.lock()
	defer e.unlock()

	if req.IdemKey == "" {
		return nil, e.denied(req, codeErr(ErrNegativeValue, "empty idempotency key"))
	}
	if req.Qty <= 0 {
		return nil, e.denied(req, codeErr(ErrNegativeValue, "qty must be positive"))
	}
	if req.Price <= 0 {
		return nil, e.denied(req, codeErr(ErrNegativeValue, "price must be positive"))
	}
	if req.Direction != Buy && req.Direction != Sell {
		return nil, e.denied(req, codeErr(ErrNegativeValue, "invalid direction"))
	}
	if req.Symbol == "" || req.AccountID == "" {
		return nil, e.denied(req, codeErr(ErrNegativeValue, "empty account or symbol"))
	}
	acc, ok := e.accounts[req.AccountID]
	if !ok {
		return nil, e.denied(req, codeErr(ErrUnknownAccount, "unknown account: "+req.AccountID))
	}
	notional, err := cashOf(req)
	if err != nil {
		return nil, e.denied(req, err)
	}
	now := e.clock.Now()
	if req.ExpiresAt != 0 && req.ExpiresAt < now {
		return nil, e.denied(req, codeErr(ErrInvalidTransition, "expires in the past"))
	}
	if prior, dup := e.orders[req.IdemKey]; dup {
		if !sameRequest(prior.Order, req) {
			return nil, e.denied(req, codeErr(ErrDuplicateConflict, "key "+req.IdemKey+" bound to different request"))
		}
		return prior, nil
	}

	// Dimension validation: all three are checked before any state changes,
	// so a rejection on one dimension leaves the others untouched.
	if req.Direction == Buy {
		sum, err := add64(int64(acc.committedCa+acc.resCash), int64(notional))
		if err != nil {
			return nil, e.denied(req, err)
		}
		if Amount(sum) > acc.spec.CashLimit {
			return nil, e.denied(req, codeErr(ErrInsufficientCash, "cash limit exceeded"))
		}
	}
	if req.Direction == Sell {
		held := acc.spec.Positions[req.Symbol]
		used := acc.committed[req.Symbol] + acc.resPosition[req.Symbol]
		need, err := add64(int64(used), int64(req.Qty))
		if err != nil {
			return nil, e.denied(req, err)
		}
		if Qty(need) > held {
			return nil, e.denied(req, codeErr(ErrInsufficientPosition, "sell exceeds available position"))
		}
	}
	riskSum, err := add64(int64(acc.committedRi+acc.resRisk), int64(notional))
	if err != nil {
		return nil, e.denied(req, err)
	}
	if Amount(riskSum) > acc.spec.RiskLimit {
		return nil, e.denied(req, codeErr(ErrInsufficientLimit, "risk limit exceeded"))
	}

	// Commit the reservation across all dimensions atomically.
	en := e.record(req.IdemKey, StatusReserved, ErrOK)
	res := &OrderResult{Seq: en.Seq, Status: StatusReserved, Order: req, Cash: notional}
	if req.Direction == Buy {
		acc.resCash += notional
	}
	acc.resRisk += notional
	if req.Direction == Sell {
		acc.resPosition[req.Symbol] += req.Qty
	}
	e.orders[req.IdemKey] = res
	if req.ExpiresAt != 0 {
		heap.Push(e.heap, expiryItem{expiresAt: req.ExpiresAt, seq: en.Seq, key: req.IdemKey})
	}
	return res, nil
}

func (e *Engine) denied(req OrderRequest, err *Error) *Error {
	en := e.record(req.IdemKey, 0, err.Code)
	err.Seq = en.Seq
	return err
}

// Confirm finalises a reservation, moving reserved amounts to committed usage.
func (e *Engine) Confirm(idemKey string) (*OrderResult, *Error) {
	e.lock()
	defer e.unlock()
	return e.transition(idemKey, StatusConfirmed)
}

// Release returns a still-open reservation's resources.
func (e *Engine) Release(idemKey string) (*OrderResult, *Error) {
	e.lock()
	defer e.unlock()
	return e.transition(idemKey, StatusReleased)
}

func (e *Engine) transition(key string, target Status) (*OrderResult, *Error) {
	res, ok := e.orders[key]
	if !ok {
		err := codeErr(ErrUnknownOrder, "unknown order: "+key)
		en := e.record(key, 0, err.Code)
		err.Seq = en.Seq
		return nil, err
	}
	// Idempotent replay of the same terminal action returns the first result.
	if res.Status == target {
		return res, nil
	}
	if res.Status != StatusReserved {
		err := codeErr(ErrInvalidTransition, "order "+key+" already "+res.Status.String())
		en := e.record(key, res.Status, err.Code)
		err.Seq = en.Seq
		return nil, err
	}

	acc := e.accounts[res.Order.AccountID]
	notional := res.Cash
	switch target {
	case StatusConfirmed:
		if res.Order.Direction == Buy {
			acc.resCash -= notional
			acc.committedCa += notional
		}
		acc.resRisk -= notional
		acc.committedRi += notional
		if res.Order.Direction == Sell {
			sym := res.Order.Symbol
			acc.resPosition[sym] -= res.Order.Qty
			acc.committed[sym] += res.Order.Qty
		}
	case StatusReleased:
		e.giveBack(acc, res)
	}
	res.Status = target
	e.removeFromHeap(key)
	en := e.record(key, target, ErrOK)
	res.Seq = en.Seq
	return res, nil
}

func (e *Engine) giveBack(acc *account, res *OrderResult) {
	if res.Order.Direction == Buy {
		acc.resCash -= res.Cash
	}
	acc.resRisk -= res.Cash
	if res.Order.Direction == Sell {
		acc.resPosition[res.Order.Symbol] -= res.Order.Qty
	}
}

func (e *Engine) removeFromHeap(key string) {
	e.heap.removeKey(key)
}

// ExpireDue reclaims reservations whose logical deadline has strictly passed.
// At the deadline tick itself the earlier-frozen reservation still wins: a
// Confirm linearized before the sweep at that tick succeeds, and the sweep
// only reclaims items with expiresAt < now. Ties within the sweep go to the
// earlier-frozen (lower seq). The rule reads only the injected logical clock,
// so wall time and goroutine scheduling cannot change the final state.
func (e *Engine) ExpireDue() []*OrderResult {
	e.lock()
	defer e.unlock()
	now := e.clock.Now()
	var out []*OrderResult
	for e.heap.Len() > 0 {
		top := e.heap.items[0]
		if top.expiresAt >= now {
			break
		}
		item := heap.Pop(e.heap).(expiryItem)
		res, ok := e.orders[item.key]
		if !ok || res.Status != StatusReserved {
			continue
		}
		e.giveBack(e.accounts[res.Order.AccountID], res)
		res.Status = StatusExpired
		en := e.record(item.key, StatusExpired, ErrOK)
		res.Seq = en.Seq
		out = append(out, res)
	}
	return out
}
