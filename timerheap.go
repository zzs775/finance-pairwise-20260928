package circuitbreaker

import "container/heap"

// timerKind distinguishes halt expiry timers from cooldown timers.
type timerKind uint8

const (
	timerHaltExpire timerKind = iota + 1
	timerCooldownExpire
)

// scheduledTimer is one entry in the stable timer heap.
type scheduledTimer struct {
	id    int64
	due   Time
	kind  timerKind
	fired bool
}

// timerHeap is a stable min-heap keyed by (due time, insertion id).
type timerHeap struct {
	items   []*scheduledTimer
	counter int64
}

func newTimerHeap() *timerHeap {
	return &timerHeap{items: make([]*scheduledTimer, 0)}
}

// timerHeapInit re-establishes the heap invariant after bulk-loading items.
func timerHeapInit(h *timerHeap) {
	heap.Init(h)
}

func (h *timerHeap) push(due Time, kind timerKind) *scheduledTimer {
	h.counter++
	t := &scheduledTimer{id: h.counter, due: due, kind: kind}
	heap.Push(h, t)
	return t
}

func (h *timerHeap) remove(kind timerKind) {
	kept := h.items[:0]
	for _, t := range h.items {
		if t.kind == kind {
			t.fired = true
			continue
		}
		kept = append(kept, t)
	}
	h.items = kept
	heap.Init(h)
}

func (h *timerHeap) due(now Time) []*scheduledTimer {
	out := make([]*scheduledTimer, 0)
	for {
		top := h.peek()
		if top == nil || top.due > now {
			return out
		}
		t := heap.Pop(h).(*scheduledTimer)
		if t.fired {
			continue
		}
		t.fired = true
		out = append(out, t)
	}
}

func (h *timerHeap) peek() *scheduledTimer {
	if len(h.items) == 0 {
		return nil
	}
	return h.items[0]
}

func (h *timerHeap) Len() int { return len(h.items) }

func (h *timerHeap) Less(i, j int) bool {
	if h.items[i].due != h.items[j].due {
		return h.items[i].due < h.items[j].due
	}
	return h.items[i].id < h.items[j].id
}

func (h *timerHeap) Swap(i, j int) {
	h.items[i], h.items[j] = h.items[j], h.items[i]
}

func (h *timerHeap) Push(x any) {
	h.items = append(h.items, x.(*scheduledTimer))
}

func (h *timerHeap) Pop() any {
	last := len(h.items) - 1
	t := h.items[last]
	h.items = h.items[:last]
	return t
}
