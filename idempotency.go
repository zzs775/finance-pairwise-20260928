package risk

// idemEntry records the first call made under an idempotency key.
type idemEntry struct {
	finger string
	result Result
}

// idemTable stores first-seen results keyed by idempotency key.
type idemTable struct {
	entries map[string]idemEntry
}

func newIdemTable() *idemTable {
	return &idemTable{entries: make(map[string]idemEntry)}
}

func (t *idemTable) get(key string) (idemEntry, bool) {
	e, ok := t.entries[key]
	return e, ok
}

func (t *idemTable) put(key string, entry idemEntry) {
	if _, ok := t.entries[key]; ok {
		return
	}
	t.entries[key] = entry
}
