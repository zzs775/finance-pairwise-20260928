package cce

import (
	"fmt"
	"time"
)

// Formula (in-title order), every amount in the agreement currency:
//
//	net                = sum of trade MtM
//	afterIndependent   = net + independentAmount
//	afterThreshold     = max(0, afterIndependent - threshold)
//	afterMTA           = max(0, afterThreshold - MTA)
//	exposure           = max(0, afterMTA - collateral)
//
// IndependentExposure evaluates the same rule as a single expression so the
// engine can assert each reported result equals the full formula term by
// term. Exposure is never negative by construction.

// Steps records every intermediate value produced by the in-title formula.
type Steps struct {
	Version               int
	EvaluatedAt           time.Time
	TradeCount            int
	NetMtM                Fixed
	IndependentAmount     Fixed
	WithIndependentAmount Fixed
	Threshold             Fixed
	AfterThreshold        Fixed
	MTA                   Fixed
	AfterMTA              Fixed
	Collateral            Fixed
	Exposure              Fixed
}

// Report is the canonical per-netting-set result attached to a snapshot.
type Report struct {
	NettingSetID string
	Currency     Currency
	State        State
	Closed       bool
	Steps        Steps
}

// ComputeSteps runs trade netting, independent amount, threshold, MTA and
// collateral deduction in order and returns every intermediate value.
func ComputeSteps(trades []Trade, v AgreementVersion, collateral Fixed, at time.Time) (Steps, error) {
	const op = "ComputeSteps"
	cur := v.Currency

	net := Zero(cur)
	for _, t := range trades {
		if t.NettingSetID != v.NettingSetID {
			return Steps{}, classified(ClassTrade, op, ErrUnknownNettingSet,
				fmt.Sprintf("trade %s belongs to %s, agreement is %s",
					t.ID, t.NettingSetID, v.NettingSetID))
		}
		if t.Currency != cur {
			return Steps{}, currencyFailure(op+":"+t.ID, t.Currency, cur)
		}
		if t.MtM.Currency() != cur {
			return Steps{}, currencyFailure(op+":"+t.ID+" MtM", t.MtM.Currency(), cur)
		}
		sum, err := net.Add(t.MtM)
		if err != nil {
			return Steps{}, err
		}
		net = sum
	}

	if collateral.Currency() != cur {
		return Steps{}, currencyFailure(op+":collateral", collateral.Currency(), cur)
	}
	if v.Params.Threshold.Currency() != cur ||
		v.Params.MTA.Currency() != cur ||
		v.Params.IndependentAmount.Currency() != cur {
		return Steps{}, currencyFailure(op+":params", cur, cur)
	}

	afterIA, err := net.Add(v.Params.IndependentAmount)
	if err != nil {
		return Steps{}, err
	}
	lessThreshold, err := afterIA.Sub(v.Params.Threshold)
	if err != nil {
		return Steps{}, err
	}
	afterThreshold := MaxFixed(Zero(cur), lessThreshold)

	lessMTA, err := afterThreshold.Sub(v.Params.MTA)
	if err != nil {
		return Steps{}, err
	}
	afterMTA := MaxFixed(Zero(cur), lessMTA)

	lessCollateral, err := afterMTA.Sub(collateral)
	if err != nil {
		return Steps{}, err
	}
	exposure := MaxFixed(Zero(cur), lessCollateral)

	return Steps{
		Version:               v.Version,
		EvaluatedAt:           at,
		TradeCount:            len(trades),
		NetMtM:                net,
		IndependentAmount:     v.Params.IndependentAmount,
		WithIndependentAmount: afterIA,
		Threshold:             v.Params.Threshold,
		AfterThreshold:        afterThreshold,
		MTA:                   v.Params.MTA,
		AfterMTA:              afterMTA,
		Collateral:            collateral,
		Exposure:              exposure,
	}, nil
}

// IndependentExposure recomputes the final exposure with a single full
// formula; it must equal Steps.Exposure for every reported result.
func IndependentExposure(trades []Trade, v AgreementVersion, collateral Fixed, at time.Time) (Fixed, error) {
	const op = "IndependentExposure"
	cur := v.Currency

	net := Zero(cur)
	for _, t := range trades {
		if t.NettingSetID != v.NettingSetID {
			return Fixed{}, classified(ClassTrade, op, ErrUnknownNettingSet,
				fmt.Sprintf("trade %s belongs to %s, agreement is %s",
					t.ID, t.NettingSetID, v.NettingSetID))
		}
		if t.Currency != cur || t.MtM.Currency() != cur {
			return Fixed{}, currencyFailure(op+":"+t.ID, t.Currency, cur)
		}
		sum, err := net.Add(t.MtM)
		if err != nil {
			return Fixed{}, err
		}
		net = sum
	}

	ia := v.Params.IndependentAmount
	threshold := v.Params.Threshold
	mta := v.Params.MTA

	step, err := net.Add(ia)
	if err != nil {
		return Fixed{}, err
	}
	step, err = step.Sub(threshold)
	if err != nil {
		return Fixed{}, err
	}
	step = MaxFixed(Zero(cur), step)
	step, err = step.Sub(mta)
	if err != nil {
		return Fixed{}, err
	}
	step = MaxFixed(Zero(cur), step)
	step, err = step.Sub(collateral)
	if err != nil {
		return Fixed{}, err
	}
	return MaxFixed(Zero(cur), step), nil
}

// assertEquivalent recomputes the exposure independently and verifies every
// reported term against the single full formula. It returns the mismatch
// description (empty when equivalent).
func assertEquivalent(s Steps, trades []Trade, v AgreementVersion, collateral Fixed) string {
	full, err := IndependentExposure(trades, v, collateral, s.EvaluatedAt)
	if err != nil {
		return "full formula failed: " + err.Error()
	}
	if full.Value() != s.Exposure.Value() {
		return fmt.Sprintf("exposure mismatch: steps=%s full=%s", s.Exposure, full)
	}
	if s.Exposure.Value() < 0 {
		return "negative exposure"
	}

	checks := []struct {
		name string
		got  Fixed
		want Fixed
	}{
		{"net", s.NetMtM, sumTrades(trades, v.Currency)},
		{"independent", s.WithIndependentAmount, mustAdd(s.NetMtM, s.IndependentAmount)},
		{"threshold", s.AfterThreshold,
			MaxFixed(Zero(v.Currency), mustSub(s.WithIndependentAmount, s.Threshold))},
		{"mta", s.AfterMTA,
			MaxFixed(Zero(v.Currency), mustSub(s.AfterThreshold, s.MTA))},
		{"collateral", s.Exposure,
			MaxFixed(Zero(v.Currency), mustSub(s.AfterMTA, s.Collateral))},
	}
	for _, c := range checks {
		if c.got.Value() != c.want.Value() {
			return fmt.Sprintf("%s step mismatch: steps=%s recomputed=%s",
				c.name, c.got, c.want)
		}
	}
	return ""
}

func sumTrades(trades []Trade, cur Currency) Fixed {
	net := Zero(cur)
	for _, t := range trades {
		net, _ = net.Add(t.MtM)
	}
	return net
}

func mustAdd(a, b Fixed) Fixed {
	r, err := a.Add(b)
	if err != nil {
		panic(err)
	}
	return r
}

func mustSub(a, b Fixed) Fixed {
	r, err := a.Sub(b)
	if err != nil {
		panic(err)
	}
	return r
}
