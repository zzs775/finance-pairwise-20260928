// Command marketmerge runs the multi-venue consolidation engine over local
// JSONL partition files.
//
// Subcommands:
//
//	generate  write deterministic synthetic partition JSONL files
//	run       stream files concurrently through the engine -> events JSONL
//	oracle    sort all input then replay -> canonical report JSON
//	verify    compare streaming output (any read interleaving) with oracle
//	recover   checkpoint, restore into a new engine, and verify equality
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"marketmerge/internal/codec"
	"marketmerge/internal/engine"
	"marketmerge/internal/oracle"
	"marketmerge/internal/synth"
	"marketmerge/internal/types"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "generate":
		err = cmdGenerate(os.Args[2:])
	case "run":
		err = cmdRun(os.Args[2:])
	case "oracle":
		err = cmdOracle(os.Args[2:])
	case "verify":
		err = cmdVerify(os.Args[2:])
	case "recover":
		err = cmdRecover(os.Args[2:])
	case "-h", "--help", "help":
		usage()
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "marketmerge:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `marketmerge - multi-venue L1 consolidation

usage: marketmerge <command> [flags] [files...]

commands:
  generate   write synthetic JSONL partitions
  run        stream files through the engine
  oracle     sort everything then replay
  verify     compare engine output to the oracle
  recover    checkpoint/restore and verify byte equality
