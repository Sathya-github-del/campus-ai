package dev.straightanswer.app.payments

import dev.straightanswer.app.data.Backend
import dev.straightanswer.app.data.FakeApi
import dev.straightanswer.app.data.MemoryPrefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopUpFlowTest {
    private val api = FakeApi()
    private val backend = Backend(api, MemoryPrefs())

    @Test
    fun devMockCreatesOrderThenConfirmsAndBalanceIncreases() = runTest {
        val flow = TopUpFlow(backend, DevMockPaymentGateway(backend))
        assertTrue(flow.isTestMode)
        val r = flow.topUp(20)
        assertEquals(TopUpFlow.Result.Done(20.0), r)
        assertEquals(20.0, backend.balance.value!!, 0.0)
        assertEquals(listOf("register", "topup:20", "confirm:tu1"), api.calls)
        assertEquals(TopUpFlow.Result.Done(70.0), flow.topUp(50))
    }

    @Test
    fun fullLoopTopUpThenChat() = runTest {
        val flow = TopUpFlow(backend, DevMockPaymentGateway(backend))
        flow.topUp(20)
        val reply = backend.chat(dev.straightanswer.app.data.ChatRequest("fast", "what is 2+2", null, null))
        assertEquals(19.98, reply.walletBalanceInr, 1e-9)
        assertEquals(19.98, backend.balance.value!!, 1e-9)
    }

    @Test
    fun gatewayWithoutBalanceRefetchesWallet() = runTest {
        val webhookStyle = object : PaymentGateway {
            override val isTestMode = false
            override suspend fun pay(order: TopUpOrder): PaymentOutcome {
                api.confirmTopUp(null, order.topupId)   // stands in for the server-side webhook
                return PaymentOutcome.Success(null)
            }
        }
        assertEquals(TopUpFlow.Result.Done(100.0), TopUpFlow(backend, webhookStyle).topUp(100))
        assertEquals("wallet", api.calls.last())
    }

    @Test
    fun unavailableGatewayFailsHonestly() = runTest {
        val r = TopUpFlow(backend, UnavailablePaymentGateway()).topUp(20)
        assertTrue(r is TopUpFlow.Result.Failed)
        assertEquals(null, backend.balance.value)
    }
}
