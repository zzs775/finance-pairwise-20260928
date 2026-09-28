package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	cb "circuitbreaker"
)

type initFile struct {
	Reference int64    `json:"reference"`
	Rules     wireRule `json:"rules"`
}

func main() {
	initPath := flag.String("init", "", "path to initial rules/reference JSON")
	checkpoint := flag.String("checkpoint", "", "checkpoint file (loaded if present, saved at end)")
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr,
			"usage: cb -init rules.json [-checkpoint state.ckpt] < events.jsonl\n"+
				"reads newline-delimited synthetic market events, emits state notices as JSON lines\n")
		flag.PrintDefaults()
	}
	flag.Parse()

	var init initFile
	if data, err := os.ReadFile(*initPath); err == nil {
		if err := json.Unmarshal(data, &init); err != nil {
			fatal("invalid init file: %v", err)
		}
	} else if *initPath == "" {
		fatal("-init is required for a fresh engine")
	} else {
		fatal("cannot read init file: %v", err)
	}

	clock := cb.NewLogicalClock(0)
	pub := cb.NewMemoryPublisher()

	var engine *cb.Engine
	if *checkpoint != "" {
		if restored, err := cb.LoadCheckpointFile(*checkpoint, clock, pub); err == nil {
			engine = restored
		} else if !os.IsNotExist(err) {
			fatal("refusing to start with corrupt checkpoint: %v", err)
		}
	}
	if engine == nil {
		var err error
		engine, err = cb.NewEngine(clock, pub, init.Rules.toDomain(), cb.Price(init.Reference))
		if err != nil {
			fatal("invalid initial state: %v", err)
		}
	}

	feeder := newStreamFeeder(os.Stdin, os.Stdout)
	if err := feeder.run(engine, pub); err != nil {
		fatal("%v", err)
	}

	if *checkpoint != "" {
		if err := cb.SaveCheckpoint(*checkpoint, engine); err != nil {
			fatal("failed to save checkpoint: %v", err)
		}
	}
}

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "cb: "+format+"\n", args...)
	os.Exit(1)
}
