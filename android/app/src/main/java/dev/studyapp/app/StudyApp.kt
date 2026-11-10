package dev.studyapp.app

import android.app.Application
import dev.studyapp.app.data.ApiClient
import dev.studyapp.app.data.Backend
import dev.studyapp.app.data.SessionStore
import dev.studyapp.app.payments.DevMockPaymentGateway
import dev.studyapp.app.payments.PaymentGateway
import dev.studyapp.app.payments.TopUpFlow
import dev.studyapp.app.payments.UnavailablePaymentGateway

/** Manual dependency container (the app is small; no DI framework). */
class StudyApp : Application() {
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
