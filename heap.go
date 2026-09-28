package risk

import "container/heap"

// expiryItem is one entry in the expiry queue.
type expiryItem struct {
	key      string
	expireAt uint64
	seq      uint64
}

// expiryHeap is a stable min-heap ordered by (expireAt, insertionSeq).
type expiryHeap struct {
	items []expiryItem
	index map[string]int
}

func newExpiryHeap() *expiryHeap {
	return &expiryHeap{index: make(map[string]int)}
}

func (h *expiryHeap) push(item expiryItem) {
	if _, exists := h.index[item.key]; exists {
		return
	}
	heap.Push(h, item)
}

func (h *expiryHeap) pop() (expiryItem, bool) {
	if len(h.items) == 0 {
		return expiryItem{}, false
	}
	item := heap.Pop(h).(expiryItem)
	return item, true
}

func (h *expiryHeap) remove(key string) bool {
	idx, ok := h.index[key]
	if !ok {
		return false
	}
	heap.Remove(h, idx)
	return true
}

func (h *expiryHeap) peek() (expiryItem, bool) {
	if len(h.items) == 0 {
		return expiryItem{}, false
	}
	return h.items[0], true
}

func (h *expiryHeap) len() int {
	return len(h.items)
}

// container/heap interface.

func (h *expiryHeap) Len() int { return len(h.items) }

func (h *expiryHeap) Less(i, j int) bool {
	a, b := h.items[i], h.items[j]
	if a.expireAt != b.expireAt {
		return a.expireAt < b.expireAt
	}
	return a.seq < b.seq
}

func (h *expiryHeap) Swap(i, j int) {
	h.items[i], h.items[j] = h.items[j], h.items[i]
	h.index[h.items[i].key] = i
	h.index[h.items[j].key] = j
}

func (h *expiryHeap) Push(x any) {
	item := x.(expiryItem)
	h.index[item.key] = len(h.items)
	h.items = append(h.items, item)
}

func (h *expiryHeap) Pop() any {
	n := len(h.items)
	item := h.items[n-1]
	h.items = h.items[:n-1]
	delete(h.index, item.key)
	return item
}
