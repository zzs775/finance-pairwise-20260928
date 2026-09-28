package circuitbreaker

// State is one of the five explicit circuit breaker states.
type State uint8

const (
	StateOpen State = iota + 1
	StateHaltedLevel1
	StateHaltedLevel2
	StateClosed
	StateCooldown
)

func (s State) String() string {
	switch s {
	case StateOpen:
		return "open"
	case StateHaltedLevel1:
		return "halted_level1"
	case StateHaltedLevel2:
		return "halted_level2"
	case StateClosed:
		return "closed"
	case StateCooldown:
		return "cooldown"
	default:
		return "unknown"
	}
}

// Tradable reports whether the state permits trading.
func (s State) Tradable() bool {
	return s == StateOpen
}
