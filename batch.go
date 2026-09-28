package circuitbreaker

import "sort"

// frozenPriority defines the order in which inputs sharing one logical time
// take effect, regardless of the order they arrive in:
// timer expirations first (handled inside Advance), then reference prices,
// rule switches, trades and finally recovery commands.
func frozenPriority(k EventKind) int {
	switch k {
	case KindReference:
		return 1
	case KindRules:
		return 2
	case KindTrade:
		return 3
	case KindRecover:
		return 4
	default:
		return 5
	}
}

type orderedEvent struct {
	idx int
	ev  Event
}

// ApplyBatch applies a slice of events under the frozen same-timestamp
// priority ordering. Events are first ordered by logical time, then by the
// frozen kind priority, and ties keep the caller's order (stable).
func (e *Engine) ApplyBatch(events []Event) error {
	ordered := make([]orderedEvent, len(events))
	for i, ev := range events {
		ordered[i] = orderedEvent{idx: i, ev: ev}
	}
	sort.SliceStable(ordered, func(i, j int) bool {
		a, b := ordered[i].ev, ordered[j].ev
		if a.Time != b.Time {
			return a.Time < b.Time
		}
		pa, pb := frozenPriority(a.Kind), frozenPriority(b.Kind)
		if pa != pb {
			return pa < pb
		}
		return ordered[i].idx < ordered[j].idx
	})
	for _, oe := range ordered {
		if err := e.Apply(oe.ev); err != nil {
			return err
		}
	}
	return nil
}
