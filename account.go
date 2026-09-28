package risk

// AccountSpec declares the total limits of a synthetic account.
type AccountSpec struct {
	ID       string
	Cash     int64
	Risk     int64
	Position map[string]int64
}

// account holds the live mutable risk state of one synthetic account.
//
// The cash dimension guards buy notional, the risk dimension guards gross
// notional on both sides and the position dimension guards sell quantity per
// symbol. On every dimension committed+reserved never exceeds the total.
type account struct {
	id       string
	cashTot  int64
	riskTot  int64
	cash     buckets
	risk     buckets
	position map[string]*position
}

// position is the per-symbol holdings state for sells.
type position struct {
	total     int64 // synthetic holdings
	reserved  int64 // qty locked by open sell reservations
	committed int64 // qty consumed by confirmed sells
}

// buckets tracks one limit dimension: committed exposure plus open reserves,
// both of which together must never exceed the total limit.
type buckets struct {
	reserved  int64
	committed int64
}

func newAccount(spec AccountSpec) (*account, error) {
	if spec.ID == "" {
		return nil, classified(CodeInvalidArgument, "empty account id")
	}
	if spec.Cash < 0 || spec.Risk < 0 {
		return nil, classified(CodeNegative, "negative account limit for "+spec.ID)
	}
	a := &account{
		id:       spec.ID,
		cashTot:  spec.Cash,
		riskTot:  spec.Risk,
		position: make(map[string]*position, len(spec.Position)),
	}
	for sym, qty := range spec.Position {
		if qty < 0 {
			return nil, classified(CodeNegative, "negative opening position for "+sym)
		}
		a.position[sym] = &position{total: qty}
	}
	return a, nil
}

// reserveClass classifies why a reservation of notional/qty cannot be taken;
// CodeOK means it fits. checked arithmetic guards every aggregate update.
func (a *account) reserveClass(side Side, symbol string, notional, qty int64) Code {
	if _, ok := Add64(a.risk.reserved, a.risk.committed); !ok {
		return CodeOverflow
	}
	used, ok := Add64(a.risk.reserved, a.risk.committed)
	if !ok {
		return CodeOverflow
	}
	if need, ok := Add64(used, notional); !ok || need > a.riskTot {
		return CodeInsufficientRisk
	}
	if side == Buy {
		used, ok = Add64(a.cash.reserved, a.cash.committed)
		if !ok {
			return CodeOverflow
		}
		if need, ok := Add64(used, notional); !ok || need > a.cashTot {
			return CodeInsufficientCash
		}
		return CodeOK
	}
	p := a.position[symbol]
	if p == nil {
		return CodeInsufficientPosition
	}
	used, ok = Add64(p.reserved, p.committed)
	if !ok {
		return CodeOverflow
	}
	if need, ok := Add64(used, qty); !ok || need > p.total {
		return CodeInsufficientPosition
	}
	return CodeOK
}

// applyReserve moves notional/qty into the reserved buckets. Callers must
// first prove the fit with reserveClass, so all arithmetic here succeeds.
func (a *account) applyReserve(side Side, symbol string, notional, qty int64) Code {
	r, ok := Add64(a.risk.reserved, notional)
	if !ok {
		return CodeOverflow
	}
	a.risk.reserved = r
	if side == Buy {
		c, ok := Add64(a.cash.reserved, notional)
		if !ok {
			return CodeOverflow
		}
		a.cash.reserved = c
		return CodeOK
	}
	p := a.position[symbol]
	q, ok := Add64(p.reserved, qty)
	if !ok {
		return CodeOverflow
	}
	p.reserved = q
	return CodeOK
}

// applyConfirm promotes a reservation into committed exposure.
func (a *account) applyConfirm(side Side, symbol string, notional, qty int64) Code {
	r, ok := Sub64(a.risk.reserved, notional)
	if !ok {
		return CodeOverflow
	}
	c, ok := Add64(a.risk.committed, notional)
	if !ok {
		return CodeOverflow
	}
	a.risk.reserved, a.risk.committed = r, c
	if side == Buy {
		r, ok = Sub64(a.cash.reserved, notional)
		if !ok {
			return CodeOverflow
		}
		c, ok = Add64(a.cash.committed, notional)
		if !ok {
			return CodeOverflow
		}
		a.cash.reserved, a.cash.committed = r, c
		return CodeOK
	}
	p := a.position[symbol]
	q, ok := Sub64(p.reserved, qty)
	if !ok {
		return CodeOverflow
	}
	done, ok := Add64(p.committed, qty)
	if !ok {
		return CodeOverflow
	}
	p.reserved, p.committed = q, done
	return CodeOK
}

// applyRelease drops a reservation without committing it.
func (a *account) applyRelease(side Side, symbol string, notional, qty int64) Code {
	r, ok := Sub64(a.risk.reserved, notional)
	if !ok {
		return CodeOverflow
	}
	a.risk.reserved = r
	if side == Buy {
		c, ok := Sub64(a.cash.reserved, notional)
		if !ok {
			return CodeOverflow
		}
		a.cash.reserved = c
		return CodeOK
	}
	p := a.position[symbol]
	q, ok := Sub64(p.reserved, qty)
	if !ok {
		return CodeOverflow
	}
	p.reserved = q
	return CodeOK
}
