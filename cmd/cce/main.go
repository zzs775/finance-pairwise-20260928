package main

import (
	"cce"
	"encoding/json"
	"fmt"
	"os"
	"time"
)

type scenario struct {
	AsOf       string            `json:"as_of"`
	Trades     []tradeInput      `json:"trades"`
	Agreements []agreementInput  `json:"agreements"`
	Collateral []collateralInput `json:"collateral"`
	Events     []eventInput      `json:"events"`
}

type tradeInput struct {
	ID           string `json:"id"`
	NettingSetID string `json:"netting_set"`
	Currency     string `json:"currency"`
	MtM          int64  `json:"mtm"`
	At           string `json:"at"`
}

type agreementInput struct {
	NettingSetID      string `json:"netting_set"`
	Version           int    `json:"version"`
	Currency          string `json:"currency"`
	Start             string `json:"start"`
	End               string `json:"end"`
	Threshold         int64  `json:"threshold"`
	MTA               int64  `json:"mta"`
	IndependentAmount int64  `json:"independent_amount"`
}

type collateralInput struct {
	NettingSetID string `json:"netting_set"`
	Currency     string `json:"currency"`
	Amount       int64  `json:"amount"`
}

type eventInput struct {
	NettingSetID string `json:"netting_set"`
	State        string `json:"state"`
	At           string `json:"at"`
}

type stepOutput struct {
	Version               int   `json:"version"`
	TradeCount            int   `json:"trade_count"`
	NetMtM                int64 `json:"net_mtm"`
	IndependentAmount     int64 `json:"independent_amount"`
	WithIndependentAmount int64 `json:"with_independent_amount"`
	Threshold             int64 `json:"threshold"`
	AfterThreshold        int64 `json:"after_threshold"`
	MTA                   int64 `json:"mta"`
	AfterMTA              int64 `json:"after_mta"`
	Collateral            int64 `json:"collateral"`
	Exposure              int64 `json:"exposure"`
}

type reportOutput struct {
	NettingSetID string     `json:"netting_set"`
	Currency     string     `json:"currency"`
	State        string     `json:"state"`
	Closed       bool       `json:"closed"`
	Steps        stepOutput `json:"steps"`
}

type snapshotOutput struct {
	Seq     int64          `json:"seq"`
	AsOf    string         `json:"as_of"`
	Reports []reportOutput `json:"reports"`
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var (
		sc  scenario
		err error
	)
	switch os.Args[1] {
	case "demo":
		sc = demoScenario()
	case "run":
		if len(os.Args) != 3 {
			usage()
			os.Exit(2)
		}
		sc, err = loadScenario(os.Args[2])
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}

	asOf, err := parseTime(sc.AsOf)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error: invalid as_of:", err)
		os.Exit(1)
	}

	eng := cce.NewEngine()
	for _, a := range sc.Agreements {
		v, err := convertAgreement(a)
		if err != nil {
			fatal("agreement", err)
		}
		if err := eng.UpsertAgreement(v); err != nil {
			fatal("agreement", err)
		}
	}
	for _, t := range sc.Trades {
		tr, err := convertTrade(t)
		if err != nil {
			fatal("trade", err)
		}
		if err := eng.UpsertTrade(tr); err != nil {
			fatal("trade", err)
		}
	}
	for _, c := range sc.Collateral {
		amt, err := cce.MkFixed(c.Amount, cce.Currency(c.Currency))
		if err != nil {
			fatal("collateral", err)
		}
		if err := eng.SetCollateral(c.NettingSetID, amt, asOf); err != nil {
			fatal("collateral", err)
		}
	}

	for _, ev := range sc.Events {
		st, err := parseState(ev.State)
		if err != nil {
			fatal("event", err)
		}
		at, err := parseTime(ev.At)
		if err != nil {
			fatal("event", err)
		}
		if err := eng.Transition(ev.NettingSetID, st, at); err != nil {
			fatal("event", err)
		}
	}

	snap, err := eng.Commit(asOf)
	if err != nil {
		fatal("commit", err)
	}

	out := snapshotOutput{Seq: snap.Seq(), AsOf: sc.AsOf}
	for _, r := range snap.Reports() {
		out.Reports = append(out.Reports, reportOutput{
			NettingSetID: r.NettingSetID,
			Currency:     string(r.Currency),
			State:        r.State.String(),
			Closed:       r.Closed,
			Steps: stepOutput{
				Version:               r.Steps.Version,
				TradeCount:            r.Steps.TradeCount,
				NetMtM:                r.Steps.NetMtM.Value(),
				IndependentAmount:     r.Steps.IndependentAmount.Value(),
				WithIndependentAmount: r.Steps.WithIndependentAmount.Value(),
				Threshold:             r.Steps.Threshold.Value(),
				AfterThreshold:        r.Steps.AfterThreshold.Value(),
				MTA:                   r.Steps.MTA.Value(),
				AfterMTA:              r.Steps.AfterMTA.Value(),
				Collateral:            r.Steps.Collateral.Value(),
				Exposure:              r.Steps.Exposure.Value(),
			},
		})
	}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(out); err != nil {
		os.Exit(1)
	}
}

