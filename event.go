package circuitbreaker

// Logical time, sequence numbers, prices and rule versions are all opaque
// integer quantities supplied by the event source.
type (
	Time        int64
	Duration    int64
	Seq         int64
	Version     int64
	Price       int64
	BasisPoints int64
)

// EventKind enumerates the inputs accepted by the state machine.
type EventKind uint8

const (
	KindTrade EventKind = iota + 1
	KindReference
	KindRules
	KindRecover
)

// Event is a single input: a synthetic trade, a versioned reference price,
// a rule version switch or a manual recovery command.
type Event struct {
	Kind        EventKind
	Time        Time
	Source      Seq
	RuleVersion Version
	Price       Price
	Rules       RuleSet
}

// RejectReason classifies why an event was refused.
type RejectReason string

const (
	RejectNone              RejectReason = ""
	RejectTimeRegress       RejectReason = "time_regression"
	RejectDuplicate         RejectReason = "duplicate_event"
	RejectDuplicateConflict RejectReason = "duplicate_conflict"
	RejectMissingReference  RejectReason = "missing_reference"
	RejectIllegalRules      RejectReason = "illegal_rules"
	RejectIllegalEvent      RejectReason = "illegal_event"
	RejectOverflow          RejectReason = "arithmetic_overflow"
)

// RejectError is returned for events that fail validation.
type RejectError struct {
	Reason RejectReason
	Detail string
}

func (e *RejectError) Error() string {
	if e.Detail == "" {
		return string(e.Reason)
	}
	return string(e.Reason) + ": " + e.Detail
}

func (k EventKind) String() string {
	switch k {
	case KindTrade:
		return "trade"
	case KindReference:
		return "reference"
	case KindRules:
		return "rules"
	case KindRecover:
		return "recover"
	default:
		return "unknown"
	}
}
