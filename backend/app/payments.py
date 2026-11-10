"""Payment providers. The API only talks to the PaymentProvider interface; swapping the dev mock for
Razorpay means implementing RazorpayPaymentProvider and setting PAYMENT_PROVIDER=razorpay.

Flow (same for every provider):
  1. POST /v1/wallet/topup        -> wallet.create_topup() (pending) -> provider.create_checkout()
  2. student pays on checkout_url (UPI)
  3. provider calls POST /v1/payments/webhook -> provider.parse_webhook() verifies it -> PaymentEvent
  4. wallet.apply_payment() credits the wallet exactly once (idempotent on retries)
In dev, POST /v1/wallet/topup/confirm stands in for step 2+3 via MockPaymentProvider.simulate_success().
"""
from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Mapping

from .config import Settings


@dataclass(frozen=True)
class Checkout:
    checkout_url: str
    provider_ref: str | None = None  # e.g. Razorpay order id


@dataclass(frozen=True)
class PaymentEvent:
    topup_id: str
    amount_paise: int
    provider_ref: str | None
    paid: bool


class WebhookError(ValueError):
    """Webhook could not be authenticated or parsed. Respond 400 and do NOT credit anything."""


class PaymentProvider(ABC):
    name: str = ""

    @abstractmethod
    def create_checkout(self, topup_id: str, amount_paise: int, user_id: str) -> Checkout:
        """Create the provider-side order and return where the client should send the student."""

    @abstractmethod
    def parse_webhook(self, headers: Mapping[str, str], body: bytes) -> PaymentEvent:
        """Authenticate (signature!) and parse a provider callback. Raise WebhookError if invalid."""


class MockPaymentProvider(PaymentProvider):
    """Dev only: every checkout 'succeeds' when confirmed. Refused at startup unless DEV_MODE=true."""

    name = "mock"

    def __init__(self, public_base_url: str):
        self.base = public_base_url.rstrip("/")

    def create_checkout(self, topup_id: str, amount_paise: int, user_id: str) -> Checkout:
        return Checkout(f"{self.base}/v1/dev/checkout/{topup_id}", provider_ref=f"mock_{topup_id}")

    def parse_webhook(self, headers: Mapping[str, str], body: bytes) -> PaymentEvent:
        # No signature in dev. A real provider MUST verify one here.
        try:
            d = json.loads(body)
            return PaymentEvent(str(d["topup_id"]), int(d["amount_paise"]), f"mock_{d['topup_id']}", True)
        except (ValueError, KeyError, TypeError) as e:
            raise WebhookError("bad mock webhook body") from e

    def simulate_success(self, topup: dict) -> PaymentEvent:
        return PaymentEvent(topup["id"], topup["amount_paise"], f"mock_{topup['id']}", True)


class RazorpayPaymentProvider(PaymentProvider):
    """NOT IMPLEMENTED YET (no Razorpay keys exist). What it needs to do:

    create_checkout:
      - POST https://api.razorpay.com/v1/orders {amount: amount_paise, currency: "INR",
        receipt: topup_id, notes: {topup_id}} with basic auth RAZORPAY_KEY_ID:RAZORPAY_KEY_SECRET
      - return Checkout(checkout_url=<hosted checkout / payment link for UPI intent>, provider_ref=order.id)
    parse_webhook:
      - verify X-Razorpay-Signature == hex(HMAC_SHA256(RAZORPAY_WEBHOOK_SECRET, raw body)) using
        hmac.compare_digest, BEFORE parsing anything; raise WebhookError on mismatch
      - accept `order.paid` (or `payment.captured`), map payload.order.entity.receipt -> topup_id,
        amount -> amount_paise, payment id -> provider_ref
      - dedupe on the x-razorpay-event-id header (wallet.apply_payment is idempotent per top-up already)
    Also: reconcile pending top-ups via GET /v1/orders/{id}/payments in case a webhook is lost.
    """

    name = "razorpay"

    def __init__(self, settings: Settings):
        raise NotImplementedError(
            "Razorpay is not wired up yet: implement RazorpayPaymentProvider in app/payments.py "
            "(see its docstring) or run with PAYMENT_PROVIDER=mock DEV_MODE=true")

    def create_checkout(self, topup_id: str, amount_paise: int, user_id: str) -> Checkout:  # pragma: no cover
        raise NotImplementedError

    def parse_webhook(self, headers: Mapping[str, str], body: bytes) -> PaymentEvent:  # pragma: no cover
        raise NotImplementedError


def build_provider(settings: Settings) -> PaymentProvider:
    if settings.payment_provider == "razorpay":
        return RazorpayPaymentProvider(settings)
    return MockPaymentProvider(settings.public_base_url)
