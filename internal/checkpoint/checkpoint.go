// Package checkpoint provides deterministic, self-verifying state snapshots.
//
// Frame layout (all bytes, little-endian fixed-width header):

//	magic "MMCK01\n" (8 bytes)
//	payload length (8 bytes, binary.LittleEndian)
//	SHA-256 of payload (32 bytes)
//	payload (canonical JSON, exactly length bytes)
//
// Any truncation, magic mismatch, length mismatch or checksum mismatch is
// reported as a classified KindCorruptCheckpt error, never as a silent reset.
package checkpoint

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"

	"marketmerge/internal/types"
)

// Magic prefixes every checkpoint frame.
var Magic = []byte("MMCK01\n")

const (
	headerLen = 8 + 8 + 32
)

// State is the opaque checkpoint payload container.
type State struct {
	// Format lets future payload revisions be detected explicitly.
	Format int             `json:"format"`
	Data   json.RawMessage `json:"data"`
}

// Encode frames and checksums an arbitrary JSON-serializable payload.
func Encode(v any) ([]byte, error) {
	// Marshal the payload as nested compact JSON inside a versioned envelope.
	inner, err := marshalCanonical(v)
	if err != nil {
		return nil, err
	}
	st := State{Format: 1, Data: inner}
	payload, err := marshalCanonical(st)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(payload)

	frame := make([]byte, 0, headerLen+len(payload))
	frame = append(frame, Magic...)
	var lenBuf [8]byte
	binary.LittleEndian.PutUint64(lenBuf[:], uint64(len(payload)))
	frame = append(frame, lenBuf[:]...)
	frame = append(frame, sum[:]...)
	frame = append(frame, payload...)
	return frame, nil
}

// Decode validates the frame and unmarshals the payload into v.
func Decode(frame []byte, v any) error {
	if len(frame) < headerLen {
		return corrupt("frame shorter than %d-byte header", headerLen)
	}
	if string(frame[:len(Magic)]) != string(Magic) {
		return corrupt("bad magic")
	}
	off := len(Magic)
	plen := binary.LittleEndian.Uint64(frame[off : off+8])
	off += 8
	gotSum := frame[off : off+32]
	off += 32
	if uint64(len(frame)-off) != plen {
		return corrupt("payload length mismatch: header says %d, frame has %d",
			plen, len(frame)-off)
	}
	payload := frame[off : off+int(plen)]
	wantSum := sha256.Sum256(payload)
	if !equalBytes(gotSum, wantSum[:]) {
		return corrupt("checksum mismatch")
	}
	var st State
	if err := json.Unmarshal(payload, &st); err != nil {
		return corrupt("payload not decodable: %v", err)
	}
	if st.Format != 1 {
		return corrupt("unsupported checkpoint format %d", st.Format)
	}
	if err := json.Unmarshal(st.Data, v); err != nil {
		return corrupt("state not decodable: %v", err)
	}
	return nil
}

// Marshal encodes state deterministically (raw JSON bytes, no framing).
func (s State) Marshal() ([]byte, error) { return marshalCanonical(s) }

// Unmarshal decodes state, returning a classified error on corruption.
func Unmarshal(data []byte) (State, error) {
	var st State
	if err := json.Unmarshal(data, &st); err != nil {
		return State{}, corrupt("%v", err)
	}
	return st, nil
}

func marshalCanonical(v any) ([]byte, error) {
	return json.Marshal(v)
}

func equalBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func corrupt(format string, args ...any) error {
	return &types.Error{
		Kind:    types.KindCorruptCheckpt,
		Message: fmt.Sprintf(format, args...),
	}
}

// IsCorrupt reports whether err is a classified checkpoint corruption.
func IsCorrupt(err error) bool {
	var te *types.Error
	if errors.As(err, &te) {
		return te.Kind == types.KindCorruptCheckpt
	}
	return false
}
