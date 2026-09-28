package risk

import "fmt"

// Side is the order direction.
type Side int

const (
	Buy  Side = 1
	Sell Side = 2
)

func (s Side) valid() bool {
	return s == Buy || s == Sell
}

func (s Side) String() string {
	switch s {
	case Buy:
		return "buy"
	case Sell:
		return "sell"
	default:
		return fmt.Sprintf("side(%d)", int(s))
	}
}

// Status is the lifecycle state of a reservation.
type Status int

const (
	StatusNone Status = iota
	StatusReserved
	StatusConfirmed
	StatusReleased
	StatusExpired
)

func (s Status) String() string {
	switch s {
	case StatusReserved:
		return "reserved"
	case StatusConfirmed:
		return "confirmed"
	case StatusReleased:
		return "released"
	case StatusExpired:
		return "expired"
	default:
		return fmt.Sprintf("status(%d)", int(s))
	}
}

// Order is a locally synthesized pre-trade order.
type Order struct {
	Account  string
	Symbol   string
	Side     Side
	Price    int64
	Qty      int64
	Key      string
	ExpireAt uint64
}

// Result is the outcome of a mutating engine call.
type Result struct {
	OK            bool
	Code          Code
	Key           string
	Seq           uint64 // dense per-event audit sequence
	ReservationID uint64 // sequence of the underlying reservation
	Status        Status
	Replay        bool
}

// finger returns the canonical parameter fingerprint used to detect
// conflicting retries under the same idempotency key. The key itself is
// deliberately excluded.
func (o Order) finger() string {
	return fmt.Sprintf("%s\x00%s\x00%d\x00%d\x00%d\x00%d",
		o.Account, o.Symbol, int64(o.Side), o.Price, o.Qty, o.ExpireAt)
}

// notional returns Price*Qty with overflow detection.
func (o Order) notional() (int64, bool) {
	return Mul64(o.Price, o.Qty)
}
