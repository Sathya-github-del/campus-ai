package dev.straightanswer.app.payments

import dev.straightanswer.app.data.Backend

/** A top-up order created by the backend (POST /v1/wallet/topup). */
data class TopUpOrder(val topupId: String, val checkoutUrl: String, val amountInr: Int)

sealed interface PaymentOutcome {
    /** Paid. [newBalanceInr] when the gateway already knows it; null means "re-fetch the wallet". */
    data class Success(val newBalanceInr: Double?) : PaymentOutcome
    data object Cancelled : PaymentOutcome
    data class Failed(val message: String) : PaymentOutcome
}

/**
 * Takes the student through paying for an order. This is the seam where Razorpay drops in:
 * a `RazorpayPaymentGateway` opens Razorpay Checkout (UPI intent) for [TopUpOrder.checkoutUrl] /
 * order id, waits for the result, and returns [PaymentOutcome.Success] with a null balance; the
 * backend credits the wallet from Razorpay's webhook and [TopUpFlow] re-fetches it.
 */
interface PaymentGateway {
    /** True when no real money moves (shown to the user as "Test mode"). */
    val isTestMode: Boolean
    suspend fun pay(order: TopUpOrder): PaymentOutcome
}

/** Dev builds: no checkout UI; immediately calls the backend's mock confirm endpoint to simulate a successful UPI payment. */
class DevMockPaymentGateway(private val backend: Backend) : PaymentGateway {
    override val isTestMode = true
    override suspend fun pay(order: TopUpOrder): PaymentOutcome =
        PaymentOutcome.Success(backend.confirmTopUp(order.topupId))
}

/** Release builds until Razorpay is integrated: top-ups are honestly unavailable rather than faked. */
class UnavailablePaymentGateway : PaymentGateway {
    override val isTestMode = false
    override suspend fun pay(order: TopUpOrder): PaymentOutcome =
        PaymentOutcome.Failed("UPI top-up isn't available in this version yet.")
}

/** create order → pay → updated balance. */
class TopUpFlow(private val backend: Backend, private val gateway: PaymentGateway) {
    val isTestMode: Boolean get() = gateway.isTestMode

    sealed interface Result {
        data class Done(val balanceInr: Double) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    /** Network/API exceptions propagate to the caller (mapped to friendly text there). */
    suspend fun topUp(amountInr: Int): Result {
        val r = backend.createTopUp(amountInr)
        val order = TopUpOrder(r.topupId, r.checkoutUrl, amountInr)
        return when (val o = gateway.pay(order)) {
            is PaymentOutcome.Success -> Result.Done(o.newBalanceInr ?: backend.refreshBalance())
            PaymentOutcome.Cancelled -> Result.Cancelled
            is PaymentOutcome.Failed -> Result.Failed(o.message)
        }
    }
}
