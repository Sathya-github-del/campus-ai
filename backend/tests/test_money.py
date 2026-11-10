"""Wallet balance math: paise parsing, USD->INR charge with margin, sub-paisa carry, never negative."""
from decimal import Decimal

import pytest

from app.money import (UNITS_PER_PAISA, UNITS_PER_RUPEE, AmountError, apply_charge, charge_multiplier,
                       inr_to_paise, units_to_inr, usd_to_units)


def test_inr_to_paise_parses_exactly():
    assert inr_to_paise(20) == 2000
    assert inr_to_paise(Decimal("20.5")) == 2050
    with pytest.raises(AmountError):
        inr_to_paise(0.1 + 0.2)  # float noise is rejected, never rounded silently
    with pytest.raises(AmountError):
        inr_to_paise(Decimal("20.001"))
    with pytest.raises(AmountError):
        inr_to_paise("abc")
    with pytest.raises(AmountError):
        inr_to_paise(Decimal("Infinity"))


def test_charge_applies_rate_and_margin():
    # $0.001 at Rs93/$ with a 5% margin => 0.093 / 0.95 = Rs 0.097894736... => ceil to 1 unit
    units = usd_to_units(Decimal("0.001"), Decimal("93"), Decimal("5"))
    assert units == 97895
    assert units_to_inr(units) == 0.0979
    # margin semantics: real cost is exactly (100 - MARGIN_PCT)% of what the user pays
    charged_inr = Decimal(units) / UNITS_PER_RUPEE
    assert abs(Decimal("0.093") / charged_inr - Decimal("0.95")) < Decimal("0.00001")
    assert charge_multiplier(Decimal("93"), Decimal("0")) == Decimal("93")
    assert usd_to_units(Decimal(0), Decimal("93"), Decimal("5")) == 0


def test_rate_change_is_proportional():
    a = usd_to_units(Decimal("0.01"), Decimal("93"), Decimal("5"))
    b = usd_to_units(Decimal("0.01"), Decimal("86"), Decimal("5"))
    assert abs(a / b - 93 / 86) < 1e-6


def test_apply_charge_takes_whole_paise_and_keeps_overpayment_as_credit():
    # balance Rs1.00, cost 2.25 paise -> take 3 paise, keep 0.75 paise credit
    bal, credit, taken, short = apply_charge(100, 0, 22_500)
    assert (bal, credit, taken, short) == (97, 7_500, 3, 0)
    # next 0.5-paisa message is paid entirely from credit
    assert apply_charge(bal, credit, 5_000) == (97, 2_500, 0, 0)


def test_many_small_charges_sum_to_exact_cost_within_one_paisa():
    cost = 22_500  # 2.25 paise per Fast message
    bal, credit = 2000, 0  # Rs 20
    for _ in range(400):
        bal, credit, _, short = apply_charge(bal, credit, cost)
        assert short == 0
    spent_units = 2000 * UNITS_PER_PAISA - (bal * UNITS_PER_PAISA + credit)
    assert spent_units == 400 * cost  # exact: no rounding drift at all
    assert bal == 1100


def test_apply_charge_never_goes_negative():
    bal, credit, taken, short = apply_charge(5, 0, 80_000)  # 8 paise cost, 5 paise balance
    assert bal == 0 and credit == 0 and taken == 5
    assert short == 30_000
    with pytest.raises(ValueError):
        apply_charge(-1, 0, 1)


def test_default_margin_recovers_fee_stack_so_full_spend_breaks_even():
    """BLUEPRINT §7: Rs20 top-up -> Rs16.47 of OpenRouter credit. With the default MARGIN_PCT, spending
    the whole wallet consumes exactly that credit (no per-message markup, BLUEPRINT §12)."""
    from app.config import Settings

    s = Settings()
    assert s.margin_pct == Decimal("17.65") == s.platform_fee_pct
    usd_credit = Decimal("16.47") / s.usd_inr_rate  # real credit a Rs20 top-up buys
    charged_inr = Decimal(usd_to_units(usd_credit, s.usd_inr_rate, s.margin_pct)) / UNITS_PER_RUPEE
    assert abs(charged_inr - Decimal("20")) < Decimal("0.0001")


def test_env_default_margin(monkeypatch):
    from app.config import Settings

    monkeypatch.delenv("MARGIN_PCT", raising=False)
    assert Settings.from_env(load_dotenv_file=False).margin_pct == Decimal("17.65")
