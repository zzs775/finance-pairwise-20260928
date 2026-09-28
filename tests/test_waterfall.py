import copy
import unittest
from decimal import Decimal

from abs_waterfall.errors import (
    DuplicateTrancheError,
    InsufficientFundsError,
    NegativeCashError,
    PriorityConflictError,
    TriggerConfigurationError,
)
from abs_waterfall.models import (
    Fee,
    PoolPeriod,
    PrincipalMode,
    PriorityProfile,
    ReserveSpec,
    Tranche,
    Trigger,
    WaterfallConfig,
)
from abs_waterfall.oracle import assert_cash_closure, assert_loss_closure
from abs_waterfall.waterfall import (
    run_period,
    validate_config,
    validate_period,
)

from tests.fixtures import build_config, dlq_trigger, period


class WaterfallTest(unittest.TestCase):
    def test_normal_sequential_period_closes(self) -> None:
        config = build_config()
        result = run_period(config, period(0))
        ending = result.ending_state
        self.assertEqual(result.profile_id, "normal")
        self.assertEqual(ending.fees_paid["servicing"], Decimal("10.00"))
        self.assertEqual(ending.interest_paid["A"], Decimal("35.00"))
        self.assertEqual(ending.interest_paid["B"], Decimal("16.00"))
        self.assertEqual(ending.principal_paid["A"], Decimal("79.00"))
        self.assertEqual(ending.reserve_cash, Decimal("20.00"))
        assert_cash_closure(config, period(0), result)

    def test_trigger_switches_to_pro_rata(self) -> None:
        config = build_config(trigger=dlq_trigger())
        hot = period(1, pool="900", interest="50", principal="120",
                     losses="30", dlq="0.60")
        result = run_period(config, hot)
        self.assertEqual(result.fired_triggers, ("dlq_trigger",))
        self.assertEqual(result.profile_id, "lockout")
        principal = result.ending_state.principal_paid
        # all three notes share principal in a pro-rata period
        self.assertGreater(principal["A"], Decimal(0))
        self.assertGreater(principal["B"], Decimal(0))
        self.assertGreater(principal["EQ"], Decimal(0))
        assert_cash_closure(config, hot, result)
        assert_loss_closure(config, hot, result)

    def test_pro_rata_remainder_is_stable_and_closes(self) -> None:
        tranches = (
            Tranche("A", 1, Decimal("100.00"), Decimal("0")),
            Tranche("B", 2, Decimal("100.00"), Decimal("0")),
            Tranche("EQ", 3, Decimal("100.00"), Decimal("0")),
        )
        config = build_config(
            tranches=tranches,
            fees=(),
            trigger=dlq_trigger(),
            target_rate="0",
            min_balance="0",
        )
        hot = PoolPeriod(
            index=0,
            beginning_pool_balance=Decimal("300.00"),
            interest_collections=Decimal("0.00"),
            principal_collections=Decimal("10.01"),
            realized_losses=Decimal("0.00"),
            indicators={"dlq": Decimal("1")},
        )
        result = run_period(config, hot)
        principal = result.ending_state.principal_paid
        # 1001 cents split equally -> smallest id gets the extra cent
        self.assertEqual(
            principal,
            {"A": Decimal("3.34"),
             "B": Decimal("3.34"),
             "EQ": Decimal("3.33")},
        )
        assert_cash_closure(config, hot, result)

    def test_interest_shortfall_uses_principal_when_switch_allowed(self) -> None:
        tranches = (
            Tranche("A", 1, Decimal("100.00"), Decimal("0.10")),
        )
        order = ("A",)
        config = WaterfallConfig(
            tranches=tranches,
            fees=(),
            reserve=ReserveSpec(Decimal("0")),
            triggers=(),
            profiles=(
                PriorityProfile(
                    "normal", PrincipalMode.SEQUENTIAL, order, order
                ),
            ),
            default_profile_id="normal",
            allow_interest_principal_switch=True,
        )
        p = PoolPeriod(
            index=0,
            beginning_pool_balance=Decimal("100.00"),
            interest_collections=Decimal("3.00"),
            principal_collections=Decimal("20.00"),
            realized_losses=Decimal("0"),
            indicators={},
        )
        result = run_period(config, p)
        # coupon 10.00: 3 interest + 7 principal switched
        self.assertEqual(
            result.ending_state.interest_paid["A"], Decimal("10.00")
        )
        # remaining interest sweeps into the principal account and pays
        # principal: the 13.00 remaining after interest goes to the note
        self.assertEqual(
            result.ending_state.principal_paid["A"], Decimal("13.00")
        )
        self.assertEqual(result.ending_state.principal_cash, Decimal("0.00"))

    def test_interest_shortfall_carries_when_switch_disabled(self) -> None:
        tranches = (
            Tranche("A", 1, Decimal("100.00"), Decimal("0.10")),
        )
        order = ("A",)
        config = WaterfallConfig(
            tranches=tranches,
            fees=(),
            reserve=ReserveSpec(Decimal("0")),
            triggers=(),
            profiles=(
                PriorityProfile(
                    "normal", PrincipalMode.SEQUENTIAL, order, order
                ),
            ),
            default_profile_id="normal",
            allow_interest_principal_switch=False,
        )
        p = PoolPeriod(
            index=0,
            beginning_pool_balance=Decimal("100.00"),
            interest_collections=Decimal("3.00"),
            principal_collections=Decimal("20.00"),
            realized_losses=Decimal("0"),
            indicators={},
        )
        result = run_period(config, p)
        self.assertEqual(
            result.ending_state.interest_paid["A"], Decimal("3.00")
        )
        self.assertEqual(
            result.ending_state.interest_shortfall["A"], Decimal("7.00")
        )
        # no switch and no principal collections: principal untouched
        self.assertEqual(result.ending_state.principal_cash, Decimal("0.00"))
        interest_steps = [
            entry
            for entry in result.audit
            if entry.step == "interest"
            and entry.account == "principal"
        ]
        self.assertEqual(interest_steps, [])

        # carried shortfall is added to next period's coupon due
        p1 = PoolPeriod(
            index=1,
            beginning_pool_balance=Decimal("100.00"),
            interest_collections=Decimal("17.00"),
            principal_collections=Decimal("0"),
            realized_losses=Decimal("0"),
            indicators={},
        )
        result1 = run_period(config, p1, result.ending_state)
        # 7 carry-forward + 8 coupon on the reduced 80 balance = 15
        self.assertEqual(
            result1.ending_state.interest_paid["A"], Decimal("15.00")
        )
        self.assertNotIn("A", result1.ending_state.interest_shortfall)

    def test_reserve_release_returns_excess_as_principal(self) -> None:
        tranches = (
            Tranche("A", 1, Decimal("100.00"), Decimal("0")),
        )
        order = ("A",)
        config = WaterfallConfig(
            tranches=tranches,
            fees=(),
            reserve=ReserveSpec(
                target_rate=Decimal("0"),
                min_balance=Decimal("0"),
                releasable=True,
            ),
            triggers=(),
            profiles=(
                PriorityProfile(
                    "normal", PrincipalMode.SEQUENTIAL, order, order
                ),
            ),
            default_profile_id="normal",
        )
        p0 = PoolPeriod(
            0, Decimal("100"), Decimal("5"), Decimal("0"), Decimal("0"), {}
        )
        r0 = run_period(config, p0)
        # no trigger target 0: excess reserve should be released
        self.assertEqual(r0.ending_state.reserve_cash, Decimal("0.00"))
        self.assertEqual(
            r0.ending_state.principal_paid["A"], Decimal("5.00")
        )

    def test_reserve_not_released_when_trigger_fired(self) -> None:
        zero_tranches = (
            Tranche("A", 1, Decimal("100.00"), Decimal("0")),
            Tranche("B", 2, Decimal("100.00"), Decimal("0")),
            Tranche("EQ", 3, Decimal("100.00"), Decimal("0")),
        )
        config = build_config(
            tranches=zero_tranches,
            fees=(),
            trigger=dlq_trigger(),
            target_rate="0.05",
            min_balance="0",
        )
        p = PoolPeriod(
            0, Decimal("100"), Decimal("5"), Decimal("0"), Decimal("0"),
            {"dlq": Decimal("1")},
        )
        result = run_period(config, p)
        # interest tops the reserve to 5.00 and lockout blocks release
        self.assertEqual(result.ending_state.reserve_cash, Decimal("5.00"))
        self.assertEqual(
            result.ending_state.principal_paid.get("A", Decimal("0")),
            Decimal("0.00"),
        )

    def test_losses_penetrate_junior_first(self) -> None:
        config = build_config()
        p = period(0, interest="0", principal="0", losses="150", pool="1000")
        result = run_period(config, p)
        losses = result.ending_state.losses_allocated
        self.assertEqual(losses["EQ"], Decimal("100.00"))
        self.assertEqual(losses["B"], Decimal("50.00"))
        self.assertNotIn("A", losses)
        assert_loss_closure(config, p, result)

    def test_losses_exceeding_notes_fail_and_rollback(self) -> None:
        config = build_config()
        p = period(
            0, pool="2000", interest="0", principal="0", losses="1001"
        )
        with self.assertRaises(InsufficientFundsError):
            run_period(config, p)

    def test_failure_rolls_back_to_opening_state(self) -> None:
        config = build_config()
        p = period(
            0, pool="2000", interest="60", principal="100", losses="1001"
        )
        prior = None
        opening_balances = {
            tranche.id: tranche.initial_balance
            for tranche in config.tranches
        }
        with self.assertRaises(InsufficientFundsError):
            run_period(config, p, prior)
        # nothing persisted: a fresh run on a benign period still works and
        # sees opening balances identical to deal inception
        good = period(0, losses="0")
        result = run_period(config, good)
        self.assertEqual(
            result.beginning_state.tranche_balances, opening_balances
        )

    def test_mid_period_failure_restores_opening_snapshot(self) -> None:
        config = build_config()
        # fees/interest/principal would all execute, then losses fail:
        # every earlier mutation must still be rolled back.
        p = period(
            0,
            pool="2000",
            interest="60",
            principal="100",
            losses="1001",
        )
        before_interest = dict(
            run_period(config, period(0, losses="0"))
            .beginning_state.tranche_interest_due
        )
        with self.assertRaises(InsufficientFundsError):
            run_period(config, p)
        # the stored beginning snapshot for a fresh run is untouched and the
        # pool payload itself was never mutated by trigger evaluation
        self.assertEqual(p.indicators, {"dlq": Decimal("0.10")})
        rerun = run_period(config, period(0, losses="0"))
        self.assertEqual(
            rerun.beginning_state.tranche_interest_due, before_interest
        )
        self.assertEqual(rerun.ending_state.reserve_cash, Decimal("20.00"))

    def test_audit_trail_records_balances_before_and_after(self) -> None:
        config = build_config()
        result = run_period(config, period(0))
        self.assertTrue(result.audit)
        cash_accounts = {"interest", "principal", "reserve"}
        for entry in result.audit:
            if entry.account not in cash_accounts:
                continue
            if entry.direction == "debit":
                self.assertEqual(entry.after, entry.before - entry.amount)
            else:
                self.assertEqual(entry.after, entry.before + entry.amount)


