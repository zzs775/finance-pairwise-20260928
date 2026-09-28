package cce

import (
	"fmt"
	"time"
)

// State is the explicit lifecycle state of a netting set.
type State int

const (
	StateOpen State = iota
	StateMargining
	StateDefaulted
	StateClosed
)

// String returns the canonical lowercase state name.
func (s State) String() string {
	switch s {
	case StateOpen:
		return "open"
	case StateMargining:
		return "margining"
	case StateDefaulted:
		return "defaulted"
	case StateClosed:
		return "closed"
	default:
		return "unknown"
	}
}

// legalTransitions is the explicit migration table. Closed is terminal.
var legalTransitions = map[State]map[State]bool{
	StateOpen: {
		StateMargining: true,
		StateDefaulted: true,
		StateClosed:    true,
	},
	StateMargining: {
		StateOpen:      true,
		StateDefaulted: true,
		StateClosed:    true,
	},
	StateDefaulted: {
		StateClosed: true,
	},
	StateClosed: {},
}

// StateMachine enforces the legal lifecycle of one netting set and holds the
// frozen closeout snapshot once the set is closed.
type StateMachine struct {
	state     State
	closedAt  time.Time
	frozen    Steps
	hasFrozen bool
}

// NewStateMachine starts a netting set in the open state.
func NewStateMachine() *StateMachine { return &StateMachine{state: StateOpen} }

// State returns the current state.
func (m *StateMachine) State() State { return m.state }

// Transition applies a legal migration; illegal migrations leave the
// current state untouched.
func (m *StateMachine) Transition(to State, at time.Time) error {
	if legalTransitions[m.state][to] {
		m.state = to
		return nil
	}
	return classified(ClassState, "StateMachine.Transition", ErrInvalidTransition,
		fmt.Sprintf("cannot move %s -> %s at %s", m.state, to, at.Format(time.RFC3339Nano)))
}

// Freeze records the closeout snapshot when entering the closed state.
func (m *StateMachine) Freeze(s Steps) error {
	if m.state != StateClosed {
		return classified(ClassState, "StateMachine.Freeze", ErrInvalidTransition,
			"freeze is only valid in the closed state; current state is "+m.state.String())
	}
	if m.hasFrozen {
		return classified(ClassState, "StateMachine.Freeze", ErrClosedNettingSet,
			"closeout snapshot is already frozen")
	}
	if s.Exposure.Value() < 0 {
		return classified(ClassState, "StateMachine.Freeze", ErrClosedNettingSet,
			"refusing to freeze a negative closeout exposure")
	}
	m.frozen = s
	m.closedAt = s.EvaluatedAt
	m.hasFrozen = true
	return nil
}

// FrozenAt returns the closeout time and whether a freeze exists.
func (m *StateMachine) FrozenAt() (time.Time, bool) {
	return m.closedAt, m.hasFrozen
}

// FrozenSteps returns the frozen closeout steps.
func (m *StateMachine) FrozenSteps() (Steps, bool) {
	if !m.hasFrozen {
		return Steps{}, false
	}
	return m.frozen, true
}

// IsClosed reports whether the terminal state has been reached.
func (m *StateMachine) IsClosed() bool { return m.state == StateClosed }
