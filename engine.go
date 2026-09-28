package circuitbreaker

import "fmt"

// Engine is the single-market multi-level circuit breaker state machine.
type Engine struct {
	clock Clock
	pub   Publisher

	lastTime Time
	seenSeq  map[Seq]Event

	win        *window
	timers     *timerHeap
	refs       *refBook
	activeRule Version
	rules      map[Version]RuleSet

	state State

	summary InputSummary
}

// InputSummary summarises everything the engine has consumed.
type InputSummary struct {
	Accepted int64
	Rejected int64
	LastSeq  Seq
}

func NewEngine(clock Clock, pub Publisher, initial RuleSet, ref Price) (*Engine, error) {
	if clock == nil {
		return nil, &RejectError{Reason: RejectIllegalEvent, Detail: "nil clock"}
	}
	if pub == nil {
		return nil, &RejectError{Reason: RejectIllegalEvent, Detail: "nil publisher"}
	}
	if err := initial.Validate(); err != nil {
		return nil, err
	}
	if ref <= 0 {
		return nil, &RejectError{Reason: RejectIllegalEvent, Detail: "initial reference price must be positive"}
	}
	e := &Engine{
		clock:      clock,
		pub:        pub,
		seenSeq:    make(map[Seq]Event),
		win:        newWindow(initial.Horizon),
		timers:     newTimerHeap(),
		refs:       newRefBook(),
		activeRule: initial.Version,
		rules:      make(map[Version]RuleSet),
		state:      StateOpen,
	}
	e.rules[initial.Version] = initial
	if err := e.refs.install(reference{
		version: initial.Version,
		price:   ref,
	}); err != nil {
		return nil, err
	}
	return e, nil
}

// Apply validates and ingests one event.
func (e *Engine) Apply(ev Event) error {
	if ev.Time < e.lastTime {
		e.summary.Rejected++
		return &RejectError{
			Reason: RejectTimeRegress,
			Detail: fmt.Sprintf("event time %d before %d", ev.Time, e.lastTime),
		}
	}
	if old, ok := e.seenSeq[ev.Source]; ok {
		if eventsEqual(old, ev) {
			return nil
		}
		e.summary.Rejected++
		return &RejectError{
			Reason: RejectDuplicateConflict,
			Detail: fmt.Sprintf("source seq %d reused with conflicting payload", ev.Source),
		}
	}
	if err := validateEvent(ev); err != nil {
		e.summary.Rejected++
		return err
	}
	e.seenSeq[ev.Source] = ev

	if err := e.Advance(ev.Time); err != nil {
		return err
	}
	var applyErr error
	switch ev.Kind {
	case KindTrade:
		applyErr = e.applyTrade(ev)
	case KindReference:
		applyErr = e.applyReference(ev)
	case KindRules:
		applyErr = e.applyRules(ev)
	case KindRecover:
		applyErr = e.applyRecover(ev)
	default:
		applyErr = &RejectError{Reason: RejectIllegalEvent, Detail: "unknown event kind"}
	}
	if applyErr != nil {
		e.summary.Rejected++
		return applyErr
	}
	e.summary.Accepted++
	if ev.Source > e.summary.LastSeq {
		e.summary.LastSeq = ev.Source
	}
	return nil
}

// Advance expires timers up to time t and publishes resulting notices.
func (e *Engine) Advance(t Time) error {
	if t < e.lastTime {
		return &RejectError{Reason: RejectTimeRegress, Detail: "advance before last time"}
	}
	if adv, ok := e.clock.(interface{ Advance(Time) error }); ok {
		if err := adv.Advance(t); err != nil {
			return err
		}
	}
	e.lastTime = t
	for _, tm := range e.timers.due(t) {
		if err := e.onTimer(tm, t); err != nil {
			return err
		}
	}
	return nil
}

func (e *Engine) StateName() State {
	return e.state
}

func (e *Engine) Summary() InputSummary {
	return e.summary
}

// eventsEqual reports whether two events with the same source seq are
// byte-for-byte the same logical input.
func eventsEqual(a, b Event) bool {
	return a.Kind == b.Kind &&
		a.Time == b.Time &&
		a.RuleVersion == b.RuleVersion &&
		a.Price == b.Price &&
		a.Rules == b.Rules
}

func validateEvent(ev Event) error {
	if ev.Source < 0 || ev.Time < 0 || ev.RuleVersion < 0 {
		return &RejectError{Reason: RejectIllegalEvent, Detail: "negative seq/time/version"}
	}
	switch ev.Kind {
	case KindTrade, KindReference:
		if ev.Price <= 0 {
			return &RejectError{Reason: RejectIllegalEvent, Detail: "price must be positive"}
		}
	case KindRules:
		if ev.Rules.Version != ev.RuleVersion {
			return &RejectError{Reason: RejectIllegalRules, Detail: "rules version mismatch"}
		}
		if err := ev.Rules.Validate(); err != nil {
			return err
		}
	case KindRecover:
	default:
		return &RejectError{Reason: RejectIllegalEvent, Detail: "unknown event kind"}
	}
	return nil
}