func fatal(kind string, err error) {
	fmt.Fprintf(os.Stderr, "error: %s rejected [%s]: %v\n", kind, cce.ClassOf(err), err)
	os.Exit(1)
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage:")
	fmt.Fprintln(os.Stderr, "  cce demo           run built-in synthetic scenario")
	fmt.Fprintln(os.Stderr, "  cce run <file>     run a local JSON scenario")
}

func loadScenario(path string) (scenario, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return scenario{}, err
	}
	var sc scenario
	if err := json.Unmarshal(data, &sc); err != nil {
		return scenario{}, err
	}
	return sc, nil
}

func convertTrade(in tradeInput) (cce.Trade, error) {
	at, err := parseTime(in.At)
	if err != nil {
		return cce.Trade{}, err
	}
	mtm, err := cce.MkFixed(in.MtM, cce.Currency(in.Currency))
	if err != nil {
		return cce.Trade{}, err
	}
	return cce.Trade{
		ID:           in.ID,
		NettingSetID: in.NettingSetID,
		Currency:     cce.Currency(in.Currency),
		MtM:          mtm,
		At:           at,
	}, nil
}

func convertAgreement(in agreementInput) (cce.AgreementVersion, error) {
	start, err := parseTime(in.Start)
	if err != nil {
		return cce.AgreementVersion{}, err
	}
	var end time.Time
	if in.End != "" {
		end, err = parseTime(in.End)
		if err != nil {
			return cce.AgreementVersion{}, err
		}
	}
	cur := cce.Currency(in.Currency)
	threshold, err := cce.MkFixed(in.Threshold, cur)
	if err != nil {
		return cce.AgreementVersion{}, err
	}
	mta, err := cce.MkFixed(in.MTA, cur)
	if err != nil {
		return cce.AgreementVersion{}, err
	}
	ia, err := cce.MkFixed(in.IndependentAmount, cur)
	if err != nil {
		return cce.AgreementVersion{}, err
	}
	return cce.AgreementVersion{
		NettingSetID: in.NettingSetID,
		Version:      in.Version,
		Currency:     cur,
		Start:        start,
		End:          end,
		Params: cce.Params{
			Threshold:         threshold,
			MTA:               mta,
			IndependentAmount: ia,
		},
	}, nil
}

func parseTime(s string) (time.Time, error) {
	return time.Parse(time.RFC3339, s)
}

func parseState(s string) (cce.State, error) {
	switch s {
	case "open":
		return cce.StateOpen, nil
	case "margining":
		return cce.StateMargining, nil
	case "defaulted":
		return cce.StateDefaulted, nil
	case "closed":
		return cce.StateClosed, nil
	default:
		return 0, fmt.Errorf("unknown state %q", s)
	}
}

// demoScenario is fully local synthetic data covering a rule change and two
// netting sets with different currencies.
func demoScenario() scenario {
	return scenario{
		AsOf: "2026-03-31T00:00:00Z",
		Agreements: []agreementInput{
			{
				NettingSetID:      "NS-A",
				Version:           1,
				Currency:          "USD",
				Start:             "2026-01-01T00:00:00Z",
				End:               "2026-03-01T00:00:00Z",
				Threshold:         1000,
				MTA:               250,
				IndependentAmount: 100,
			},
			{
				NettingSetID:      "NS-A",
				Version:           2,
				Currency:          "USD",
				Start:             "2026-03-01T00:00:00Z",
				End:               "",
				Threshold:         500,
				MTA:               300,
				IndependentAmount: 0,
			},
			{
				NettingSetID:      "NS-B",
				Version:           1,
				Currency:          "EUR",
				Start:             "2026-01-01T00:00:00Z",
				End:               "",
				Threshold:         0,
				MTA:               0,
				IndependentAmount: 0,
			},
		},
		Trades: []tradeInput{
			{ID: "T1", NettingSetID: "NS-A", Currency: "USD", MtM: 1200, At: "2026-02-15T00:00:00Z"},
			{ID: "T2", NettingSetID: "NS-A", Currency: "USD", MtM: 900, At: "2026-03-15T00:00:00Z"},
			{ID: "T3", NettingSetID: "NS-B", Currency: "EUR", MtM: -400, At: "2026-03-15T00:00:00Z"},
			{ID: "T4", NettingSetID: "NS-B", Currency: "EUR", MtM: 700, At: "2026-03-20T00:00:00Z"},
		},
		Collateral: []collateralInput{
			{NettingSetID: "NS-A", Currency: "USD", Amount: 100},
			{NettingSetID: "NS-B", Currency: "EUR", Amount: 50},
		},
	}
}
