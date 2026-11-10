package dev.straightanswer.app.data

import dev.straightanswer.app.core.Tier
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
    @Test
    fun signupStoresTokenAndEmailAndStartsFreshBalance() = runTest {
        backend.ensureRegistered() // a guest first
        api.wallets["tok1"] = 5.0
        backend.refreshBalance()
        backend.signup("a@b.co", "pw")
        assertEquals("tok2", prefs.tokenValue)
        assertEquals("a@b.co", prefs.email)
        assertEquals(Session.Account("a@b.co"), backend.session.value)
        assertEquals(0.0, backend.balance.value!!, 0.0) // the account's wallet, not the guest's ₹5
    }

    @Test
    fun restoreSessionReadsWhatIsStored() = runTest {
        assertEquals(Session.SignedOut(), backend.restoreSession())
        prefs.tokenValue = "tok9"
        assertEquals(Session.Guest, backend.restoreSession())
        prefs.email = "a@b.co"
        assertEquals(Session.Account("a@b.co"), backend.restoreSession())
    }

    @Test
    fun anAccountTokenRejectedBy401SignsOutInsteadOfBecomingAGuest() = runTest {
        api.accounts["a@b.co"] = "pw" to "acct"
        backend.login("a@b.co", "pw")
        api.validTokens -= "acct"
        api.calls.clear()
        try {
            backend.refreshBalance(); org.junit.Assert.fail()
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(listOf("wallet"), api.calls) // no silent register()
        assertNull(prefs.tokenValue)
        assertNull(prefs.email)
        assertEquals(Session.SignedOut(expired = true), backend.session.value)
    }

    @Test
    fun afterLogoutNothingReRegistersSilently() = runTest {
        backend.ensureRegistered()
        backend.logout()
        assertEquals(Session.SignedOut(), backend.session.value)
        assertNull(backend.balance.value)
        try {
            backend.refreshBalance(); org.junit.Assert.fail()
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, api.calls.count { it == "register" })
        // ...but the explicit guest path still works.
        assertEquals("tok2", backend.ensureRegistered())
        assertEquals(Session.Guest, backend.session.value)
    }

    @Test
    fun deletingAMissingHistoryItemCountsAsDone() = runTest {
        backend.ensureRegistered()
        backend.deleteHistory("gone") // 404 from the fake: no exception
        assertEquals("deleteHistory:gone", api.calls.last())
    }

    @Test
    fun deleteAccountSignsOut() = runTest {
        api.accounts["a@b.co"] = "pw" to "acct"
        backend.login("a@b.co", "pw")
        backend.deleteAccount()
        assertEquals(Session.SignedOut(), backend.session.value)
        assertNull(prefs.tokenValue)
        assertEquals(emptyMap<String, Pair<String, String>>(), api.accounts)
    }
}
