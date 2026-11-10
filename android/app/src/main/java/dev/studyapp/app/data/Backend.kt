package dev.studyapp.app.data

import dev.studyapp.app.core.TierPrice
import dev.studyapp.app.core.Tiers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-wide session over [StudyApi]: owns the device token (registering on first use) and the
 * latest known wallet balance, which every screen observes so chat and wallet stay in sync.
 */
class Backend(private val api: StudyApi, private val prefs: AppPrefs) {
    private val registerLock = Mutex()
    private val _balance = MutableStateFlow<Double?>(null)
    private val _tiers = MutableStateFlow<List<TierPrice>>(emptyList())

    /** Last balance the server told us, or null before the first fetch. */
    val balance: StateFlow<Double?> = _balance.asStateFlow()
    val tiers: StateFlow<List<TierPrice>> = _tiers.asStateFlow()

    suspend fun isRegistered(): Boolean = prefs.token() != null

    /** Registers this device once and stores the token; later calls return the stored token. */
    suspend fun ensureRegistered(): String = registerLock.withLock {
        prefs.token() ?: api.register().token.also {
            require(it.isNotBlank()) { "server returned an empty token" }
            prefs.saveToken(it)
        }
    }

    /**
     * Runs [block] with the token. On 401 (token unknown to the server, e.g. a dev backend whose DB
     * was reset) the stale token is dropped, the device re-registers, and the call is retried once.
     */
    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val token = ensureRegistered()
        return try {
            block(token)
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            registerLock.withLock { if (prefs.token() == token) prefs.clearToken() }
            block(ensureRegistered())
        }
    }

    suspend fun refreshBalance(): Double = authed { api.wallet(it) }.balanceInr.also { _balance.value = it }

    suspend fun refreshTiers(): List<TierPrice> {
        val r = api.models(prefs.token())
        return Tiers.fromServer(r.tiers.map { it.tier to it.estCostPerMessageInr }).also { _tiers.value = it }
    }

    suspend fun chat(request: ChatRequest): ChatResponse =
        authed { api.chat(it, request) }.also { _balance.value = it.walletBalanceInr }

    suspend fun createTopUp(amountInr: Int): TopUpResponse = authed { api.createTopUp(it, amountInr) }

    /** Dev-mode mock payment confirmation (the real flow is confirmed server-side by the payment webhook). */
    suspend fun confirmTopUp(topupId: String): Double =
        authed { api.confirmTopUp(it, topupId) }.balanceInr.also { _balance.value = it }
}
