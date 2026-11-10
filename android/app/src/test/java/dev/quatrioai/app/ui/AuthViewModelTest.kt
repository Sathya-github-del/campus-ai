package dev.quatrioai.app.ui

import dev.quatrioai.app.core.AuthText
import dev.quatrioai.app.data.Backend
import dev.quatrioai.app.data.FakeApi
import dev.quatrioai.app.data.MemoryPrefs
import dev.quatrioai.app.data.Session
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val api = FakeApi()
    private val prefs = MemoryPrefs()
    private val backend = Backend(api, prefs)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidInputNeverReachesTheNetwork() = runTest {
        val vm = AuthViewModel(backend)
        vm.setEmail("riya@gmail")
        vm.submit()
        assertNotNull(vm.state.value.emailError)
        assertNotNull(vm.state.value.passwordError)
        assertTrue(api.calls.isEmpty())
        // Editing a field clears just that field's error.
        vm.setEmail("riya@gmail.com")
        assertNull(vm.state.value.emailError)
        assertNotNull(vm.state.value.passwordError)
    }

    @Test
    fun signupSignsInTrimsTheEmailAndForgetsThePassword() = runTest {
        val vm = AuthViewModel(backend)
        vm.setEmail("  riya@gmail.com ")
        vm.setPassword("secret")
        vm.submit()
        val s = vm.state.value
        assertTrue(s.done)
        assertEquals("", s.password)
        assertEquals("riya@gmail.com", prefs.email)
        assertEquals(Session.Account("riya@gmail.com"), backend.session.value)
        assertEquals("secret", api.accounts["riya@gmail.com"]!!.first)
    }

    @Test
    fun duplicateEmailOffersLoginWithTheSameDetails() = runTest {
        api.accounts["riya@gmail.com"] = "secret" to "acct"
        val vm = AuthViewModel(backend)
        vm.setEmail("riya@gmail.com")
        vm.setPassword("secret")
        vm.submit()
        assertTrue(vm.state.value.duplicateEmail)
        assertEquals(AuthText.DUPLICATE_EMAIL, vm.state.value.error)
        assertFalse(vm.state.value.done)
        vm.loginInstead()
        assertEquals(AuthMode.LOGIN, vm.state.value.mode)
        assertEquals("secret", vm.state.value.password)
        vm.submit()
        assertTrue(vm.state.value.done)
        assertEquals("acct", prefs.tokenValue)
    }

    @Test
    fun wrongPasswordGetsTheGenericMessage() = runTest {
        api.accounts["riya@gmail.com"] = "secret" to "acct"
        val vm = AuthViewModel(backend, AuthMode.LOGIN)
        vm.setEmail("riya@gmail.com")
        vm.setPassword("wrong")
        vm.submit()
        assertEquals(AuthText.BAD_LOGIN, vm.state.value.error)
        assertFalse(vm.state.value.done)
        assertNull(prefs.tokenValue)
        // An unknown email reads exactly the same.
        vm.setEmail("nobody@gmail.com")
        vm.setPassword("secret")
        vm.submit()
        assertEquals(AuthText.BAD_LOGIN, vm.state.value.error)
    }

    @Test
    fun skipCreatesAGuestWallet() = runTest {
        val vm = AuthViewModel(backend)
        vm.continueAsGuest()
        assertTrue(vm.state.value.done)
        assertEquals(listOf("register"), api.calls)
        assertEquals(Session.Guest, backend.session.value)
        assertNull(prefs.email)
    }

    @Test
    fun aGuestWithMoneyIsWarnedBeforeSwitchingWallets() = runTest {
        backend.ensureRegistered()
        api.wallets["tok1"] = 12.5
        backend.refreshBalance()
        val vm = AuthViewModel(backend)
        assertEquals(12.5, vm.state.value.guestBalanceAtRisk!!, 0.0)
        api.wallets["tok1"] = 0.0
        backend.refreshBalance()
        assertNull(vm.state.value.guestBalanceAtRisk)
    }

    @Test
    fun forgotPasswordAlwaysShowsTheGenericConfirmation() = runTest {
        val vm = AuthViewModel(backend, AuthMode.LOGIN)
        vm.setEmail("riya@gmail.com")
        vm.openForgot()
        assertEquals("riya@gmail.com", vm.state.value.forgot!!.email) // pre-filled
        vm.sendReset()
        assertTrue(vm.state.value.forgot!!.sent)
        assertEquals(listOf("riya@gmail.com"), api.resetRequests) // no account needed for "sent"
        vm.closeForgot()
        assertNull(vm.state.value.forgot)
    }

    @Test
    fun forgotPasswordValidatesAndReportsOnlyRealFailures() = runTest {
        val vm = AuthViewModel(backend, AuthMode.LOGIN)
        vm.openForgot()
        vm.setForgotEmail("nope")
        vm.sendReset()
        assertNotNull(vm.state.value.forgot!!.emailError)
        assertTrue(api.resetRequests.isEmpty())

        val failing = Backend(object : dev.quatrioai.app.data.AnswerApi by api {
            override suspend fun forgotPassword(email: String) = throw IOException("offline")
        }, prefs)
        val vm2 = AuthViewModel(failing, AuthMode.LOGIN)
        vm2.openForgot()
        vm2.setForgotEmail("riya@gmail.com")
        vm2.sendReset()
        assertFalse(vm2.state.value.forgot!!.sent)
        assertNotNull(vm2.state.value.forgot!!.error)
    }
}
