// Package codec reads and writes local JSONL partition streams.
package codec

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"

	"marketmerge/internal/types"
)

var errTrailingData = errors.New("trailing data after JSON record")

// LineError reports a malformed JSONL record with its 1-based line number.
type LineError struct {
	Line int
	Raw  string
	Err  error
}

func (e *LineError) Error() string { return "jsonl line " + itoa(e.Line) + ": " + e.Err.Error() }
func (e *LineError) Unwrap() error { return e.Err }

// ReadJSONL streams messages from a JSONL reader.
// Blank lines are skipped; every other line must decode exactly one record.
func ReadJSONL(r io.Reader) ([]types.Message, error) {
	var out []types.Message
	err := ScanJSONL(r, func(m types.Message) error {
		out = append(out, m)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ScanJSONL invokes fn for every record in order.
func ScanJSONL(r io.Reader, fn func(types.Message) error) error {
	sc := bufio.NewScanner(r)
	// Allow generously large single records; the 64KiB scanner default is too
	// small for long synthetic partitions.
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	line := 0
	for sc.Scan() {
		line++
		raw := sc.Bytes()
		if len(bytes.TrimSpace(raw)) == 0 {
			continue
		}
		var m types.Message
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&m); err != nil {
			return &LineError{Line: line, Raw: string(raw), Err: err}
		}
		if dec.More() {
			return &LineError{Line: line, Raw: string(raw), Err: errTrailingData}
		}
		if err := fn(m); err != nil {
			return err
		}
	}
	return sc.Err()
}

// WriteJSONL writes messages to a JSONL writer, one record per line.
// Encoding is canonical: struct field order, no HTML escaping, LF newlines.
func WriteJSONL(w io.Writer, msgs []types.Message) error {
	bw := bufio.NewWriter(w)
	for i := range msgs {
		if err := WriteOne(bw, msgs[i]); err != nil {
			return err
		}
	}
	return bw.Flush()
}

// WriteOne writes a single message followed by a newline.
func WriteOne(w io.Writer, m types.Message) error {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(m); err != nil {
		return err
	}
	_, err := w.Write(buf.Bytes())
	return err
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [24]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
