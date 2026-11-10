package dev.straightanswer.app.data

/** In-memory [AppPrefs] for JVM tests. */
class MemoryPrefs(var tokenValue: String? = null) : AppPrefs {
    var tier: String? = null
    var expertSkip = false
    override suspend fun token() = tokenValue
    override suspend fun saveToken(token: String) { tokenValue = token }
    override suspend fun clearToken() { tokenValue = null }
    var email: String? = null
    override suspend fun accountEmail() = email
    override suspend fun saveAccountEmail(email: String?) { this.email = email }
    override suspend fun tierKey() = tier
    override suspend fun saveTierKey(key: String) { tier = key }
    override suspend fun expertConfirmSkipped() = expertSkip
    override suspend fun setExpertConfirmSkipped(skip: Boolean) { expertSkip = skip }
}

/** Scriptable fake backend that keeps a per-token wallet, like the real one. */
class FakeApi : AnswerApi {
    val calls = mutableListOf<String>()
    val wallets = mutableMapOf<String, Double>()
    val validTokens = mutableSetOf<String>()
    private var nextToken = 1
    private val pendingTopUps = mutableMapOf<String, Pair<String, Int>>()
    var tiers = listOf(ModelTier("fast", "Fast", 0.02), ModelTier("smart", "Smart", 0.2), ModelTier("expert", "Expert", 1.6))
    /** Cost charged per chat call. */
    var chatCost = 0.02
    var chatRequests = mutableListOf<ChatRequest>()
    var chatError: Exception? = null
    /** The next replies come back cut off at the length limit (POST /v1/chat "truncated": true). */
    var truncateReplies = false
    /** When set, chat() waits for it, so tests can look at the "meter running" state. */
    var chatGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private fun check(token: String) { if (token !in validTokens) throw ApiException(401, "invalid token") }

    override suspend fun register(): RegisterResponse {
        calls += "register"
        return RegisterResponse(newToken())
    }

    override suspend fun wallet(token: String): WalletResponse {
        calls += "wallet"; check(token)
        return WalletResponse(wallets.getValue(token))
    }

    override suspend fun createTopUp(token: String, amountInr: Int): TopUpResponse {
        calls += "topup:$amountInr"; check(token)
        val id = "tu${pendingTopUps.size + 1}"
        pendingTopUps[id] = token to amountInr
        return TopUpResponse("https://pay.example/$id", id)
    }

    override suspend fun confirmTopUp(token: String?, topupId: String): WalletResponse {
        calls += "confirm:$topupId"
        val (owner, amount) = pendingTopUps.remove(topupId) ?: throw ApiException(404, "unknown top-up")
        wallets[owner] = wallets.getValue(owner) + amount
        return WalletResponse(wallets.getValue(owner))
    }

    /** When set, GET /v1/models fails with it (e.g. backend cold start / offline). */
    var modelsError: Exception? = null

    override suspend fun models(token: String?): ModelsResponse {
        calls += "models"
        modelsError?.let { throw it }
        return ModelsResponse(tiers)
    }

    /** email -> (password, token). */
    val accounts = mutableMapOf<String, Pair<String, String>>()
    val resetRequests = mutableListOf<String>()
    val history = mutableMapOf<String, MutableList<HistoryItem>>()
    var historyError: Exception? = null
    var deleteError: Exception? = null

    private fun newToken(): String = "tok${nextToken++}".also { validTokens += it; wallets[it] = 0.0 }

    override suspend fun signup(email: String, password: String): RegisterResponse {
        calls += "signup"
        if (email in accounts) throw ApiException(409, "Email already registered.")
        val t = newToken()
        accounts[email] = password to t
        return RegisterResponse(t)
    }

    override suspend fun login(email: String, password: String): RegisterResponse {
        calls += "login"
        val (pw, t) = accounts[email] ?: throw ApiException(401, "Invalid email or password.")
        if (pw != password) throw ApiException(401, "Invalid email or password.")
        validTokens += t
        return RegisterResponse(t)
    }

    override suspend fun forgotPassword(email: String) {
        calls += "forgot"
        resetRequests += email
    }

    override suspend fun history(token: String): HistoryPage {
        calls += "history"; check(token)
        historyError?.let { throw it }
        return HistoryPage(history[token].orEmpty().toList())
    }

    override suspend fun deleteHistory(token: String, conversationId: String) {
        calls += "deleteHistory:$conversationId"; check(token)
        deleteError?.let { throw it }
        val list = history[token]
        if (list == null || list.none { it.conversationId == conversationId }) throw ApiException(404, "not found")
        list.removeAll { it.conversationId == conversationId }
    }

    override suspend fun deleteAccount(token: String) {
        calls += "deleteAccount"; check(token)
        deleteError?.let { throw it }
        validTokens -= token
        wallets.remove(token)
        history.remove(token)
        accounts.entries.removeAll { it.value.second == token }
    }

    override suspend fun chat(token: String, request: ChatRequest): ChatResponse {
        calls += "chat"; check(token)
        chatRequests += request
        chatGate?.await()
        chatError?.let { throw it }
        val bal = wallets.getValue(token)
        if (bal < chatCost) throw ApiException(402, "insufficient balance")
        wallets[token] = bal - chatCost
        return ChatResponse("answer to: ${request.message}", request.conversationId ?: "c1", chatCost, wallets.getValue(token), truncateReplies)
    }
}
