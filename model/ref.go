// Package model provides a serial reference model and a linearizability
// checker for the pre-trade risk reservation engine.
package model

import (
	"sort"

	risk "riskengine"
)

// Kind enumerates the operations that may appear in a history.
const (
	KindReserve   = "reserve"
	KindConfirm   = "confirm"
	KindRelease   = "release"
	KindExpireDue = "expire"
)

// Operation is one invocation in a recorded concurrent history.
type Operation struct {
	ClientID int
	Time     int64
	Kind     string
	Req      risk.OrderRequest
	Key      string
}

// Outcome captures the response observed by one client.
type Outcome struct {
	Result *risk.OrderResult
	Err    *risk.Error
}

// Seq returns the audit sequence the engine assigned at the linearization
// point, or 0 when the operation was never admitted.
func (o Outcome) Seq() int64 {
	if o.Result != nil {
		return o.Result.Seq
	}
	if o.Err != nil {
		return o.Err.Seq
	}
	return 0
}

// RecordedOp pairs an invocation with its observed outcome.
type RecordedOp struct {
	Op      Operation
	Outcome Outcome
}

func sameOutcome(a, b Outcome) bool {
	if a.Seq() != b.Seq() {
		return false
	}
	if (a.Err == nil) != (b.Err == nil) {
		return false
	}
	if a.Err != nil && a.Err.Code != b.Err.Code {
		return false
	}
	if (a.Result == nil) != (b.Result == nil) {
		return false
	}
	if a.Result != nil && a.Result.Status != b.Result.Status {
		return false
	}
	return true
}

// Apply replays one operation serially on the reference engine.
func Apply(eng *risk.Engine, op Operation) Outcome {
	switch op.Kind {
	case KindReserve:
		res, err := eng.Reserve(op.Req)
		return Outcome{Result: res, Err: err}
	case KindConfirm:
		res, err := eng.Confirm(op.Key)
		return Outcome{Result: res, Err: err}
	case KindRelease:
		res, err := eng.Release(op.Key)
		return Outcome{Result: res, Err: err}
	case KindExpireDue:
		expired := eng.ExpireDue()
		if len(expired) == 0 {
			return Outcome{}
		}
		return Outcome{Result: expired[len(expired)-1]}
	default:
		return Outcome{Err: &risk.Error{Code: risk.ErrInvalidTransition, Msg: "unknown op kind"}}
	}
}

// VerifyLinearizable replays the admitted operations in the audit-sequence
// order assigned by the concurrent engine. That order is the unique
// linearization point; the serial replay must reproduce every observed
// outcome and an identical canonical snapshot.
func VerifyLinearizable(
	specs []risk.AccountSpec,
	hist []RecordedOp,
	final *risk.Snapshot,
) bool {
	admitted := make([]RecordedOp, 0, len(hist))
	for _, h := range hist {
		if h.Outcome.Seq() != 0 {
			admitted = append(admitted, h)
		}
	}
	sort.SliceStable(admitted, func(i, j int) bool {
		return admitted[i].Outcome.Seq() < admitted[j].Outcome.Seq()
	})

	// Per-client program order must be respected by the serial order.
	last := map[int]int64{}
	for _, h := range admitted {
		cid := h.Op.ClientID
		if prev, ok := last[cid]; ok && h.Outcome.Seq() < prev {
			return false
		}
		last[cid] = h.Outcome.Seq()
	}

	// Replay with a deterministic manual clock advanced to each op's time.
	mc := &risk.ManualClock{}
	ref, err := risk.NewEngine(mc, specs)
	if err != nil {
		return false
	}
	for _, h := range admitted {
		got := Apply(ref, h.Op)
		if !sameOutcome(got, h.Outcome) {
			return false
		}
	}
	if final != nil {
		mc.T = final.Time
		got := ref.Snapshot()
		if !got.Equal(final) {
			return false
		}
	}
	return true
}
