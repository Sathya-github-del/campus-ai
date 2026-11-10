package dev.studyapp.app.ui

import dev.studyapp.app.core.Examples
import dev.studyapp.app.core.Tier
import dev.studyapp.app.data.ApiException
import dev.studyapp.app.data.Backend
import dev.studyapp.app.data.FakeApi
import dev.studyapp.app.data.MemoryPrefs
import dev.studyapp.app.payments.DevMockPaymentGateway
import dev.studyapp.app.payments.TopUpFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val api = FakeApi()
    private val prefs = MemoryPrefs()
    private val backend = Backend(api, prefs)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun registeredWith(balance: Double): AskViewModel {
        backend.ensureRegistered()
        api.wallets["tok1"] = balance
        return AskViewModel(backend, prefs)
    }

    @Test
    fun loadsPricesBalanceAndDefaultsToFast() = runTest {
        val vm = registeredWith(5.0)
        val s = vm.state.value
        assertEquals(Tier.FAST, s.tier)
        assertEquals(5.0, s.balance!!, 0.0)
        assertEquals(0.02, s.estimate!!, 0.0)
        assertNull(s.openThread)
        assertFalse(s.canAsk)
    }

    @Test
    fun aCutOffAnswerIsMarkedAndContinueOpensAReadyFollowUp() = runTest {
        val vm = registeredWith(5.0)
        api.truncateReplies = true
        vm.setDraft("explain everything")
        vm.ask()
        assertTrue(vm.state.value.openThread!!.first.truncated)
        vm.continueCutOff()
        val s = vm.state.value
        assertTrue(s.composingFollowUp)
        assertEquals(AskViewModel.CONTINUE_PROMPT, s.followUp)
        // Something already typed is never overwritten.
        vm.setFollowUp("only the last step please")
        vm.continueCutOff()
        assertEquals("only the last step please", vm.state.value.followUp)
        api.truncateReplies = false
        vm.followUp()
        val t = vm.state.value.openThread!!
        assertTrue(t.exchanges[0].truncated)
        assertFalse(t.exchanges[1].truncated)
    }

    @Test
    fun estimateFollowsTheTypedQuestionAndTheThread() = runTest {
        val vm = registeredWith(5.0)
        vm.setTier(Tier.SMART)
        vm.setDraft("hi")
        val short = vm.state.value.askEstimate!!
        assertEquals(0.2, short, 0.0)
        vm.setDraft("x".repeat(3000))
        val long = vm.state.value.askEstimate!!
        assertTrue("$long", long > short)
        // Every tier card follows the typed question too.
        assertTrue(vm.state.value.livePrices.all { p -> p.estCostInr >= vm.state.value.prices.first { it.tier == p.tier }.estCostInr })
        vm.setTier(Tier.EXPERT)
        assertTrue(vm.state.value.askEstimate!! > long)
        // The flat figure (used for the pre-send balance check) is unchanged.
        assertEquals(1.6, vm.state.value.estimate!!, 0.0)

        vm.ask()
        vm.confirmExpert(dontAskAgain = false)
        vm.startFollowUp()
        vm.setFollowUp("and then?")
        val s = vm.state.value
        assertTrue(s.followUpHistoryTokens > 1000)
        assertTrue(s.followUpEstimate!! > s.prices.first { it.tier == Tier.EXPERT }.estCostInr)
    }

    @Test
    fun askOpensAnAnswerCardWithItsFare() = runTest {
        val vm = registeredWith(5.0)
        vm.setDraft("  what is 2+2  ")
        vm.ask()
        val s = vm.state.value
        val t = s.openThread!!
        assertEquals(1, t.exchanges.size)
        assertFalse(t.isThread)
        assertEquals("what is 2+2", t.first.question)
        assertEquals("answer to: what is 2+2", t.first.answer)
        assertEquals(0.02, t.first.costInr!!, 0.0)
        assertEquals(4.98, t.first.balanceAfter!!, 1e-9)
        assertEquals("c1", t.conversationId)
        assertEquals(4.98, s.balance!!, 1e-9)
        assertEquals("", s.draft)
        assertFalse(s.sending)
        assertNull(api.chatRequests.single().conversationId)
    }

    @Test
    fun showsMeterRunningWhileTheAnswerIsOnItsWay() = runTest {
        val vm = registeredWith(5.0)
        val gate = CompletableDeferred<Unit>()
        api.chatGate = gate
        vm.setDraft("slow one")
        vm.ask()
        var s = vm.state.value
        assertTrue(s.sending)
        assertTrue(s.openThread!!.pending)
        assertFalse(s.canAsk)
        gate.complete(Unit)
        s = vm.state.value
        assertFalse(s.sending)
        assertEquals("answer to: slow one", s.openThread!!.first.answer)
    }

    @Test
    fun followUpContinuesTheSameQuestionAndTotalsTheFare() = runTest {
        val vm = registeredWith(5.0)
        vm.setDraft("first")
        vm.ask()
        vm.startFollowUp()
        assertTrue(vm.state.value.composingFollowUp)
        vm.setFollowUp("and why?")
        vm.followUp()
        val s = vm.state.value
        val t = s.openThread!!
        assertEquals(listOf("first", "and why?"), t.exchanges.map { it.question })
        assertTrue(t.isThread)
        assertEquals(0.04, t.totalCostInr, 1e-9)
        assertEquals("c1", api.chatRequests.last().conversationId)
        assertFalse(s.composingFollowUp)
        assertEquals("", s.followUp)
        assertEquals(1, s.threads.size)
    }

    @Test
    fun aNewQuestionStartsFreshAndIsListedFirst() = runTest {
        val vm = registeredWith(5.0)
        vm.setDraft("one"); vm.ask()
        vm.closeThread()
        assertNull(vm.state.value.openThread)
        vm.setDraft("two"); vm.ask()
        val s = vm.state.value
        assertEquals(listOf("two", "one"), s.threads.map { it.first.question })
        assertEquals("two", s.openThread!!.first.question)
        assertNull(api.chatRequests.last().conversationId)
        assertEquals(0.04, s.sessionCostInr, 1e-9)
        vm.openThread(s.threads.last().id)
        assertEquals("one", vm.state.value.openThread!!.first.question)
    }

    @Test
    fun http402ShowsAddFareAndGivesTheQuestionBack() = runTest {
        val vm = registeredWith(5.0)
        api.chatError = ApiException(402, "insufficient balance")
        vm.setDraft("long question")
        vm.ask()
        val s = vm.state.value
        assertTrue(s.needsTopUp)
        assertNull(s.error)
        assertTrue(s.threads.isEmpty())
        assertNull(s.openThread)
        assertEquals("long question", s.draft)
    }

    @Test
    fun failedFollowUpKeepsTheAnswerAndTheTypedText() = runTest {
        val vm = registeredWith(5.0)
        vm.setDraft("q"); vm.ask()
        vm.startFollowUp()
        api.chatError = ApiException(503, "upstream down")
        vm.setFollowUp("more please")
        vm.followUp()
        val s = vm.state.value
        assertEquals(1, s.openThread!!.exchanges.size)
        assertEquals("more please", s.followUp)
        assertTrue(s.composingFollowUp)
        assertTrue(s.error!!.contains("our side"))
    }

    @Test
    fun balanceBelowEstimateSkipsTheRequest() = runTest {
        val vm = registeredWith(0.0)
        vm.setDraft("hi")
        vm.ask()
        assertTrue(vm.state.value.needsTopUp)
        assertNull(vm.state.value.openThread)
        assertFalse("chat" in api.calls)
    }

    @Test
    fun toppingUpClearsTheAddFareState() = runTest {
        val vm = registeredWith(0.01)
        vm.setDraft("hi")
        vm.ask()
        assertTrue(vm.state.value.needsTopUp)
        TopUpFlow(backend, DevMockPaymentGateway(backend)).topUp(20)
        assertFalse(vm.state.value.needsTopUp)
        vm.ask()
        assertEquals("answer to: hi", vm.state.value.openThread!!.first.answer)
    }

    @Test
    fun otherErrorsShowMessageAndRestoreDraft() = runTest {
        val vm = registeredWith(5.0)
        api.chatError = ApiException(503, "upstream down")
        vm.setDraft("q")
        vm.ask()
        val s = vm.state.value
        assertFalse(s.needsTopUp)
        assertTrue(s.error!!.contains("our side"))
        assertEquals("q", s.draft)
    }

    @Test
    fun expertAsksFirstForNewQuestionsAndFollowUps() = runTest {
        val vm = registeredWith(5.0)
        vm.setTier(Tier.EXPERT)
        assertEquals("expert", prefs.tier)
        vm.setDraft("hard one")
        vm.ask()
        assertEquals(AskTarget.NewQuestion, vm.state.value.confirmExpert)
        assertFalse("chat" in api.calls)
        vm.confirmExpert(dontAskAgain = false)
        assertEquals("expert", api.chatRequests.single().tier)

        vm.startFollowUp()
        vm.setFollowUp("harder")
        vm.followUp()
        val target = vm.state.value.confirmExpert
        assertTrue(target is AskTarget.FollowUp)
        vm.confirmExpert(dontAskAgain = true)
        assertTrue(prefs.expertSkip)
        assertEquals(2, vm.state.value.openThread!!.exchanges.size)

        vm.closeThread()
        vm.setDraft("another")
        vm.ask()
        assertNull(vm.state.value.confirmExpert)
        assertEquals(3, api.chatRequests.size)
    }

    @Test
    fun examplesAndCameraTextFillTheRightBox() = runTest {
        val vm = registeredWith(5.0)
        val withPaste = Examples.ALL.first { it.fill != it.text }
        vm.useExample(withPaste)
        vm.appendRecognised("Dear Sir, could you…")
        assertEquals(withPaste.fill + "Dear Sir, could you…", vm.state.value.draft)

        vm.setDraft("q"); vm.ask()
        vm.startFollowUp()
        vm.appendRecognised("2x + 3 = 11")
        assertEquals("2x + 3 = 11", vm.state.value.followUp)
        assertEquals("", vm.state.value.draft)
    }

    @Test
    fun backFromAnAnswerClearsTransientState() = runTest {
        val vm = registeredWith(5.0)
        vm.setDraft("q"); vm.ask()
        vm.startFollowUp()
        vm.setFollowUp("half-typed")
        vm.closeThread()
        val s = vm.state.value
        assertNull(s.openThread)
        assertFalse(s.composingFollowUp)
        assertEquals("", s.followUp)
        vm.startFollowUp()
        assertFalse(vm.state.value.composingFollowUp)
    }

    @Test
    fun fareKeyCountsAreCompactAndNeverRoundUp() {
        assertEquals("999", compact(999))
        assertEquals("1k", compact(1000))
        assertEquals("2.5k", compact(2500))
        assertEquals("1.2k", compact(1299))
        assertEquals("12k", compact(12_999))
    }
}
