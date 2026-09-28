package riskengine

// Amount is a fixed-point signed scalar for cash and credit limits.
// One unit in the last place equals one milli-unit (scale 10^-3).
type Amount int64

// Qty is a non-negative fixed-point instrument quantity (scale 10^-6).
type Qty int64

const maxAmt Amount = 1<<63 - 1
const maxQty Qty = 1<<63 - 1

// MaxAmount and MaxQty are the largest representable fixed-point values.
const MaxAmount Amount = maxAmt
const MaxQty Qty = maxQty

// ErrCode classifies every rejected engine operation.
type ErrCode int

const (
	ErrOK ErrCode = iota
	ErrNegativeValue
	ErrUnknownAccount
	ErrDuplicateConflict
	ErrOverflow
	ErrInsufficientCash
	ErrInsufficientPosition
	ErrInsufficientLimit
	ErrInvalidTransition
	ErrUnknownOrder
)

// Error is a classified engine error.
type Error struct {
	Code ErrCode
	Msg  string
	Seq  int64
}

func (e *Error) Error() string {
	return e.Code.String() + ": " + e.Msg
}

func codeErr(code ErrCode, msg string) *Error {
	return &Error{Code: code, Msg: msg}
}

func (c ErrCode) String() string {
	switch c {
	case ErrOK:
		return "ok"
	case ErrNegativeValue:
		return "negative value"
	case ErrUnknownAccount:
		return "unknown account"
	case ErrDuplicateConflict:
		return "duplicate key conflict"
	case ErrOverflow:
		return "overflow"
	case ErrInsufficientCash:
		return "insufficient cash"
	case ErrInsufficientPosition:
		return "insufficient position"
	case ErrInsufficientLimit:
		return "insufficient risk limit"
	case ErrInvalidTransition:
		return "invalid transition"
	case ErrUnknownOrder:
		return "unknown order"
	default:
		return "unknown error"
	}
}

// Direction of a synthetic order.
type Direction int

const (
	Buy Direction = iota + 1
	Sell
)

// Status is the lifecycle state of a reservation.
type Status int

const (
	StatusReserved Status = iota + 1
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
		return "invalid"
	}
}

func (d Direction) String() string {
	switch d {
	case Buy:
		return "buy"
	case Sell:
		return "sell"
	default:
		return "invalid"
	}
}

// OrderRequest is a synthetic pre-trade reservation request.
type OrderRequest struct {
	AccountID string
	Symbol    string
	Direction Direction
	Qty       Qty
	Price     Amount
	IdemKey   string
	ExpiresAt int64
}

// OrderResult is the outcome of an idempotency-keyed operation.
type OrderResult struct {
	Seq    int64
	Status Status
	Order  OrderRequest
	Cash   Amount
}

// AccountSpec declares a synthetic account's dimensions.
type AccountSpec struct {
	ID        string
	CashLimit Amount
	RiskLimit Amount
	Positions map[string]Qty
}
