from datetime import date
from decimal import Decimal
from unittest import TestCase

from dcurve.bootstrap import CurveHolder, bootstrap
from dcurve.constants import MAX_BISECTION_ITERATIONS
from dcurve.dates import (
    BusinessDayConvention,
    DayCountConvention,
    StubType,
    Tenor,
)
from dcurve.errors import (
    DuplicateNodeError,
    IterationLimitError,
    NoBracketError,
    NonFiniteValueError,
    QuoteValidationError,
    RepricingToleranceError,
)
from dcurve.instruments import (
    DepositQuote,
    FRAQuote,
    QuoteBook,
    SwapQuote,
)


def _deposit(identifier: str, maturity: date,
             rate: Decimal = Decimal("0.03"),
             start: date | None = None) -> DepositQuote:
    return DepositQuote(
        id=identifier, start=start or date(2025, 1, 13),
        maturity=maturity, rate=rate,
        day_count=DayCountConvention.ACT_360)


class StructuralValidationTest(TestCase):
    def test_duplicate_instrument_id_rejected(self) -> None:
        book = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(_deposit("X", date(2025, 4, 14)),),
            fras=(FRAQuote("X", date(2025, 4, 14), date(2025, 7, 14),
                           Decimal("0.03"), DayCountConvention.ACT_360),),
            swaps=(),
        )
        with self.assertRaises(QuoteValidationError):
            bootstrap(book)

    def test_non_positive_notional_rejected(self) -> None:
        deposit = DepositQuote(
            "D", date(2025, 1, 13), date(2025, 4, 14),
            Decimal("0.03"), DayCountConvention.ACT_360,
            notional=Decimal(0))
        book = QuoteBook(date(2025, 1, 13), (deposit,), (), ())
        with self.assertRaises(QuoteValidationError):
            bootstrap(book)

    def test_unknown_calendar_rejected(self) -> None:
        swap = SwapQuote(
            "S", date(2025, 1, 13), date(2026, 1, 13),
            Tenor.parse("6M"), Decimal("0.03"),
            DayCountConvention.ACT_360, "MARS",
            BusinessDayConvention.FOLLOWING, StubType.NONE)
        book = QuoteBook(date(2025, 1, 13), (), (), (swap,))
        with self.assertRaises(QuoteValidationError):
            bootstrap(book)


class DuplicateNodeTest(TestCase):
    def test_two_deposits_same_maturity_are_duplicate_nodes(self) -> None:
        book = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(
                _deposit("A", date(2025, 4, 14)),
                _deposit("B", date(2025, 4, 14)),
            ),
            fras=(), swaps=(),
        )
        with self.assertRaises(DuplicateNodeError):
            bootstrap(book)


class NoBracketTest(TestCase):
    def test_deposit_with_rate_below_minus_one_has_no_positive_root(self) -> None:
        # Proceeds 1 + r*tau <= 0 cannot be solved by a positive df.
        impossible = _deposit("BAD", date(2025, 4, 14),
                              rate=Decimal(-500))
        book = QuoteBook(date(2025, 1, 13), (impossible,), (), ())
        with self.assertRaises(NoBracketError):
            bootstrap(book)


class NonFiniteTest(TestCase):
    def test_nan_rate_classified_before_solving(self) -> None:
        bad = _deposit("BAD", date(2025, 4, 14), rate=Decimal("NaN"))
        book = QuoteBook(date(2025, 1, 13), (bad,), (), ())
        with self.assertRaises(QuoteValidationError):
            bootstrap(book)

    def test_non_finite_residual_is_classified(self) -> None:
        from dcurve.bootstrap import bisect_node

        def weird_residual(candidate_df: Decimal) -> Decimal:
            if str(candidate_df) == str(candidate_df):
                return Decimal("Infinity")
            return Decimal(0)

        with self.assertRaises(NonFiniteValueError):
            bisect_node(weird_residual, Decimal(1), "2025-04-14", "BAD")


class IterationLimitTest(TestCase):
    def test_iteration_cap_is_enforced(self) -> None:
        from unittest.mock import patch
        import dcurve.bootstrap as bs

        def residual(candidate_df: Decimal) -> Decimal:
            return candidate_df - Decimal("0.985")

        # Make both stopping thresholds unreachable so only the iteration
        # cap can terminate the loop.
        with patch.object(bs, "MAX_BISECTION_ITERATIONS", 3), \
                patch.object(bs, "BISECTION_WIDTH_TOLERANCE", Decimal(0)), \
                patch.object(bs, "SOLVER_RESIDUAL_TOLERANCE", Decimal(0)):
            with self.assertRaises(IterationLimitError):
                bs.bisect_node(residual, Decimal(1), "2025-04-14", "D")

        self.assertIsInstance(MAX_BISECTION_ITERATIONS, int)
        self.assertGreater(MAX_BISECTION_ITERATIONS, 0)


class RepricingFailureTest(TestCase):
    def test_inconsistent_group_repricing_fails_build(self) -> None:
        # Two par deposits sharing a maturity but different rates cannot
        # share one discount factor.
        book = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(
                _deposit("A", date(2025, 4, 14), Decimal("0.03")),
            ),
            fras=(FRAQuote(
                "B", date(2025, 1, 13), date(2025, 4, 14),
                Decimal("0.99"), DayCountConvention.ACT_360),),
            swaps=(),
        )
        with self.assertRaises((RepricingToleranceError, NoBracketError)):
            bootstrap(book)


class AtomicSwitchTest(TestCase):
    def test_failed_publish_keeps_previous_curve_queryable(self) -> None:
        good = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(_deposit("GOOD", date(2025, 4, 14)),),
            fras=(), swaps=(),
        )
        holder = CurveHolder()
        first = holder.publish(good)

        bad = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(_deposit("BAD", date(2025, 4, 14),
                               rate=Decimal(-500)),),
            fras=(), swaps=(),
        )
        with self.assertRaises(NoBracketError):
            holder.publish(bad)

        # Old version survives intact and is fully queryable.
        self.assertIs(holder.current, first.curve)
        self.assertEqual(holder.current.version_id,
                         first.curve.version_id)
        self.assertEqual(
            holder.current.discount_factor(date(2025, 4, 14)),
            first.curve.nodes[0].discount_factor)

    def test_non_finite_midpoint_aborts_and_keeps_old_curve(self) -> None:
        from unittest.mock import patch
        import dcurve.bootstrap as bs

        good = QuoteBook(
            value_date=date(2025, 1, 13),
            deposits=(_deposit("GOOD", date(2025, 4, 14)),),
            fras=(), swaps=(),
        )
        holder = CurveHolder()
        first = holder.publish(good)

        call_count = {"n": 0}

        def poisoned_price(quote, curve) -> Decimal:
            call_count["n"] += 1
            if call_count["n"] == 3:
                return Decimal("NaN")
            return curve.nodes[-1].discount_factor - Decimal(1)

        with patch.object(bs, "_price", side_effect=poisoned_price):
            with self.assertRaises(NonFiniteValueError):
                holder.publish(good)

        self.assertIs(holder.current, first.curve)
