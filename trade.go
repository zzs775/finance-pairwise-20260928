package cce

import "time"

// Trade is a single local synthetic position with a mark-to-market value at
// a valuation instant. A trade belongs to exactly one netting set.
type Trade struct {
	ID           string
	NettingSetID string
	Currency     Currency
	MtM          Fixed
	At           time.Time
}
