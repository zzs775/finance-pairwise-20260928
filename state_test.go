package cce

import (
	"errors"
	"testing"
)

func TestLegalTransitionPath(t *testing.T) {
	m := NewStateMachine()
	path := []State{StateMargining, StateDefaulted, StateClosed}
	for _, want := range path {
		if err := m.Transition(want, t1); err != nil {
			t.Fatalf("transition to %s: %v", want, err)
		}
		if m.State() != want {
			t.Fatalf("state = %s want %s", m.State(), want)
		}
	}
	if !m.IsClosed() {
		t.Fatal("expected closed")
	}
}

func TestIllegalTransitionKeepsState(t *testing.T) {
	m := NewStateMachine()
	// defaulted cannot return to open.
	if err := m.Transition(StateDefaulted, t1); err != nil {
		t.Fatal(err)
	}
	err := m.Transition(StateOpen, t2)
	if !errors.Is(err, ErrInvalidTransition) || ClassOf(err) != ClassState {
		t.Fatalf("defaulted -> open: %v", err)
	}
	if m.State() != StateDefaulted {
		t.Fatalf("state mutated by failed transition: %s", m.State())
	}

	if err := m.Transition(StateClosed, t2); err != nil {
		t.Fatalf("defaulted -> closed: %v", err)
	}
	err = m.Transition(StateDefaulted, t3)
	if !errors.Is(err, ErrInvalidTransition) {
		t.Fatalf("closed terminal: %v", err)
	}
	if m.State() != StateClosed {
		t.Fatalf("closed state mutated: %s", m.State())
	}
}

func TestFreezeOnlyClosedAndOnce(t *testing.T) {
	m := NewStateMachine()
	cur := Currency("USD")
	steps := Steps{
		EvaluatedAt: t1,
		Exposure:    mustFixed(t, 42, cur),
	}
	if err := m.Freeze(steps); !errors.Is(err, ErrInvalidTransition) {
		t.Fatalf("freeze while open: %v", err)
	}

	if err := m.Transition(StateClosed, t1); err != nil {
		t.Fatal(err)
	}
	if err := m.Freeze(steps); err != nil {
		t.Fatalf("first freeze: %v", err)
	}
	got, ok := m.FrozenSteps()
	if !ok || got.Exposure.Value() != 42 {
		t.Fatalf("frozen steps: %+v ok=%v", got, ok)
	}
	at, ok := m.FrozenAt()
	if !ok || !at.Equal(t1) {
		t.Fatalf("frozen at: %v ok=%v", at, ok)
	}

	if err := m.Freeze(steps); !errors.Is(err, ErrClosedNettingSet) {
		t.Fatalf("second freeze: %v", err)
	}
}

func TestFreezeRejectsNegativeExposure(t *testing.T) {
	m := NewStateMachine()
	if err := m.Transition(StateClosed, t1); err != nil {
		t.Fatal(err)
	}
	neg, err := mustFixed(t, 0, Currency("USD")).Sub(mustFixed(t, 1, Currency("USD")))
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Freeze(Steps{EvaluatedAt: t1, Exposure: neg}); !errors.Is(err, ErrClosedNettingSet) {
		t.Fatalf("negative freeze: %v", err)
	}
	if _, ok := m.FrozenSteps(); ok {
		t.Fatal("no freeze should exist after negative rejection")
	}
}
