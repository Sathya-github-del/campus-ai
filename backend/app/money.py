"""Money math. Wallets are integer paise; metering is integer "units" (1/10,000 paisa).

Why two integers: a Fast-tier message costs ~2 paise, so rounding every message to whole paise would
distort the price by up to +-25%. Each wallet therefore carries a sub-paisa *prepaid credit*
(0..9,999 units): a charge takes whole paise from the balance (rounded up) and the overpayment is kept
as credit for the next message. The user is never charged more than the exact metered cost plus less
than one paisa, and the balance can never go below zero. No floats anywhere in the ledger.
"""
from __future__ import annotations

from decimal import ROUND_CEILING, ROUND_FLOOR, Decimal, InvalidOperation

UNITS_PER_PAISA = 10_000
UNITS_PER_RUPEE = 100 * UNITS_PER_PAISA


class AmountError(ValueError):
    pass


def inr_to_paise(amount: Decimal | int | float | str) -> int:
    """Parse a client-supplied rupee amount (at most 2 decimal places) into paise."""
    try:
        d = Decimal(str(amount))
    except InvalidOperation as e:
        raise AmountError("amount must be a number") from e
    if not d.is_finite():
        raise AmountError("amount must be a finite number")
    paise = d * 100
    if paise != paise.to_integral_value():
        raise AmountError("amount can have at most 2 decimal places")
    return int(paise)


def paise_to_inr(paise: int) -> float:
    return float(Decimal(paise) / 100)


def units_to_inr(units: int) -> float:
    """Exact charge for display, 4 decimal places (Fast-tier messages are fractions of a rupee)."""
    return float((Decimal(units) / UNITS_PER_RUPEE).quantize(Decimal("0.0001"), rounding=ROUND_CEILING))


def charge_multiplier(usd_inr_rate: Decimal, margin_pct: Decimal) -> Decimal:
    """Rupees charged to the user per US dollar of real model cost."""
    return usd_inr_rate / (1 - margin_pct / 100)


def usd_to_units(usd: Decimal, usd_inr_rate: Decimal, margin_pct: Decimal) -> int:
    """User-visible charge, in units, for a real OpenRouter cost in USD (rounded up to 1 unit)."""
    if usd <= 0:
        return 0
    inr = usd * charge_multiplier(usd_inr_rate, margin_pct)
    return int((inr * UNITS_PER_RUPEE).to_integral_value(rounding=ROUND_CEILING))


def units_to_paise_ceil(units: int) -> int:
    return -(-units // UNITS_PER_PAISA)


def apply_charge(balance_paise: int, credit_units: int, cost_units: int) -> tuple[int, int, int, int]:
    """Debit `cost_units` from a wallet.

    Returns (new_balance_paise, new_credit_units, paise_taken, shortfall_units). `shortfall_units` is
    non-zero only if the wallet could not cover the cost (should not happen: chat holds a worst-case
    reservation first); the platform absorbs it so the balance never goes negative.
    """
    if cost_units < 0 or balance_paise < 0 or not (0 <= credit_units < UNITS_PER_PAISA):
        raise ValueError("invalid wallet state or cost")
    if cost_units <= credit_units:
        return balance_paise, credit_units - cost_units, 0, 0
    need = cost_units - credit_units
    paise = units_to_paise_ceil(need)
    if paise > balance_paise:
        shortfall = need - balance_paise * UNITS_PER_PAISA
        return 0, 0, balance_paise, shortfall
    return balance_paise - paise, paise * UNITS_PER_PAISA - need, paise, 0


def floor_div(a: Decimal, b: Decimal) -> int:
    return int((a / b).to_integral_value(rounding=ROUND_FLOOR))
