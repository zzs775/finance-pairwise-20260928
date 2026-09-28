package circuitbreaker

// Clock is the injectable source of logical time.
type Clock interface {
	Now() Time
}

// LogicalClock is a monotonically non-decreasing manual clock.
type LogicalClock struct {
	now Time
}

func NewLogicalClock(start Time) *LogicalClock {
	return &LogicalClock{now: start}
}

func (c *LogicalClock) Now() Time {
	return c.now
}

func (c *LogicalClock) Advance(t Time) error {
	if t < c.now {
		return &RejectError{
			Reason: RejectTimeRegress,
			Detail: "clock cannot move backwards",
		}
	}
	c.now = t
	return nil
}
