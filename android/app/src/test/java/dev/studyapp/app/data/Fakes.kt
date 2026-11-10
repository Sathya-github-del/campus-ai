package dev.studyapp.app.data

/** In-memory [AppPrefs] for JVM tests. */
class MemoryPrefs(var tokenValue: String? = null) : AppPrefs {
    var tier: String? = null
    var expertSkip = false
    override suspend fun token() = tokenValue
    override suspend fun saveToken(token: String) { tokenValue = token }
    override suspend fun clearToken() { tokenValue = null }
    override suspend fun tierKey() = tier
    override suspend fun saveTierKey(key: String) { tier = key }
    override suspend fun expertConfirmSkipped() = expertSkip
    override suspend fun setExpertConfirmSkipped(skip: Boolean) { expertSkip = skip }
}

/** Scriptable fake backend that keeps a per-token wallet, like the real one. */
class FakeApi : StudyApi {
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
        val t = "tok${nextToken++}"
        validTokens += t
        wallets[t] = 0.0
        return RegisterResponse(t)
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

    override suspend fun models(token: String?): ModelsResponse {
        calls += "models"
        return ModelsResponse(tiers)
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
