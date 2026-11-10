package dev.straightanswer.app.ui

import dev.straightanswer.app.core.Tier
import dev.straightanswer.app.data.ApiException
import dev.straightanswer.app.data.Backend
import dev.straightanswer.app.data.FakeApi
import dev.straightanswer.app.data.HistoryItem
import dev.straightanswer.app.data.MemoryPrefs
import dev.straightanswer.app.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val api = FakeApi()
    private val prefs = MemoryPrefs()
    private val backend = Backend(api, prefs)

    private fun item(id: String, cost: Double) = HistoryItem(id, "question $id", Instant.EPOCH, 1, cost, Tier.FAST)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun signedIn(): ProfileViewModel {
        api.accounts["a@b.co"] = "pw" to "acct"
        api.validTokens += "acct"
        api.wallets["acct"] = 7.5
        api.history["acct"] = mutableListOf(item("c1", 0.02), item("c2", 0.4))
        backend.login("a@b.co", "pw")
        return ProfileViewModel(backend)
    }

    @Test
    fun loadsHistoryBalanceAndWhoYouAre() = runTest {
        val s = signedIn().state.value
        assertEquals(listOf("c1", "c2"), s.history!!.map { it.conversationId })
        assertEquals(0.42, s.historyTotalInr, 1e-9)
        assertEquals(7.5, s.balance!!, 0.0)
        assertEquals("a@b.co", s.email)
        assertFalse(s.isGuest)
        assertFalse(s.loading)
    }

    @Test
    fun deletingAnItemNeedsConfirmation() = runTest {
        val vm = signedIn()
        vm.askDelete(vm.state.value.history!!.first())
        assertTrue(api.calls.none { it.startsWith("deleteHistory") })
        vm.cancelDelete()
        assertNull(vm.state.value.confirmDelete)
        assertTrue(api.calls.none { it.startsWith("deleteHistory") })

        vm.askDelete(vm.state.value.history!!.first())
        vm.confirmDelete()
        assertEquals(listOf("c2"), vm.state.value.history!!.map { it.conversationId })
        assertTrue(vm.state.value.deleting.isEmpty())
        assertEquals(listOf("c2"), api.history["acct"]!!.map { it.conversationId })
    }

    @Test
    fun aFailedDeleteKeepsTheItemAndSaysSo() = runTest {
        val vm = signedIn()
        api.deleteError = IOException("offline")
        vm.askDelete(vm.state.value.history!!.first())
        vm.confirmDelete()
        assertEquals(2, vm.state.value.history!!.size)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.deleting.isEmpty())
    }

    @Test
    fun aFailedLoadCanBeRetried() = runTest {
        api.historyError = ApiException(500, "boom")
        val vm = signedIn()
        assertNull(vm.state.value.history)
        assertNotNull(vm.state.value.loadError)
        api.historyError = null
        vm.load()
        assertEquals(2, vm.state.value.history!!.size)
        assertNull(vm.state.value.loadError)
    }

    @Test
    fun logoutIsConfirmedThenSignsOut() = runTest {
        val vm = signedIn()
        vm.askLogout()
        assertTrue(vm.state.value.confirmLogout)
        assertEquals(Session.Account("a@b.co"), backend.session.value)
        vm.logout()
        assertEquals(Session.SignedOut(), backend.session.value)
        assertNull(prefs.tokenValue)
    }

    @Test
    fun deleteAccountOnlyArmsAfterTypingTheWord() = runTest {
        val vm = signedIn()
        vm.openDeleteAccount()
        vm.deleteAccount() // nothing typed: no request
        assertTrue(api.calls.none { it == "deleteAccount" })
        vm.setDeleteConfirm("delet")
        assertFalse(vm.state.value.deleteAccount!!.armed)
        vm.deleteAccount()
        assertTrue(api.calls.none { it == "deleteAccount" })
        vm.setDeleteConfirm(" delete ")
        assertTrue(vm.state.value.deleteAccount!!.armed)
        vm.deleteAccount()
        assertEquals(1, api.calls.count { it == "deleteAccount" })
        assertEquals(Session.SignedOut(), backend.session.value)
        assertNull(vm.state.value.deleteAccount)
    }

    @Test
    fun aFailedAccountDeletionKeepsYouSignedIn() = runTest {
        val vm = signedIn()
        api.deleteError = ApiException(500, "boom")
        vm.openDeleteAccount()
        vm.setDeleteConfirm("DELETE")
        vm.deleteAccount()
        val d = vm.state.value.deleteAccount!!
        assertFalse(d.busy)
        assertTrue(d.error!!.startsWith("Your account was not deleted."))
        assertEquals(Session.Account("a@b.co"), backend.session.value)
        assertEquals("acct", prefs.tokenValue)
    }

    @Test
    fun guestsSeeTheirHistoryToo() = runTest {
        backend.ensureRegistered()
        api.history["tok1"] = mutableListOf(item("g1", 0.0))
        val s = ProfileViewModel(backend).state.value
        assertTrue(s.isGuest)
        assertNull(s.email)
        assertEquals(listOf("g1"), s.history!!.map { it.conversationId })
    }
}
