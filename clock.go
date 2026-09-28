package riskengine

// Clock is an injectable logical clock measured in opaque logical ticks.
type Clock interface {
	Now() int64
}

// FuncClock adapts a function to the Clock interface.
type FuncClock func() int64

// ManualClock is a deterministic, externally advanced clock.
type ManualClock struct {
	T int64
}

func (f FuncClock) Now() int64 { return f() }

func (c *ManualClock) Now() int64 { return c.T }

// Advance moves the logical clock forward; negative deltas are rejected.
func (c *ManualClock) Advance(delta int64) *Error {
	if delta < 0 {
		return codeErr(ErrNegativeValue, "clock cannot move backwards")
	}
	next, err := add64(c.T, delta)
	if err != nil {
		return err
	}
	c.T = next
	return nil
}
