package dev.studyapp.app.data

import dev.studyapp.app.core.Tier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendTest {
    private val api = FakeApi()
    private val prefs = MemoryPrefs()
    private val backend = Backend(api, prefs)

    @Test
    fun registersOnceAndReusesToken() = runTest {
        assertEquals("tok1", backend.ensureRegistered())
        assertEquals("tok1", backend.ensureRegistered())
        assertEquals("tok1", prefs.tokenValue)
        assertEquals(1, api.calls.count { it == "register" })
    }

    @Test
    fun staleTokenReRegistersAndRetriesOnce() = runTest {
        prefs.tokenValue = "gone"
        assertEquals(0.0, backend.refreshBalance(), 0.0)
        assertEquals("tok1", prefs.tokenValue)
        assertEquals(listOf("wallet", "register", "wallet"), api.calls)
    }

    @Test
    fun chatUpdatesSharedBalance() = runTest {
        backend.ensureRegistered()
        api.wallets["tok1"] = 1.0
        assertNull(backend.balance.value)
        backend.chat(ChatRequest("fast", "hi", null, null))
        assertEquals(0.98, backend.balance.value!!, 1e-9)
    }

    @Test
    fun tiersAreFilteredAndOrdered() = runTest {
        api.tiers = listOf(
            ModelTier("expert", "Expert", 1.6),
            ModelTier("gpt-raw-model", "Some Model", 0.5),
            ModelTier("fast", "Fast", 0.02),
        )
        val tiers = backend.refreshTiers()
        assertEquals(listOf(Tier.FAST, Tier.EXPERT), tiers.map { it.tier })
        assertEquals(tiers, backend.tiers.value)
    }
}