func (e *Engine) applyTrade(ev Event) error {
	rules, ok := e.rules[ev.RuleVersion]
	if !ok {
		return &RejectError{Reason: RejectIllegalRules, Detail: "unknown rule version"}
	}
	ref, ok := e.refs.priceAt(ev.Time, ev.RuleVersion)
	if !ok {
		return &RejectError{Reason: RejectMissingReference, Detail: "no effective reference for rule version"}
	}
	e.win.horizon = rules.Horizon
	e.win.add(tradePoint{
		time:        ev.Time,
		seq:         ev.Source,
		price:       ev.Price,
		ruleVersion: ev.RuleVersion,
	})
	e.win.evict(ev.Time)
	return e.evaluate(ev, rules, ref)
}

func (e *Engine) applyReference(ev Event) error {
	return e.refs.install(reference{
		version:   ev.RuleVersion,
		price:     ev.Price,
		effective: ev.Time,
	})
}

func (e *Engine) applyRules(ev Event) error {
	if ev.RuleVersion <= e.activeRule {
		return &RejectError{Reason: RejectIllegalRules, Detail: "rule version must increase"}
	}
	e.rules[ev.RuleVersion] = ev.Rules
	e.activeRule = ev.RuleVersion
	e.win.horizon = ev.Rules.Horizon
	e.win.evict(ev.Time)
	return nil
}

func (e *Engine) applyRecover(ev Event) error {
	return e.tryRecover(ev.Time, "manual recovery command")
}

// evaluate compares the worst retained trade under the current rule version
// against the three thresholds and drives state transitions.
func (e *Engine) evaluate(ev Event, rules RuleSet, ref Price) error {
	low, ok := e.win.minPriceSince(ev.Time-Time(rules.Horizon), ev.RuleVersion)
	if !ok {
		low = ev.Price
	}
	drop, fine := drawdownBP(ref, low)
	if !fine {
		return &RejectError{Reason: RejectOverflow, Detail: "drawdown overflow"}
	}

	switch {
	case drop >= rules.Level3:
		return e.close(ev.Time, drop, ref, low)
	case drop >= rules.Level2 && e.state != StateHaltedLevel2:
		if e.state == StateCooldown || e.state == StateClosed {
			return nil
		}
		if e.state == StateHaltedLevel1 {
			return e.upgrade(ev.Time, StateHaltedLevel2, rules, drop, ref, low)
		}
		if e.state == StateOpen {
			return e.halt(ev.Time, StateHaltedLevel2, rules, timerHaltExpire,
				rules.Halt2, drop, ref, low, "level2 threshold breached")
		}
	case drop >= rules.Level1 && e.state == StateOpen:
		return e.halt(ev.Time, StateHaltedLevel1, rules, timerHaltExpire,
			rules.Halt1, drop, ref, low, "level1 threshold breached")
	}
	if (e.state == StateHaltedLevel1 || e.state == StateHaltedLevel2) && drop < rules.Recover {
		return e.tryRecover(ev.Time, fmt.Sprintf(
			"trade seq %d confirms price recovery", ev.Source))
	}
	return nil
}

func (e *Engine) halt(now Time, st State, rules RuleSet, kind timerKind, dur Duration,
	drop BasisPoints, ref, low Price, why string,
) error {
	saved := e.stash()
	e.state = st
	e.timers.remove(timerHaltExpire)
	e.timers.push(now+Time(dur), kind)
	if err := e.publish(now, fmt.Sprintf(
		"%s: entered %s, drawdown %d bps (ref %d, low %d), halt for %d ticks",
		why, st, drop, ref, low, dur)); err != nil {
		e.restore(saved)
		return err
	}
	return nil
}

func (e *Engine) upgrade(now Time, st State, rules RuleSet,
	drop BasisPoints, ref, low Price,
) error {
	saved := e.stash()
	e.state = st
	e.timers.remove(timerHaltExpire)
	e.timers.push(now+Time(rules.Halt2), timerHaltExpire)
	if err := e.publish(now, fmt.Sprintf(
		"escalated to %s, drawdown %d bps (ref %d, low %d), halt extended by %d ticks",
		st, drop, ref, low, rules.Halt2)); err != nil {
		e.restore(saved)
		return err
	}
	return nil
}

