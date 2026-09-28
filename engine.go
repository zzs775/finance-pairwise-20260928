package cce

import (
	"sort"
	"time"
)

// pendingTransition is a staged lifecycle migration applied at Commit.
type pendingTransition struct {
	nettingSet string
	to         State
	at         time.Time
}

// Engine is the serial version publisher over an immutable snapshot store.
// Inputs are staged, validated, and applied only via Commit; duplicate
// updates are idempotent.
type Engine struct {
	store *Store

	trades      map[string]Trade
	versions    map[string][]AgreementVersion
	collateral  map[string]Fixed
	machines    map[string]*StateMachine
	transitions []pendingTransition
}

// NewEngine creates an empty engine.
func NewEngine() *Engine {
	return &Engine{
		store:      NewStore(),
		trades:     map[string]Trade{},
		versions:   map[string][]AgreementVersion{},
		collateral: map[string]Fixed{},
		machines:   map[string]*StateMachine{},
	}
}

// UpsertTrade stages a trade; a trade belongs to exactly one netting set.
func (e *Engine) UpsertTrade(t Trade) error {
	const op = "Engine.UpsertTrade"
	if t.ID == "" {
		return classified(ClassTrade, op, ErrDuplicateTrade, "trade has empty id")
	}
	if t.NettingSetID == "" {
		return classified(ClassTrade, op, ErrUnknownNettingSet,
			"trade "+t.ID+" has empty netting set id")
	}
	if t.Currency == "" || t.MtM.Currency() == "" {
		return classified(ClassCurrency, op, ErrCurrencyMismatch,
			"trade "+t.ID+" has empty currency")
	}
	if t.Currency != t.MtM.Currency() {
		return currencyFailure(op+":"+t.ID, t.MtM.Currency(), t.Currency)
	}
	if existing, ok := e.trades[t.ID]; ok && existing.NettingSetID != t.NettingSetID {
		return classified(ClassTrade, op, ErrDuplicateTrade,
			"trade "+t.ID+" reassigned from netting set "+
				existing.NettingSetID+" to "+t.NettingSetID)
	}
	if m := e.machines[t.NettingSetID]; m != nil && m.IsClosed() {
		return classified(ClassState, op, ErrClosedNettingSet,
			"trade "+t.ID+" cannot be upserted into closed netting set "+t.NettingSetID)
	}
	e.trades[t.ID] = t
	return nil
}

// UpsertAgreement stages one agreement version; duplicate versions are
// rejected and ordering must be gapless and non-overlapping.
func (e *Engine) UpsertAgreement(v AgreementVersion) error {
	const op = "Engine.UpsertAgreement"
	if v.NettingSetID == "" {
		return classified(ClassAgreement, op, ErrMissingAgreement,
			"version has empty netting set id")
	}
	if v.Version < 0 {
		return classified(ClassVersion, op, ErrDuplicateVersion,
			"negative version number")
	}
	if v.Currency == "" {
		return classified(ClassCurrency, op, ErrCurrencyMismatch,
			"version has empty currency")
	}
	if !v.End.IsZero() && !v.End.After(v.Start) {
		return classified(ClassVersion, op, ErrOverlappingWindow,
			"version end must be after start (half-open window)")
	}
	if v.Params.Threshold.Currency() != v.Currency ||
		v.Params.MTA.Currency() != v.Currency ||
		v.Params.IndependentAmount.Currency() != v.Currency {
		return currencyFailure(op+":params", v.Currency, v.Currency)
	}
	if v.Params.Threshold.Value() < 0 || v.Params.MTA.Value() < 0 ||
		v.Params.IndependentAmount.Value() < 0 {
		return classified(ClassAgreement, op, ErrMissingAgreement,
			"negative agreement parameter")
	}

	for _, existing := range e.versions[v.NettingSetID] {
		if existing.Version != v.Version {
			continue
		}
		if sameVersion(existing, v) {
			return nil // idempotent duplicate update
		}
		return classified(ClassVersion, op, ErrDuplicateVersion,
			"netting set "+v.NettingSetID+" version "+itoa(v.Version)+
				" already exists with different content")
	}
	for _, other := range e.versions[v.NettingSetID] {
		if other.Currency != v.Currency {
			return currencyFailure(op, v.Currency, other.Currency)
		}
	}

	e.versions[v.NettingSetID] = append(e.versions[v.NettingSetID], v)
	return nil
}

