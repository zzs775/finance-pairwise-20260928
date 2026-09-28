package cce

import (
	"errors"
	"testing"
)

func TestMkFixedRejectsEmptyCurrency(t *testing.T) {
	if _, err := MkFixed(10, ""); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("want currency failure, got %v", err)
	}
	if ClassOf(nil) != ClassUnknown {
		t.Fatalf("ClassOf(nil) = %v", ClassOf(nil))
	}
}

func TestCheckedAddSub(t *testing.T) {
	usd := Currency("USD")
	a := mustFixed(t, 120, usd)
	b := mustFixed(t, -45, usd)

	sum, err := a.Add(b)
	if err != nil || sum.Value() != 75 {
		t.Fatalf("Add = %d, %v", sum.Value(), err)
	}
	diff, err := a.Sub(b)
	if err != nil || diff.Value() != 165 {
		t.Fatalf("Sub = %d, %v", diff.Value(), err)
	}
	neg, err := b.Neg()
	if err != nil || neg.Value() != 45 {
		t.Fatalf("Neg = %d, %v", neg.Value(), err)
	}

	cmp, err := a.Cmp(b)
	if err != nil || cmp <= 0 {
		t.Fatalf("Cmp = %d, %v", cmp, err)
	}
	if MaxFixed(a, b).Value() != 120 {
		t.Fatal("MaxFixed picked wrong operand")
	}
}

func TestCurrencyMismatchClass(t *testing.T) {
	usd := mustFixed(t, 1, "USD")
	eur := mustFixed(t, 1, "EUR")
	if _, err := usd.Add(eur); !errors.Is(err, ErrCurrencyMismatch) ||
		ClassOf(err) != ClassCurrency {
		t.Fatalf("Add cross currency: %v (class %v)", err, ClassOf(err))
	}
	if _, err := usd.Sub(eur); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("Sub cross currency: %v", err)
	}
	if _, err := usd.Cmp(eur); !errors.Is(err, ErrCurrencyMismatch) {
		t.Fatalf("Cmp cross currency: %v", err)
	}
}

func TestOverflowClassification(t *testing.T) {
	usd := Currency("USD")
	big := mustFixed(t, maxInt64, usd)
	one := mustFixed(t, 1, usd)

	if _, err := big.Add(one); !errors.Is(err, ErrOverflow) ||
		ClassOf(err) != ClassOverflow {
		t.Fatalf("Add overflow: %v", err)
	}
	low := mustFixed(t, minInt64, usd)
	if _, err := low.Sub(one); !errors.Is(err, ErrOverflow) {
		t.Fatalf("Sub overflow: %v", err)
	}
	if _, err := low.Neg(); !errors.Is(err, ErrOverflow) {
		t.Fatalf("Neg overflow: %v", err)
	}
}

func mustFixed(t *testing.T, v int64, cur Currency) Fixed {
	t.Helper()
	f, err := MkFixed(v, cur)
	if err != nil {
		t.Fatal(err)
	}
	return f
}
