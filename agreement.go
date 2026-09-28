package cce

import (
	"fmt"
	"sort"
	"time"
)

// Params holds the in-title agreement parameters for one effective version.
type Params struct {
	Threshold         Fixed
	MTA               Fixed
	IndependentAmount Fixed
}

// AgreementVersion is one immutable version of an agreement, effective on
// the half-open window [Start, End). A zero End means the window is open.
type AgreementVersion struct {
	NettingSetID string
	Version      int
	Currency     Currency
	Start        time.Time
	End          time.Time
	Params       Params
}

// Agreement is the validated, ordered version chain for one netting set.
type Agreement struct {
	nettingSet string
	currency   Currency
	versions   []AgreementVersion
}

// NewAgreement validates a version set (currency, order, coverage) and
// freezes it into an Agreement.
func NewAgreement(vs []AgreementVersion) (*Agreement, error) {
	const op = "NewAgreement"
	if len(vs) == 0 {
		return nil, classified(ClassAgreement, op, ErrMissingAgreement, "no versions supplied")
	}

	ns := vs[0].NettingSetID
	cur := vs[0].Currency
	if ns == "" {
		return nil, classified(ClassAgreement, op, ErrMissingAgreement, "version has empty netting set id")
	}
	if cur == "" {
		return nil, classified(ClassCurrency, op, ErrCurrencyMismatch, "version has empty currency")
	}

	seenVersion := make(map[int]struct{}, len(vs))
	for i, v := range vs {
		detail := fmt.Sprintf("version index %d number %d", i, v.Version)
		if v.NettingSetID != ns {
			return nil, classified(ClassAgreement, op, ErrMissingAgreement,
				detail+": netting set "+v.NettingSetID+" != "+ns)
		}
		if v.Currency != cur {
			return nil, currencyFailure(op+":"+detail, v.Currency, cur)
		}
		if _, dup := seenVersion[v.Version]; dup {
			return nil, classified(ClassVersion, op, ErrDuplicateVersion,
				fmt.Sprintf("netting set %s version %d", ns, v.Version))
		}
		seenVersion[v.Version] = struct{}{}
		if v.Params.Threshold.Currency() != cur ||
			v.Params.MTA.Currency() != cur ||
			v.Params.IndependentAmount.Currency() != cur {
			return nil, currencyFailure(op+":"+detail+" params",
				cur, cur)
		}
		if v.Params.Threshold.Value() < 0 || v.Params.MTA.Value() < 0 ||
			v.Params.IndependentAmount.Value() < 0 {
			return nil, classified(ClassAgreement, op, ErrMissingAgreement,
				detail+": negative agreement parameter")
		}
	}

	sorted := make([]AgreementVersion, len(vs))
	copy(sorted, vs)
	sort.SliceStable(sorted, func(i, j int) bool {
		return sorted[i].Start.Before(sorted[j].Start)
	})
	for i := 1; i < len(sorted); i++ {
		prev := sorted[i-1]
		cur := sorted[i]
		if !cur.Start.After(prev.Start) {
			return nil, classified(ClassVersion, op, ErrOverlappingWindow,
				fmt.Sprintf("netting set %s: starts %s and %s are not strictly ordered",
					ns, prev.Start.Format(time.RFC3339Nano), cur.Start.Format(time.RFC3339Nano)))
		}
		if prev.End.IsZero() {
			return nil, classified(ClassVersion, op, ErrOverlappingWindow,
				fmt.Sprintf("netting set %s version %d has open end before later version %d",
					ns, prev.Version, cur.Version))
		}
		switch {
		case cur.Start.Before(prev.End):
			return nil, classified(ClassVersion, op, ErrOverlappingWindow,
				fmt.Sprintf("netting set %s: versions %d and %d overlap (%s < %s)",
					ns, prev.Version, cur.Version,
					cur.Start.Format(time.RFC3339Nano),
					prev.End.Format(time.RFC3339Nano)))
		case cur.Start.After(prev.End):
			return nil, classified(ClassVersion, op, ErrVersionGap,
				fmt.Sprintf("netting set %s: gap between end %s and start %s",
					ns,
					prev.End.Format(time.RFC3339Nano),
					cur.Start.Format(time.RFC3339Nano)))
		}
	}

	return &Agreement{
		nettingSet: ns,
		currency:   cur,
		versions:   sorted,
	}, nil
}

// VersionAt returns the complete version effective at time t.
func (a *Agreement) VersionAt(t time.Time) (AgreementVersion, error) {
	const op = "Agreement.VersionAt"
	for _, v := range a.versions {
		if t.Before(v.Start) {
			continue
		}
		if v.End.IsZero() || t.Before(v.End) {
			return v, nil
		}
	}
	return AgreementVersion{}, classified(ClassAgreement, op, ErrMissingAgreement,
		fmt.Sprintf("netting set %s at %s", a.nettingSet, t.Format(time.RFC3339Nano)))
}

// Versions returns the versions in canonical ascending order.
func (a *Agreement) Versions() []AgreementVersion {
	out := make([]AgreementVersion, len(a.versions))
	copy(out, a.versions)
	return out
}