class ValidationTest(unittest.TestCase):
    def test_duplicate_tranche_id_fails(self) -> None:
        config = build_config(
            tranches=(
                Tranche("X", 1, Decimal("1"), Decimal("0")),
                Tranche("X", 2, Decimal("1"), Decimal("0")),
            ),
        )
        with self.assertRaises(DuplicateTrancheError):
            validate_config(config)

    def test_duplicate_seniority_fails(self) -> None:
        config = build_config(
            tranches=(
                Tranche("X", 1, Decimal("1"), Decimal("0")),
                Tranche("Y", 1, Decimal("1"), Decimal("0")),
            ),
        )
        with self.assertRaises(DuplicateTrancheError):
            validate_config(config)

    def test_priority_conflict_unknown_tranche(self) -> None:
        config = build_config()
        bad = PriorityProfile(
            "bad",
            PrincipalMode.SEQUENTIAL,
            ("A", "B", "ZZ"),
            ("A", "B", "ZZ"),
        )
        object.__setattr__(config, "profiles", config.profiles + (bad,))
        with self.assertRaises(PriorityConflictError):
            validate_config(config)

    def test_trigger_missing_indicator_fails(self) -> None:
        config = build_config(trigger=dlq_trigger())
        p = PoolPeriod(
            0, Decimal("1"), Decimal("0"), Decimal("0"), Decimal("0"), {}
        )
        with self.assertRaises(TriggerConfigurationError):
            run_period(config, p)

    def test_negative_period_cash_fails(self) -> None:
        config = build_config()
        bad = PoolPeriod(
            0, Decimal("1"), Decimal("-1"), Decimal("0"), Decimal("0"), {}
        )
        with self.assertRaises(NegativeCashError):
            validate_period(bad, config)


if __name__ == "__main__":
    unittest.main()