`)
}

type genFlags struct {
	dir        string
	venues     int
	partitions int
	messages   int
	seed       int64
	gaps       bool
	duplicates bool
	correct    bool
	conflicts  bool
	shuffle    bool
}

func cmdGenerate(args []string) error {
	fs := flag.NewFlagSet("generate", flag.ContinueOnError)
	g := genFlags{}
	fs.StringVar(&g.dir, "dir", "data", "output directory")
	fs.IntVar(&g.venues, "venues", 4, "venue count (max 4)")
	fs.IntVar(&g.partitions, "partitions", 2, "quote partitions per venue")
	fs.IntVar(&g.messages, "messages", 24, "messages per partition")
	fs.Int64Var(&g.seed, "seed", 1, "PRNG seed")
	fs.BoolVar(&g.gaps, "gaps", false, "include gap-and-backfill scenario")
	fs.BoolVar(&g.duplicates, "duplicates", false, "include benign duplicates")
	fs.BoolVar(&g.correct, "corrections", false, "include correction pairs")
	fs.BoolVar(&g.conflicts, "conflicts", false, "include conflicting duplicate")
	fs.BoolVar(&g.shuffle, "shuffle", false, "deliver out of canonical order")
	if err := fs.Parse(args); err != nil {
		return err
	}

	ds := synth.Generate(synth.Config{
		Venues: g.venues, Partitions: g.partitions, Messages: g.messages,
		Seed: g.seed, Gaps: g.gaps, Duplicates: g.duplicates,
		Corrections: g.correct, Conflicts: g.conflicts, Shuffle: g.shuffle,
	})
	if err := os.MkdirAll(g.dir, 0o755); err != nil {
		return err
	}
	bySource := map[types.Source][]types.Message{}
	for _, m := range ds.Messages {
		s := m.Source()
		bySource[s] = append(bySource[s], m)
	}
	var srcs []types.Source
	for s := range bySource {
		srcs = append(srcs, s)
	}
	sort.Slice(srcs, func(i, j int) bool {
		if srcs[i].Venue != srcs[j].Venue {
			return srcs[i].Venue < srcs[j].Venue
		}
		return srcs[i].Partition < srcs[j].Partition
	})
	for _, s := range srcs {
		name := fmt.Sprintf("%s-%d.jsonl", s.Venue, s.Partition)
		path := filepath.Join(g.dir, name)
		f, err := os.Create(path)
		if err != nil {
			return err
		}
		if err := codec.WriteJSONL(f, bySource[s]); err != nil {
			f.Close()
			return err
		}
		f.Close()
	}
	fmt.Printf("wrote %d partition files to %s\n", len(srcs), g.dir)
	return nil
}

type runFlags struct {
	window  int
	budget  int
	workers int
}

func cmdRun(args []string) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	rf := runFlags{window: 16, budget: 100000, workers: 4}
	fs.IntVar(&rf.window, "window", 16, "bounded reorder window")
	fs.IntVar(&rf.budget, "budget", 100000, "total buffer budget")
	fs.IntVar(&rf.workers, "workers", 4, "concurrent file readers")
	if err := fs.Parse(args); err != nil {
		return err
	}
	msgs, err := readFilesConcurrent(fs.Args(), rf.workers)
	if err != nil {
		return err
	}
	eng := engine.New(rf.window, rf.budget)
	srcs, err := sourcesFromFiles(fs.Args())
	if err != nil {
		return err
	}
	eng.RegisterSources(srcs...)
	for _, m := range msgs {
		if _, ierr := eng.Ingest(m); ierr != nil {
			fmt.Fprintln(os.Stderr, "reject:", ierr)
		}
	}
	if _, derr := eng.Drain(); derr != nil {
		fmt.Fprintln(os.Stderr, "drain reject:", derr)
	}
	return writeEvents(os.Stdout, eng.Events())
}

func cmdOracle(args []string) error {
	fs := flag.NewFlagSet("oracle", flag.ContinueOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	msgs, err := readAll(fs.Args())
	if err != nil {
		return err
	}
	rep := oracle.New().Rebuild(msgs)
	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	return enc.Encode(rep)
}

func cmdVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	rf := runFlags{window: 16, budget: 100000, workers: 4}
	fs.IntVar(&rf.window, "window", 16, "bounded reorder window")
	fs.IntVar(&rf.budget, "budget", 100000, "total buffer budget")
	fs.IntVar(&rf.workers, "workers", 4, "concurrent file readers")
	if err := fs.Parse(args); err != nil {
		return err
	}
	msgs, err := readFilesConcurrent(fs.Args(), rf.workers)
	if err != nil {
		return err
	}
	eng := engine.New(rf.window, rf.budget)
	srcs, err := sourcesFromFiles(fs.Args())
	if err != nil {
		return err
	}
	eng.RegisterSources(srcs...)
	for _, m := range msgs {
		if _, ierr := eng.Ingest(m); ierr != nil {
			fmt.Fprintln(os.Stderr, "reject:", ierr)
		}
	}
	if _, derr := eng.Drain(); derr != nil {
		fmt.Fprintln(os.Stderr, "drain reject:", derr)
	}
	rep := oracle.New().Rebuild(msgs)
	got := oracle.EventsJSON(eng.Events())
	want := oracle.EventsJSON(rep.Events)
	if !bytes.Equal(got, want) {
		return fmt.Errorf("streaming output differs from oracle\n--- got ---\n%s\n--- want ---\n%s", got, want)
	}
	fmt.Printf("OK: %d canonical events match oracle\n", len(eng.Events()))
	return nil
}

type recFlags struct {
	window int
	budget int
	path   string
}

func cmdRecover(args []string) error {
	fs := flag.NewFlagSet("recover", flag.ContinueOnError)
	rc := recFlags{window: 16, budget: 100000, path: "checkpoint.bin"}
	fs.IntVar(&rc.window, "window", 16, "bounded reorder window")
	fs.IntVar(&rc.budget, "budget", 100000, "total buffer budget")
	fs.StringVar(&rc.path, "out", "checkpoint.bin", "checkpoint path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	msgs, err := readAll(fs.Args())
	if err != nil {
		return err
	}
	eng := engine.New(rc.window, rc.budget)
	srcs, err := sourcesFromFiles(fs.Args())
	if err != nil {
		return err
	}
	eng.RegisterSources(srcs...)
	for _, m := range msgs {
		if _, ierr := eng.Ingest(m); ierr != nil {
			fmt.Fprintln(os.Stderr, "reject:", ierr)
		}
	}
	if _, derr := eng.Drain(); derr != nil {
		fmt.Fprintln(os.Stderr, "drain reject:", derr)
	}
	frame1, err := eng.Checkpoint()
	if err != nil {
		return err
	}
	if err := os.WriteFile(rc.path, frame1, 0o644); err != nil {
		return err
	}
	frame2, err := os.ReadFile(rc.path)
	if err != nil {
		return err
	}
	restored := engine.New(rc.window, rc.budget)
	if err := restored.Restore(frame2); err != nil {
		return err
	}
	frame3, err := restored.Checkpoint()
	if err != nil {
		return err
	}
	if !bytes.Equal(frame1, frame3) {
		return fmt.Errorf("checkpoint round-trip is not byte-identical")
	}
	// Full replay through a fresh engine must match too.
	full := engine.New(rc.window, rc.budget)
	full.RegisterSources(srcs...)
	for _, m := range msgs {
		if _, ierr := full.Ingest(m); ierr != nil {
			fmt.Fprintln(os.Stderr, "replay reject:", ierr)
		}
	}
	if _, derr := full.Drain(); derr != nil {
		fmt.Fprintln(os.Stderr, "replay drain reject:", derr)
	}
	fullFrame, err := full.Checkpoint()
	if err != nil {
		return err
	}
	if !bytes.Equal(frame1, fullFrame) {
		return fmt.Errorf("full replay checkpoint differs")
	}
	fmt.Println("OK: checkpoint, restore and full replay are byte-identical")
	return nil
}

func readAll(files []string) ([]types.Message, error) {
	var all []types.Message
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		msgs, err := codec.ReadJSONL(bytes.NewReader(data))
		if err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		all = append(all, msgs...)
	}
	return all, nil
}

// sourcesFromFiles pre-declares every (venue, partition) by reading the first
// record of each file, letting the engine wait for unread sources correctly.
func sourcesFromFiles(files []string) ([]types.Source, error) {
	var srcs []types.Source
	seen := map[types.Source]bool{}
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		var first types.Message
		found := false
		err = codec.ScanJSONL(bytes.NewReader(data), func(m types.Message) error {
			if !found {
				first = m
				found = true
			}
			return nil
		})
		if err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		if found && !seen[first.Source()] {
			seen[first.Source()] = true
			srcs = append(srcs, first.Source())
		}
	}
	sort.Slice(srcs, func(i, j int) bool {
		if srcs[i].Venue != srcs[j].Venue {
			return srcs[i].Venue < srcs[j].Venue
		}
		return srcs[i].Partition < srcs[j].Partition
	})
	return srcs, nil
}

// readFilesConcurrent reads each file on its own goroutine. Each file keeps
// internal order; merge is intentionally arbitrary because the engine's
// canonical ordering makes output independent of arrival interleaving.
func readFilesConcurrent(files []string, workers int) ([]types.Message, error) {
	if workers <= 0 {
		workers = 1
	}
	sem := make(chan struct{}, workers)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error
	var all []types.Message
	for _, path := range files {
		path := path
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			data, err := os.ReadFile(path)
			if err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				mu.Unlock()
				return
			}
			msgs, err := codec.ReadJSONL(bytes.NewReader(data))
			if err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = fmt.Errorf("%s: %w", path, err)
				}
				mu.Unlock()
				return
			}
			mu.Lock()
			all = append(all, msgs...)
			mu.Unlock()
		}()
	}
	wg.Wait()
	if firstErr != nil {
		return nil, firstErr
	}
	return all, nil
}

func writeEvents(f *os.File, evs []engine.Event) error {
	enc := json.NewEncoder(f)
	enc.SetEscapeHTML(false)
	for _, ev := range evs {
		if err := enc.Encode(ev); err != nil {
			return err
		}
	}
	return nil
}
