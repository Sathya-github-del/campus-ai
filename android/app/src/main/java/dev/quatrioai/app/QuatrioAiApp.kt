package dev.quatrioai.app

import android.app.Application
import dev.quatrioai.app.data.ApiClient
import dev.quatrioai.app.data.Backend
import dev.quatrioai.app.data.DataStoreHistoryTagsStore
import dev.quatrioai.app.data.HistoryTagsStore
import dev.quatrioai.app.data.SessionStore
import dev.quatrioai.app.payments.DevMockPaymentGateway
import dev.quatrioai.app.payments.PaymentGateway
import dev.quatrioai.app.payments.TopUpFlow
import dev.quatrioai.app.payments.UnavailablePaymentGateway

/** Manual dependency container (the app is small; no DI framework). */
class QuatrioAiApp : Application() {
    lateinit var store: SessionStore private set
    lateinit var backend: Backend private set
    lateinit var topUpFlow: TopUpFlow private set
    lateinit var historyTags: HistoryTagsStore private set

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        backend = Backend(ApiClient(BuildConfig.API_BASE_URL), store)
        val gateway: PaymentGateway =
            if (BuildConfig.MOCK_PAYMENTS) DevMockPaymentGateway(backend) else UnavailablePaymentGateway()
        topUpFlow = TopUpFlow(backend, gateway)
        historyTags = DataStoreHistoryTagsStore(this)
    }
}
