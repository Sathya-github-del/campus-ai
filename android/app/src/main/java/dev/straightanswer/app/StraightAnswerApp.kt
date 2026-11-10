package dev.straightanswer.app

import android.app.Application
import dev.straightanswer.app.data.ApiClient
import dev.straightanswer.app.data.Backend
import dev.straightanswer.app.data.SessionStore
import dev.straightanswer.app.payments.DevMockPaymentGateway
import dev.straightanswer.app.payments.PaymentGateway
import dev.straightanswer.app.payments.TopUpFlow
import dev.straightanswer.app.payments.UnavailablePaymentGateway

/** Manual dependency container (the app is small; no DI framework). */
class StraightAnswerApp : Application() {
    lateinit var store: SessionStore private set
    lateinit var backend: Backend private set
    lateinit var topUpFlow: TopUpFlow private set

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        backend = Backend(ApiClient(BuildConfig.API_BASE_URL), store)
        val gateway: PaymentGateway =
            if (BuildConfig.MOCK_PAYMENTS) DevMockPaymentGateway(backend) else UnavailablePaymentGateway()
        topUpFlow = TopUpFlow(backend, gateway)
    }
}