// SetCollateral stages the posted collateral amount for a netting set.
func (e *Engine) SetCollateral(nettingSet string, amount Fixed, at time.Time) error {
	const op = "Engine.SetCollateral"
	if nettingSet == "" {
		return classified(ClassTrade, op, ErrUnknownNettingSet,
			"empty netting set id")
	}
	if amount.Currency() == "" {
		return classified(ClassCurrency, op, ErrCurrencyMismatch,
			"collateral has empty currency")
	}
	if amount.Value() < 0 {
		return classified(ClassCurrency, op, ErrCurrencyMismatch,
			"negative collateral")
	}
	e.collateral[nettingSet] = amount
	return nil
}

// Transition applies a legal lifecycle migration.
func (e *Engine) Transition(nettingSet string, to State, at time.Time) error {
	const op = "Engine.Transition"
	if len(e.versions[nettingSet]) == 0 {
		return classified(ClassTrade, op, ErrUnknownNettingSet,
			"unknown netting set "+nettingSet)
	}
	m := e.machines[nettingSet]
	if m == nil {
		m = NewStateMachine()
		e.machines[nettingSet] = m
	}
	probe := *m
	// Apply already staged migrations for this set so pre-commit validation
	// sees the true prospective state; nothing is mutated on failure.
	staged := make([]pendingTransition, 0, len(e.transitions)+1)
	for _, tr := range e.transitions {
		if tr.nettingSet == nettingSet {
			staged = append(staged, tr)
		}
	}
	staged = append(staged, pendingTransition{
		nettingSet: nettingSet, to: to, at: at,
	})
	sort.SliceStable(staged, func(i, j int) bool {
		if staged[i].at.Equal(staged[j].at) {
			return staged[i].to < staged[j].to
		}
		return staged[i].at.Before(staged[j].at)
	})
	for _, tr := range staged {
		if err := probe.Transition(tr.to, tr.at); err != nil {
			return err // illegal: current state is untouched
		}
	}
	if to == StateClosed {
		ag, err := NewAgreement(e.versions[nettingSet])
		if err != nil {
			return err
		}
		if _, err := ag.VersionAt(at); err != nil {
			return err
		}
	}
	e.transitions = append(e.transitions, pendingTransition{
		nettingSet: nettingSet,
		to:         to,
		at:         at,
	})
	return nil
}

