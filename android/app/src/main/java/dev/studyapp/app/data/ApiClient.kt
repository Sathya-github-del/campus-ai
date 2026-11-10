package dev.studyapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP error from the backend; [code] is the HTTP status (401 = bad token, 402 = balance too low). */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isInsufficientBalance: Boolean get() = code == 402
}

/** The backend contract. [ApiClient] is the real implementation; tests use fakes. */
interface StudyApi {
    suspend fun register(): RegisterResponse
    suspend fun wallet(token: String): WalletResponse
    suspend fun createTopUp(token: String, amountInr: Int): TopUpResponse
    suspend fun confirmTopUp(token: String?, topupId: String): WalletResponse
    suspend fun models(token: String?): ModelsResponse
    suspend fun chat(token: String, request: ChatRequest): ChatResponse
}

/**
 * Thin client over HttpURLConnection + kotlinx.serialization, so it runs unchanged in JVM unit
 * tests (same approach as TileOps' ApiClient). No certificate pinning in v1: the backend is a
 * normal public HTTPS service in release.
 */
class ApiClient(
    baseUrl: String,
    private val timeoutMs: Int = 20_000,
    /** AI replies can take a while, especially Expert. */
    private val chatTimeoutMs: Int = 90_000,
) : StudyApi {
    val baseUrl: String = normalizeUrl(baseUrl)

    companion object {
        /** Lenient decoding: unknown fields ignored, so the backend can add fields without breaking old apps. */
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        /** Requests send explicit nulls (`"conversation_id": null`) exactly as the contract shows them. */
        private val requestJson = Json { explicitNulls = true; encodeDefaults = true }

        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }

        /** Pulls a human-readable message from FastAPI-style `{"detail": "..."}` or `{"error": "..."}` bodies. */
        internal fun detail(text: String): String? = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            (o["detail"] ?: o["error"] ?: o["message"])?.jsonPrimitive?.content
        }.getOrNull()
    }

    internal suspend fun request(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
        readTimeoutMs: Int = timeoutMs,
    ): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            val payload = body ?: if (method == "POST") "{}" else null
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, detail(text) ?: "HTTP $code")
            text
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <reified T> encode(value: T): String = requestJson.encodeToString(value)

    private inline fun <reified T> decode(text: String): T =
        try { json.decodeFromString(text) } catch (e: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException: surface as an I/O-level failure.
            throw IOException("unexpected response from server: ${e.message}", e)
        }

    override suspend fun register(): RegisterResponse =
        decode(request("POST", "/v1/auth/register", token = null))

    override suspend fun wallet(token: String): WalletResponse =
        decode(request("GET", "/v1/wallet", token))

    override suspend fun createTopUp(token: String, amountInr: Int): TopUpResponse =
        decode(request("POST", "/v1/wallet/topup", token, encode(TopUpRequest(amountInr))))

    override suspend fun confirmTopUp(token: String?, topupId: String): WalletResponse =
        decode(request("POST", "/v1/wallet/topup/confirm", token, encode(TopUpConfirmRequest(topupId))))

    override suspend fun models(token: String?): ModelsResponse =
        decode(request("GET", "/v1/models", token))

    override suspend fun chat(token: String, request: ChatRequest): ChatResponse =
        decode(request("POST", "/v1/chat", token, encode(request), readTimeoutMs = chatTimeoutMs))
}
