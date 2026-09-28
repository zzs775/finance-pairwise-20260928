"""Balance ledger with audit hooks and snapshot/restore support.

Every mutation emits one :class:`AuditEntry` capturing the account, the
before/after balances and the step that caused it. ``snapshot`` / ``restore``
let a failed period roll every account and tranche balance back to the
period's opening state while discarding its partial audit trail.
"""

from copy import deepcopy
from decimal import Decimal

from .errors import NegativeCashError
from .models import Account, AuditEntry, PeriodState
from .money import checked_add, checked_sub, to_money

_CASH_FIELDS = {
    Account.INTEREST: "interest_cash",
    Account.PRINCIPAL: "principal_cash",
    Account.RESERVE: "reserve_cash",
}


class Ledger:
    def __init__(self, state: PeriodState) -> None:
        self.state = state
        self.audit: list[AuditEntry] = []

    def balance(self, account: Account) -> Decimal:
        return getattr(self.state, _CASH_FIELDS[account])

    def tranche_balance(self, tranche_id: str) -> Decimal:
        return self.state.tranche_balances[tranche_id]

    def _record(
        self,
        account: str,
        before: Decimal,
        amount: Decimal,
        after: Decimal,
        step: str,
        action: str,
        memo: str,
        direction: str = "debit",
    ) -> AuditEntry:
        entry = AuditEntry(
            period_index=self.state.period_index,
            step=step,
            action=action,
            account=account,
            before=before,
            amount=amount,
            after=after,
            memo=memo,
            direction=direction,
        )
        self.audit.append(entry)
        return entry

    def debit(
        self,
        account: Account,
        amount: Decimal,
        step: str,
        action: str,
        memo: str = "",
    ) -> AuditEntry:
        """Take cash out of an account (debit its balance)."""
        amount = to_money(amount)
        before = self.balance(account)
        after = checked_sub(before, amount)
        setattr(self.state, _CASH_FIELDS[account], after)
        return self._record(
            account.value, before, amount, after, step, action, memo, "debit"
        )

    def credit(
        self,
        account: Account,
        amount: Decimal,
        step: str,
        action: str,
        memo: str = "",
    ) -> AuditEntry:
        """Put cash into an account."""
        amount = to_money(amount)
        before = self.balance(account)
        after = checked_add(before, amount)
        setattr(self.state, _CASH_FIELDS[account], after)
        return self._record(
            account.value, before, amount, after, step, action, memo, "credit"
        )

    def transfer(
        self,
        source: Account,
        target: Account,
        amount: Decimal,
        step: str,
        action: str,
        memo: str = "",
    ) -> list[AuditEntry]:
        """Move cash between explicit accounts; both legs are audited."""
        amount = to_money(amount)
        if source == target:
            raise NegativeCashError("cannot transfer within the same account")
        entries = [
            self.debit(source, amount, step, f"{action}:debit", memo),
            self.credit(target, amount, step, f"{action}:credit", memo),
        ]
        return entries

    def pay_tranche(
        self,
        tranche_id: str,
        interest_amount: Decimal = Decimal("0.00"),
        principal_amount: Decimal = Decimal("0.00"),
        step: str = "",
        memo: str = "",
    ) -> list[AuditEntry]:
        """Record a security payment and reduce the tranche balance."""
        entries: list[AuditEntry] = []
        interest_amount = to_money(interest_amount)
        principal_amount = to_money(principal_amount)
        before = self.state.tranche_balances[tranche_id]
        after = before
        if interest_amount:
            self.state.interest_paid[tranche_id] = checked_add(
                self.state.interest_paid.get(tranche_id, Decimal("0.00")),
                interest_amount,
            )
        if principal_amount:
            after = checked_sub(before, principal_amount)
            self.state.tranche_balances[tranche_id] = after
            self.state.principal_paid[tranche_id] = checked_add(
                self.state.principal_paid.get(tranche_id, Decimal("0.00")),
                principal_amount,
            )
        entries.append(
            self._record(
                f"tranche:{tranche_id}",
                before,
                interest_amount + principal_amount,
                after,
                step,
                "tranche_pay",
                memo,
            )
        )
        return entries

    def absorb_loss(
        self,
        tranche_id: str,
        amount: Decimal,
        step: str,
        memo: str = "",
    ) -> AuditEntry:
        """Write a tranche balance down by an allocated loss."""
        amount = to_money(amount)
        before = self.state.tranche_balances[tranche_id]
        after = checked_sub(before, amount)
        self.state.tranche_balances[tranche_id] = after
        self.state.losses_allocated[tranche_id] = checked_add(
            self.state.losses_allocated.get(tranche_id, Decimal("0.00")),
            amount,
        )
        return self._record(
            f"tranche:{tranche_id}",
            before,
            amount,
            after,
            step,
            "loss_write_down",
            memo,
        )

    def pay_fee(
        self,
        fee_id: str,
        amount: Decimal,
        step: str,
        memo: str = "",
    ) -> AuditEntry:
        amount = to_money(amount)
        before = self.state.fees_paid.get(fee_id, Decimal("0.00"))
        after = checked_add(before, amount)
        self.state.fees_paid[fee_id] = after
        return self._record(
            f"fee:{fee_id}", before, amount, after, step, "fee_pay", memo
        )

    def accrue_interest_due(self, tranche_id: str, amount: Decimal) -> None:
        self.state.tranche_interest_due[tranche_id] = checked_add(
            self.state.tranche_interest_due.get(tranche_id, Decimal("0.00")),
            to_money(amount),
        )

    def snapshot(self) -> PeriodState:
        """Deep-copy every balance and per-period accumulator."""
        return deepcopy(self.state)

    def restore(self, snapshot: PeriodState) -> None:
        """Roll the ledger back, discarding partial audit entries."""
        self.state = deepcopy(snapshot)
        self.audit = []
