package dev.straightanswer.app.data

import dev.straightanswer.app.core.TierPrice
import dev.straightanswer.app.core.Tiers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Who the app is talking to the backend as. */
sealed interface Session {
    /** Not read from storage yet (first frame only). */
    data object Unknown : Session
    /** No token. [expired] = the server rejected an account's token (so the login screen can say why). */
    data class SignedOut(val expired: Boolean = false) : Session
    /** Anonymous device token from POST /v1/auth/register ("use without an account"). */
    data object Guest : Session
    data class Account(val email: String) : Session
}

/**
 * App-wide session over [AnswerApi]: owns the token (guest or account), the latest known wallet
 * balance and tier prices, which every screen observes so chat, wallet and profile stay in sync.
 */
class Backend(private val api: AnswerApi, private val prefs: AppPrefs) {
    private val registerLock = Mutex()
    private val _balance = MutableStateFlow<Double?>(null)
    private val _tiers = MutableStateFlow<List<TierPrice>>(emptyList())
    private val _session = MutableStateFlow<Session>(Session.Unknown)

    /** Last balance the server told us, or null before the first fetch. */
    val balance: StateFlow<Double?> = _balance.asStateFlow()
    val tiers: StateFlow<List<TierPrice>> = _tiers.asStateFlow()
    val session: StateFlow<Session> = _session.asStateFlow()

    suspend fun isRegistered(): Boolean = prefs.token() != null

    /** Reads the stored session (called once at start-up). */
    suspend fun restoreSession(): Session {
        val s = when {
            prefs.token() == null -> Session.SignedOut()
            else -> prefs.accountEmail()?.let { Session.Account(it) } ?: Session.Guest
        }
        _session.value = s
        return s
    }

    /**
     * Guest path ("use without an account"): registers this device once and stores the token; later
     * calls return the stored token (guest or account).
     */
    suspend fun ensureRegistered(): String = registerLock.withLock {
        prefs.token() ?: api.register().token.also {
            require(it.isNotBlank()) { "server returned an empty token" }
            prefs.saveToken(it)
            prefs.saveAccountEmail(null)
            _session.value = Session.Guest
        }
    }

    suspend fun signup(email: String, password: String) = startAccount(email) { api.signup(email, password) }

    suspend fun login(email: String, password: String) = startAccount(email) { api.login(email, password) }

    /** Always "succeeds" server-side whether or not the email has an account. */
    suspend fun forgotPassword(email: String) = api.forgotPassword(email)

    private suspend fun startAccount(email: String, call: suspend () -> RegisterResponse) {
        val token = call().token
        require(token.isNotBlank()) { "server returned an empty token" }
        registerLock.withLock {
            prefs.saveToken(token)
            prefs.saveAccountEmail(email)
        }
        // A different wallet from whatever this phone used before: forget the old balance.
        _balance.value = null
        _session.value = Session.Account(email)
        runCatching { refreshBalance() }
    }

    /** Forgets the token on this phone. The account (and its balance) stays on the server. */
    suspend fun logout() = signOutLocally(expired = false)

    private suspend fun signOutLocally(expired: Boolean) {
        registerLock.withLock {
            prefs.clearToken()
            prefs.saveAccountEmail(null)
        }
        _balance.value = null
        _session.value = Session.SignedOut(expired)
    }

    /**
     * Runs [block] with the token.
     *
     * On 401 for a **guest** (token unknown to the server, e.g. a dev backend whose DB was reset), the
     * stale token is dropped, the device re-registers and the call is retried once, as before accounts.
     * On 401 for an **account**, the app must not quietly swap in a fresh ₹0 guest wallet: it signs out
     * so the user is asked to log in again. After an explicit sign-out nothing re-registers silently.
     */
    private suspend fun <T> authed(block: suspend (String) -> T): T {
        if (_session.value is Session.SignedOut && prefs.token() == null) throw ApiException(401, "Please log in again.")
        val token = ensureRegistered()
        return try {
            block(token)
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            if (prefs.accountEmail() != null) {
                if (prefs.token() == token) signOutLocally(expired = true)
                throw e
            }
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

    suspend fun history(): List<HistoryItem> = authed { api.history(it) }.items

    /** Deletes one conversation from history. 404 means it is already gone, which is what the user wanted. */
    suspend fun deleteHistory(conversationId: String) {
        try {
            authed { api.deleteHistory(it, conversationId) }
        } catch (e: ApiException) {
            if (e.code != 404) throw e
        }
    }

    /** Deletes the account (or guest wallet) on the server, then signs out here. Irreversible. */
    suspend fun deleteAccount() {
        authed { api.deleteAccount(it) }
        signOutLocally(expired = false)
    }
}
