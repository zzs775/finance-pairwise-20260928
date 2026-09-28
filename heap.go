package riskengine

import "container/heap"

// expiryItem tracks one live reservation in the expiry min-heap.
type expiryItem struct {
	expiresAt int64
	seq       int64
	key       string
	index     int
}

// expiryHeap orders reservations by (expiresAt, seq): same-instant
// confirmations are ordered before expirations via frozen priority.
type expiryHeap struct {
	items []expiryItem
	idx   map[string]int
}

func newExpiryHeap() *expiryHeap {
	return &expiryHeap{idx: map[string]int{}}
}

func (h *expiryHeap) Len() int { return len(h.items) }

// Less orders by expiry instant, then by reservation sequence. At the same
// instant a lower seq (frozen earlier) wins, giving deterministic expiry
// arbitration independent of wall time and goroutine scheduling.
func (h *expiryHeap) Less(i, j int) bool {
	if h.items[i].expiresAt != h.items[j].expiresAt {
		return h.items[i].expiresAt < h.items[j].expiresAt
	}
	return h.items[i].seq < h.items[j].seq
}

func (h *expiryHeap) Swap(i, j int) {
	h.items[i], h.items[j] = h.items[j], h.items[i]
	h.idx[h.items[i].key] = i
	h.idx[h.items[j].key] = j
}

func (h *expiryHeap) Push(x any) {
	it := x.(expiryItem)
	h.idx[it.key] = len(h.items)
	h.items = append(h.items, it)
}

func (h *expiryHeap) Pop() any {
	n := len(h.items)
	it := h.items[n-1]
	delete(h.idx, it.key)
	h.items = h.items[:n-1]
	return it
}

func (h *expiryHeap) removeKey(key string) bool {
	idx, ok := h.idx[key]
	if !ok {
		return false
	}
	heap.Remove(h, idx)
	return true
}

var _ heap.Interface = (*expiryHeap)(nil)
