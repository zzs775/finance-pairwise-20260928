package cce

import (
	"errors"
	"fmt"
)

// Classified sentinel failures. Inputs are rejected into exactly one class.
var (
	ErrOverflow          = errors.New("cce: fixed-point overflow")
	ErrCurrencyMismatch  = errors.New("cce: currency mismatch")
	ErrMissingAgreement  = errors.New("cce: no effective agreement version")
	ErrDuplicateVersion  = errors.New("cce: duplicate agreement version")
	ErrOverlappingWindow = errors.New("cce: overlapping agreement effective windows")
	ErrVersionGap        = errors.New("cce: gap between agreement effective windows")
	ErrDuplicateTrade    = errors.New("cce: duplicate trade identifier")
	ErrInvalidTransition = errors.New("cce: illegal state transition")
	ErrClosedNettingSet  = errors.New("cce: netting set is closed")
	ErrUnknownNettingSet = errors.New("cce: unknown netting set")
)

// FailureClass is the coarse category of a rejected operation.
type FailureClass int

const (
	ClassUnknown FailureClass = iota
	ClassOverflow
	ClassCurrency
	ClassAgreement
	ClassVersion
	ClassTrade
	ClassState
)

// String returns the canonical failure class name.
func (c FailureClass) String() string {
	switch c {
	case ClassOverflow:
		return "overflow"
	case ClassCurrency:
		return "currency"
	case ClassAgreement:
		return "agreement"
	case ClassVersion:
		return "version"
	case ClassTrade:
		return "trade"
	case ClassState:
		return "state"
	default:
		return "unknown"
	}
}

// Failure attaches a class and operation context to a sentinel error.
type Failure struct {
	Class  FailureClass
	Op     string
	Detail string
	Err    error
}

func (e *Failure) Error() string {
	if e.Detail == "" {
		return fmt.Sprintf("cce: %s: %v", e.Op, e.Err)
	}
	return fmt.Sprintf("cce: %s: %v (%s)", e.Op, e.Err, e.Detail)
}

func (e *Failure) Unwrap() error { return e.Err }

// ClassOf returns the coarse failure class of err, or ClassUnknown.
func ClassOf(err error) FailureClass {
	var f *Failure
	if errors.As(err, &f) {
		return f.Class
	}
	return ClassUnknown
}

func classified(class FailureClass, op string, sentinel error, detail string) *Failure {
	return &Failure{Class: class, Op: op, Err: sentinel, Detail: detail}
}

func currencyFailure(op string, got, want Currency) *Failure {
	return classified(
		ClassCurrency,
		op,
		ErrCurrencyMismatch,
		fmt.Sprintf("got %q want %q", got, want),
	)
}

func overflowFailure(op string, a, b int64, opSign string) *Failure {
	return classified(
		ClassOverflow,
		op,
		ErrOverflow,
		fmt.Sprintf("%d %s %d", a, opSign, b),
	)
}