func (e *Engine) close(now Time, drop BasisPoints, ref, low Price) error {
	saved := e.stash()
	e.state = StateClosed
	e.timers.remove(timerHaltExpire)
	e.win.reset()
	if err := e.publish(now, fmt.Sprintf(
		"level3 threshold breached: market closed, drawdown %d bps (ref %d, low %d)",
		drop, ref, low)); err != nil {
		e.restore(saved)
		return err
	}
	return nil
}

func (e *Engine) onTimer(tm *scheduledTimer, now Time) error {
	if tm.kind == timerCooldownExpire {
		if e.state == StateCooldown {
			saved := e.stash()
			e.state = StateOpen
			if err := e.publish(now, "cooldown elapsed: market reopened"); err != nil {
				e.restore(saved)
				return err
			}
		}
		return nil
	}
	if tm.kind == timerHaltExpire {
		if e.state == StateHaltedLevel1 || e.state == StateHaltedLevel2 {
			rules := e.rules[e.activeRule]
			saved := e.stash()
			e.state = StateCooldown
			e.timers.push(now+Time(rules.Cooldown), timerCooldownExpire)
			if err := e.publish(now, fmt.Sprintf(
				"halt elapsed: entered cooldown for %d ticks", rules.Cooldown)); err != nil {
				e.restore(saved)
				return err
			}
		}
	}
	return nil
}

func (e *Engine) tryRecover(now Time, why string) error {
	rules, ok := e.rules[e.activeRule]
	if !ok {
		return &RejectError{Reason: RejectIllegalRules, Detail: "active rules missing"}
	}
	ref, ok := e.refs.priceAt(now, e.activeRule)
	if !ok {
		return &RejectError{Reason: RejectMissingReference, Detail: "no reference for active version"}
	}
	if e.state == StateClosed {
		saved := e.stash()
		e.state = StateCooldown
		e.timers.push(now+Time(rules.Cooldown), timerCooldownExpire)
		if err := e.publish(now, fmt.Sprintf("%s: closed market reset into cooldown", why)); err != nil {
			e.restore(saved)
			return err
		}
		return nil
	}
	if e.state != StateHaltedLevel1 && e.state != StateHaltedLevel2 && e.state != StateCooldown {
		return &RejectError{Reason: RejectIllegalEvent, Detail: "recover not applicable in open state"}
	}
	low, hasTrades := e.win.minPriceSince(now-Time(rules.Horizon), e.activeRule)
	if !hasTrades {
		return &RejectError{Reason: RejectIllegalEvent, Detail: "recovery needs a recent trade to confirm price"}
	}
	drop, fine := drawdownBP(ref, low)
	if !fine {
		return &RejectError{Reason: RejectOverflow, Detail: "drawdown overflow"}
	}
	if drop >= rules.Recover {
		return &RejectError{
			Reason: RejectIllegalEvent,
			Detail: fmt.Sprintf("recovery condition unmet: drawdown %d bps >= %d bps", drop, rules.Recover),
		}
	}
	saved := e.stash()
	e.state = StateOpen
	e.timers.remove(timerHaltExpire)
	e.timers.remove(timerCooldownExpire)
	if err := e.publish(now, fmt.Sprintf(
		"%s: reopened, drawdown %d bps below recovery threshold %d bps",
		why, drop, rules.Recover)); err != nil {
		e.restore(saved)
		return err
	}
	return nil
}

// mutableState captures everything a transition may change, so a failed
// publish rolls back and never leaves a half-applied transition in place.
type mutableState struct {
	state   State
	win     []tradePoint
	timers  []*scheduledTimer
	counter int64
}

func (e *Engine) stash() mutableState {
	s := mutableState{
		state:   e.state,
		win:     append([]tradePoint(nil), e.win.points...),
		timers:  make([]*scheduledTimer, len(e.timers.items)),
		counter: e.timers.counter,
	}
	for i, t := range e.timers.items {
		cp := *t
		s.timers[i] = &cp
	}
	return s
}

func (e *Engine) restore(s mutableState) {
	e.state = s.state
	e.win.points = append([]tradePoint(nil), s.win...)
	e.timers.items = make([]*scheduledTimer, len(s.timers))
	for i, t := range s.timers {
		cp := *t
		e.timers.items[i] = &cp
	}
	e.timers.counter = s.counter
	timerHeapInit(e.timers)
}

func (e *Engine) publish(now Time, explanation string) error {
	n := Notice{
		Time:        now,
		State:       e.state,
		Tradable:    e.state.Tradable(),
		RuleVersion: e.activeRule,
		Explanation: explanation,
	}
	if e.state != StateOpen && n.Tradable {
		return &RejectError{Reason: RejectIllegalEvent, Detail: "refusing tradable notice while suspended"}
	}
	return e.pub.Publish(n)
}
