package circuitbreaker

import "sync"

// Notice is one published state observation.
type Notice struct {
	Time        Time
	State       State
	Tradable    bool
	RuleVersion Version
	Explanation string
}

// Publisher sinks state notices.
type Publisher interface {
	Publish(Notice) error
}

// MemoryPublisher records notices in process and can simulate publish failure.
type MemoryPublisher struct {
	mu      sync.Mutex
	notices []Notice
	fail    error
}

func NewMemoryPublisher() *MemoryPublisher {
	return &MemoryPublisher{notices: make([]Notice, 0)}
}

func (p *MemoryPublisher) Publish(n Notice) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.fail != nil {
		err := p.fail
		p.fail = nil
		return err
	}
	p.notices = append(p.notices, n)
	return nil
}

func (p *MemoryPublisher) Notices() []Notice {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]Notice, len(p.notices))
	copy(out, p.notices)
	return out
}

func (p *MemoryPublisher) FailNext(err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.fail = err
}
