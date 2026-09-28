package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestCLIEndToEnd(t *testing.T) {
	if _, err := exec.LookPath("go"); err != nil {
		t.Skip("go toolchain unavailable")
	}
	root := repoRoot(t)
	bin := filepath.Join(t.TempDir(), "cb.exe")
	build := exec.Command("go", "build", "-o", bin, ".")
	build.Dir = filepath.Join(root, "cmd", "cb")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}

	ckpt := filepath.Join(t.TempDir(), "state.ckpt")
	events, err := os.Open(filepath.Join(root, "testdata", "scenario.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer events.Close()
	var stdout, stderr bytes.Buffer
	cmd := exec.Command(bin,
		"-init", filepath.Join(root, "testdata", "init.json"),
		"-checkpoint", ckpt)
	cmd.Stdin = events
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("run: %v\n%s", err, stderr.String())
	}

	out := stdout.String()
	wantStates := []string{"halted_level1", "halted_level2", "cooldown", "open"}
	for i, want := range wantStates {
		if !strings.Contains(out, `"state":"`+want+`"`) {
			t.Fatalf("output %d missing state %s:\n%s", i, want, out)
		}
	}
	if info, err := os.Stat(ckpt); err != nil || info.Size() == 0 {
		t.Fatalf("checkpoint not written: %v", err)
	}
	if strings.Contains(out, `"tradable":true`) {
		if idx := strings.Index(out, `"state":"halted_level1"`); idx >= 0 &&
			strings.Contains(out[:idx], `"tradable":true`) {
			t.Fatal("tradable notice precedes halt")
		}
	}
}

func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := filepath.Abs(".")
	if err != nil {
		t.Fatal(err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("go.mod not found")
		}
		dir = parent
	}
}
