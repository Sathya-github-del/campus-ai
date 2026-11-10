package dev.studyapp.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Runs ApiClient against a real local HTTP server speaking the backend contract. */
class ApiClientTest {
    private data class Seen(val method: String, val path: String, val auth: String?, val body: String, val contentType: String?)

    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Seen>()
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val path = ex.requestURI.toString()
            seen += Seen(ex.requestMethod, path, ex.requestHeaders.getFirst("Authorization"), body,
                ex.requestHeaders.getFirst("Content-Type"))
            val (code, resp) = routes["${ex.requestMethod} $path"] ?: (404 to """{"detail":"Not Found"}""")
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        api = ApiClient("127.0.0.1:${server.address.port}/")
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun normalizesBaseUrl() {
        assertEquals("http://127.0.0.1:${server.address.port}", api.baseUrl)
        assertEquals("http://10.0.2.2:8000", ApiClient.normalizeUrl(" http://10.0.2.2:8000/ "))
        assertEquals("https://api.example.in", ApiClient.normalizeUrl("https://api.example.in"))
    }

    @Test
    fun registerSendsNoTokenAndParsesToken() = runTest {
        routes["POST /v1/auth/register"] = 200 to """{"token":"abc123","user_id":"u1"}"""
        assertEquals("abc123", api.register().token)
        assertNull(seen.single().auth)
        assertEquals("POST", seen.single().method)
    }

    @Test
    fun walletSendsBearerAndParsesIntegerOrDecimalBalance() = runTest {
        routes["GET /v1/wallet"] = 200 to """{"balance_inr": 20}"""
        assertEquals(20.0, api.wallet("t1").balanceInr, 0.0)
        assertEquals("Bearer t1", seen.last().auth)
        routes["GET /v1/wallet"] = 200 to """{"balance_inr": 17.38, "currency": "INR"}"""
        assertEquals(17.38, api.wallet("t1").balanceInr, 1e-9)
    }

    @Test
    fun topUpAndConfirmBodiesMatchContract() = runTest {
        routes["POST /v1/wallet/topup"] = 200 to """{"checkout_url":"https://pay.example/x","topup_id":"tu_9"}"""
        routes["POST /v1/wallet/topup/confirm"] = 200 to """{"balance_inr":70.5}"""
        val order = api.createTopUp("t1", 50)
        assertEquals("tu_9", order.topupId)
        assertEquals("https://pay.example/x", order.checkoutUrl)
        assertEquals("""{"amount_inr":50}""", seen.last().body)
        assertTrue(seen.last().contentType!!.startsWith("application/json"))
        assertEquals(70.5, api.confirmTopUp("t1", "tu_9").balanceInr, 0.0)
        assertEquals("""{"topup_id":"tu_9"}""", seen.last().body)
    }

    @Test
    fun parsesModelTiers() = runTest {
        routes["GET /v1/models"] = 200 to """{"tiers":[
            {"tier":"fast","label":"Fast","est_cost_per_message_inr":0.02},
            {"tier":"smart","label":"Smart","est_cost_per_message_inr":0.25},
            {"tier":"expert","label":"Expert","est_cost_per_message_inr":1.6,"model":"ignored"}]}"""
        val tiers = api.models(null).tiers
        assertEquals(listOf("fast", "smart", "expert"), tiers.map { it.tier })
        assertEquals(1.6, tiers[2].estCostPerMessageInr, 0.0)
    }

    @Test
    fun chatSendsExplicitNullsAndParsesReply() = runTest {
        routes["POST /v1/chat"] = 200 to """{"reply":"x = 4 ✓","conversation_id":"c42","cost_inr":0.018,"wallet_balance_inr":19.98}"""
        val r = api.chat("t1", ChatRequest("fast", "solve 2x = 8 (₹ test)", null, null))
        assertEquals("x = 4 ✓", r.reply)
        assertEquals("c42", r.conversationId)
        assertEquals(0.018, r.costInr, 0.0)
        assertEquals(19.98, r.walletBalanceInr, 0.0)
        assertEquals(
            """{"tier":"fast","message":"solve 2x = 8 (₹ test)","conversation_id":null,"image_base64":null}""",
            seen.last().body,
        )
        assertEquals("Bearer t1", seen.last().auth)
    }

    @Test
    fun chatParsesTruncatedAndDefaultsItToFalse() = runTest {
        routes["POST /v1/chat"] = 200 to """{"reply":"x = 4 ✓","conversation_id":"c42","cost_inr":0.018,"wallet_balance_inr":19.98}"""
        assertFalse(api.chat("t1", ChatRequest("fast", "q", null, null)).truncated)
        routes["POST /v1/chat"] = 200 to """{"reply":"Step 1…","conversation_id":"c42","cost_inr":0.5,"wallet_balance_inr":19.5,"truncated":true}"""
        assertTrue(api.chat("t1", ChatRequest("fast", "q", null, null)).truncated)
        routes["POST /v1/chat"] = 200 to """{"reply":"ok","conversation_id":"c42","cost_inr":0.5,"wallet_balance_inr":19.5,"truncated":null}"""
        assertFalse(api.chat("t1", ChatRequest("fast", "q", null, null)).truncated)
    }

    @Test
    fun chatCarriesConversationId() = runTest {
        routes["POST /v1/chat"] = 200 to """{"reply":"ok","conversation_id":"c42","cost_inr":0.02,"wallet_balance_inr":1}"""
        api.chat("t1", ChatRequest("smart", "and then?", "c42", null))
        assertTrue(seen.last().body.contains(""""conversation_id":"c42""""))
    }

    @Test
    fun http402IsInsufficientBalance() = runTest {
        routes["POST /v1/chat"] = 402 to """{"detail":"insufficient balance"}"""
        try {
            api.chat("t1", ChatRequest("expert", "hi", null, null))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(402, e.code)
            assertTrue(e.isInsufficientBalance)
            assertEquals("insufficient balance", e.message)
        }
    }

    @Test
    fun errorWithoutJsonBodyFallsBackToStatus() = runTest {
        routes["GET /v1/wallet"] = 500 to "Internal Server Error"
        try {
            api.wallet("t1"); fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertEquals("HTTP 500", e.message)
        }
    }

    @Test
    fun malformedSuccessBodyIsAnIoError() = runTest {
        routes["GET /v1/wallet"] = 200 to """{"unexpected":true}"""
        try {
            api.wallet("t1"); fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e !is ApiException)
        }
    }

    @Test
    fun detailExtraction() {
        assertEquals("nope", ApiClient.detail("""{"detail":"nope"}"""))
        assertEquals("bad", ApiClient.detail("""{"error":"bad"}"""))
        assertNull(ApiClient.detail("not json"))
        // FastAPI validation errors put a list in "detail": not a readable string, so no message.
        assertNull(ApiClient.detail("""{"detail":[{"loc":["body"],"msg":"field required"}]}"""))
    }
}
