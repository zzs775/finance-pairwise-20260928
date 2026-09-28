package risk

// Code is the machine-readable classification of a rejected call.
type Code int

const (
	CodeOK Code = iota
	CodeNegative
	CodeUnknownAccount
	CodeUnknownKey
	CodeDuplicateConflict
	CodeInvalidArgument
	CodeOverflow
	CodeInsufficientCash
	CodeInsufficientPosition
	CodeInsufficientRisk
	CodeInvalidState
)

func (c Code) String() string {
	switch c {
	case CodeOK:
		return "ok"
	case CodeNegative:
		return "negative"
	case CodeUnknownAccount:
		return "unknown_account"
	case CodeUnknownKey:
		return "unknown_key"
	case CodeDuplicateConflict:
		return "duplicate_conflict"
	case CodeInvalidArgument:
		return "invalid_argument"
	case CodeOverflow:
		return "overflow"
	case CodeInsufficientCash:
		return "insufficient_cash"
	case CodeInsufficientPosition:
		return "insufficient_position"
	case CodeInsufficientRisk:
		return "insufficient_risk"
	case CodeInvalidState:
		return "invalid_state"
	default:
		return "unknown"
	}
}

// ClassifiedError is the typed error returned for classified failures.
type ClassifiedError struct {
	Code    Code
	Message string
}

func (e *ClassifiedError) Error() string {
	return e.Code.String() + ": " + e.Message
}

func classified(code Code, msg string) *ClassifiedError {
	return &ClassifiedError{Code: code, Message: msg}
}