// Commit recomputes every netting set and publishes one immutable snapshot.
func (e *Engine) Commit(at time.Time) (*Snapshot, error) {
	const op = "Engine.Commit"

	nettingSets := make([]string, 0, len(e.versions))
	for ns := range e.versions {
		nettingSets = append(nettingSets, ns)
	}
	sort.Strings(nettingSets)

	for id, t := range e.trades {
		if _, ok := e.versions[t.NettingSetID]; !ok {
			return nil, classified(ClassAgreement, op, ErrMissingAgreement,
				"trade "+id+" netting set "+t.NettingSetID+" has no agreement")
		}
	}

	agreements := make(map[string]*Agreement, len(nettingSets))
	for _, ns := range nettingSets {
		ag, err := NewAgreement(e.versions[ns])
		if err != nil {
			return nil, err
		}
		agreements[ns] = ag
	}

	pending := make(map[string][]pendingTransition)
	for _, tr := range e.transitions {
		pending[tr.nettingSet] = append(pending[tr.nettingSet], tr)
	}
	for ns := range pending {
		sort.SliceStable(pending[ns], func(i, j int) bool {
			if pending[ns][i].at.Equal(pending[ns][j].at) {
				return pending[ns][i].to < pending[ns][j].to
			}
			return pending[ns][i].at.Before(pending[ns][j].at)
		})
	}

	nextMachines := make(map[string]*StateMachine, len(e.machines))
	for ns, m := range e.machines {
		clone := *m
		nextMachines[ns] = &clone
	}

	reports := make([]Report, 0, len(nettingSets))
	for _, ns := range nettingSets {
		ag := agreements[ns]
		m := nextMachines[ns]
		if m == nil {
			m = NewStateMachine()
			nextMachines[ns] = m
		}

		for _, tr := range pending[ns] {
			if err := m.Transition(tr.to, tr.at); err != nil {
				return nil, err
			}
		}

		collateral, hasCollateral := e.collateral[ns]
		if !hasCollateral {
			collateral = Zero(ag.currency)
		}
		if collateral.Currency() != ag.currency {
			return nil, currencyFailure(op+":collateral:"+ns,
				collateral.Currency(), ag.currency)
		}

		var report Report
		if frozen, ok := m.FrozenSteps(); ok {
			report = Report{
				NettingSetID: ns,
				Currency:     ag.currency,
				State:        StateClosed,
				Closed:       true,
				Steps:        frozen,
			}
		} else {
			valAt := at
			if m.IsClosed() {
				valAt = lastCloseAt(pending[ns])
			}
			version, err := ag.VersionAt(valAt)
			if err != nil {
				return nil, err
			}
			selected, err := e.selectTrades(ns, ag, version, valAt, op)
			if err != nil {
				return nil, err
			}
			steps, err := ComputeSteps(selected, version, collateral, valAt)
			if err != nil {
				return nil, err
			}
			if mismatch := assertEquivalent(steps, selected, version, collateral); mismatch != "" {
				return nil, classified(ClassUnknown, op, ErrOverflow, mismatch)
			}
			if m.IsClosed() {
				if err := m.Freeze(steps); err != nil {
					return nil, err
				}
			}
			report = Report{
				NettingSetID: ns,
				Currency:     ag.currency,
				State:        m.State(),
				Closed:       m.IsClosed(),
				Steps:        steps,
			}
		}
		reports = append(reports, report)
	}

	snap, err := e.store.publish(reports, at)
	if err != nil {
		return nil, err
	}
	e.machines = nextMachines
	e.transitions = nil
	return snap, nil
}

// Current returns the latest published snapshot.
func (e *Engine) Current() *Snapshot { return e.store.Current() }

// selectTrades returns exactly the trades governed by the version selected
// for the valuation, deterministically ordered by (At, ID).
func (e *Engine) selectTrades(
	ns string,
	ag *Agreement,
	selected AgreementVersion,
	valAt time.Time,
	op string,
) ([]Trade, error) {
	var out []Trade
	for _, t := range e.trades {
		if t.NettingSetID != ns || t.At.After(valAt) {
			continue
		}
		v, err := ag.VersionAt(t.At)
		if err != nil {
			return nil, classified(ClassAgreement, op+":trade:"+t.ID,
				ErrMissingAgreement, err.Error())
		}
		if v.Version != selected.Version {
			continue
		}
		out = append(out, t)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].At.Equal(out[j].At) {
			return out[i].ID < out[j].ID
		}
		return out[i].At.Before(out[j].At)
	})
	return out, nil
}

func lastCloseAt(trs []pendingTransition) time.Time {
	var closeAt time.Time
	for _, tr := range trs {
		if tr.to == StateClosed {
			closeAt = tr.at
		}
	}
	return closeAt
}

func sameVersion(a, b AgreementVersion) bool {
	return a.NettingSetID == b.NettingSetID &&
		a.Version == b.Version &&
		currencyEq(a.Currency, b.Currency) &&
		a.Start.Equal(b.Start) &&
		((a.End.IsZero() && b.End.IsZero()) || a.End.Equal(b.End)) &&
		a.Params.Threshold.Value() == b.Params.Threshold.Value() &&
		a.Params.MTA.Value() == b.Params.MTA.Value() &&
		a.Params.IndependentAmount.Value() == b.Params.IndependentAmount.Value()
}

func currencyEq(a, b Currency) bool { return a == b }

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	var b [24]byte
	i := len(b)
	for v != 0 {
		i--
		d := v % 10
		if neg {
			d = -d
		}
		b[i] = byte('0' + d)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
