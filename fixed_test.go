package risk

import (
	"math"
	"testing"
)

func TestAdd64Checked(t *testing.T) {
	cases := []struct {
		a, b int64
		want int64
		ok   bool
	}{
		{1, 2, 3, true},
		{-1, -2, -3, true},
		{math.MaxInt64, 1, 0, false},
		{math.MinInt64, -1, 0, false},
		{math.MaxInt64, 0, math.MaxInt64, true},
		{math.MinInt64, 0, math.MinInt64, true},
		{0, 0, 0, true},
	}
	for _, c := range cases {
		got, ok := Add64(c.a, c.b)
		if ok != c.ok || (ok && got != c.want) {
			t.Fatalf("Add64(%d,%d)=(%d,%v), want (%d,%v)", c.a, c.b, got, ok, c.want, c.ok)
		}
	}
}

func TestSub64Checked(t *testing.T) {
	cases := []struct {
		a, b int64
		want int64
		ok   bool
	}{
		{3, 2, 1, true},
		{-3, -2, -1, true},
		{math.MinInt64, 1, 0, false},
		{math.MaxInt64, -1, 0, false},
		{0, 0, 0, true},
	}
	for _, c := range cases {
		got, ok := Sub64(c.a, c.b)
		if ok != c.ok || (ok && got != c.want) {
			t.Fatalf("Sub64(%d,%d)=(%d,%v), want (%d,%v)", c.a, c.b, got, ok, c.want, c.ok)
		}
	}
}

func TestMul64Checked(t *testing.T) {
	cases := []struct {
		a, b int64
		want int64
		ok   bool
	}{
		{6, 7, 42, true},
		{-6, 7, -42, true},
		{-6, -7, 42, true},
		{math.MaxInt64, 2, 0, false},
		{math.MinInt64, 2, 0, false},
		{math.MinInt64, -1, 0, false},
		{100, 0, 0, true},
	}
	for _, c := range cases {
		got, ok := Mul64(c.a, c.b)
		if ok != c.ok || (ok && got != c.want) {
			t.Fatalf("Mul64(%d,%d)=(%d,%v), want (%d,%v)", c.a, c.b, got, ok, c.want, c.ok)
		}
	}
}
