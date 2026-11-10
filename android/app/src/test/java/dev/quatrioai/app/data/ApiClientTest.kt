package dev.quatrioai.app.data

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

    @Test
    fun signupAndLoginSendCredentialsWithoutABearer() = runTest {
        routes["POST /v1/auth/signup"] = 200 to """{"token":"s1"}"""
        routes["POST /v1/auth/login"] = 200 to """{"token":"l1","expires_in":3600}"""
        assertEquals("s1", api.signup("a@b.co", "pw").token)
        assertEquals("""{"email":"a@b.co","password":"pw"}""", seen.last().body)
        assertNull(seen.last().auth)
        assertEquals("l1", api.login("a@b.co", "pw").token)
        assertEquals("/v1/auth/login", seen.last().path)
        assertNull(seen.last().auth)
    }

    @Test
    fun duplicateSignupIs409() = runTest {
        routes["POST /v1/auth/signup"] = 409 to """{"detail":"Email already registered."}"""
        try {
            api.signup("a@b.co", "pw"); fail()
        } catch (e: ApiException) {
            assertEquals(409, e.code)
        }
    }

    @Test
    fun signupValidationErrorsFallBackToFriendlyWording() = runTest {
        routes["POST /v1/auth/signup"] = 422 to """{"detail":[{"loc":["body","password"],"msg":"field required","type":"missing"}]}"""
        try {
            api.signup("a@b.co", "pw"); fail()
        } catch (e: ApiException) {
            assertEquals(422, e.code)
            assertEquals("Check your email and password and try again.", dev.quatrioai.app.core.AuthText.signupError(e))
        }
        routes["POST /v1/auth/signup"] = 422 to """{"detail":"Password must be at least 8 characters."}"""
        try {
            api.signup("a@b.co", "pw"); fail()
        } catch (e: ApiException) {
            assertEquals("Password must be at least 8 characters.", dev.quatrioai.app.core.AuthText.signupError(e))
        }
    }

    @Test
    fun forgotPasswordPostsEmailAndIgnoresTheBody() = runTest {
        routes["POST /v1/auth/forgot-password"] = 200 to """{"detail":"If that email exists, a reset link has been sent."}"""
        api.forgotPassword("a@b.co")
        assertEquals("""{"email":"a@b.co"}""", seen.last().body)
    }

    @Test
    fun historyIsParsedLeniently() = runTest {
        routes["GET /v1/history"] = 200 to """{"items":[{"conversation_id":"c1","first_question":"hi","created_at":"2026-11-10T09:30:00Z","parts":1,"total_cost_inr":0.02,"tier":"fast","new_field":1}]}"""
        val page = api.history("t1")
        assertEquals("c1", page.items.single().conversationId)
        assertEquals(setOf("new_field"), page.unknownFields)
        assertEquals("Bearer t1", seen.last().auth)
    }

    @Test
    fun historyThatIsNotJsonIsAnIoError() = runTest {
        routes["GET /v1/history"] = 200 to "<html>oops</html>"
        try {
            api.history("t1"); fail()
        } catch (e: IOException) {
            assertFalse(e is ApiException)
        }
    }

    @Test
    fun deletesUseDeleteWithBearerAndAnEncodedId() = runTest {
        routes["DELETE /v1/history/c%201%2F2"] = 200 to ""
        routes["DELETE /v1/account"] = 200 to """{"deleted":true}"""
        api.deleteHistory("t1", "c 1/2")
        assertEquals("DELETE", seen.last().method)
        assertEquals("/v1/history/c%201%2F2", seen.last().path)
        assertEquals("Bearer t1", seen.last().auth)
        api.deleteAccount("t1")
        assertEquals("DELETE /v1/account", seen.last().method + " " + seen.last().path)
        assertEquals("", seen.last().body)
    }
}
