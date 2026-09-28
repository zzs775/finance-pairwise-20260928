package risk

// Clock is the injectable time source. Implementations must be deterministic:
// wall-clock time or goroutine scheduling must never influence engine state.
type Clock interface {
	Now() uint64
}

// LogicalClock is a manually advanced monotonically non-decreasing tick clock.
type LogicalClock struct {
	tick uint64
}

func NewLogicalClock(start uint64) *LogicalClock {
	return &LogicalClock{tick: start}
}

func (c *LogicalClock) Now() uint64 {
	return c.tick
}

// Advance moves the clock forward by delta and returns the new tick.
func (c *LogicalClock) Advance(delta uint64) uint64 {
	if delta == 0 {
		return c.tick
	}
	c.tick += delta
	return c.tick
}

// Set jumps to tick t. Because the clock must remain monotonic, setting the
// clock backwards is rejected.
func (c *LogicalClock) Set(t uint64) bool {
	if t < c.tick {
		return false
	}
	c.tick = t
	return true
}
