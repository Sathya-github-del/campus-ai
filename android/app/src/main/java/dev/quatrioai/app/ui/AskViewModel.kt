package dev.quatrioai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.quatrioai.app.core.CostEstimate
import dev.quatrioai.app.core.ErrorText
import dev.quatrioai.app.core.Example
import dev.quatrioai.app.core.OcrText
import dev.quatrioai.app.core.Tier
import dev.quatrioai.app.core.TierPrice
import dev.quatrioai.app.data.ApiException
import dev.quatrioai.app.data.AppPrefs
import dev.quatrioai.app.data.Backend
import dev.quatrioai.app.data.ChatRequest
import dev.quatrioai.app.data.HistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One question and its answer. [answer] is null while the meter is running (request in flight).
 * [costInr] is the exact fare the server charged; [balanceAfter] is the wallet right after it.
 */
data class Exchange(
    val id: Long,
    val question: String,
    val tier: Tier,
    val answer: String? = null,
    val costInr: Double? = null,
    val balanceAfter: Double? = null,
    /** The answer was cut off at the model's length limit (shown as "Cut off · follow up to continue"). */
    val truncated: Boolean = false,
    /** Which model answered (server's human-readable name), for the quiet "Answered by" trust line. */
    val modelName: String? = null,
) {
    val pending: Boolean get() = answer == null
}

/**
 * A question "ticket": one standalone question, optionally continued with explicit follow-ups.
 * The default mental model is one question → one answer card with its fare; it only becomes a
 * (short) thread when the user taps "Follow up". Follow-ups reuse [conversationId], so the
 * backend re-sends the earlier legs and each one costs a little more (blueprint §7).
 */
data class AskThread(
    val id: Long,
    val exchanges: List<Exchange>,
    val conversationId: String? = null,
) {
    val first: Exchange get() = exchanges.first()
    val pending: Boolean get() = exchanges.any { it.pending }
    val totalCostInr: Double get() = exchanges.sumOf { it.costInr ?: 0.0 }
    val isThread: Boolean get() = exchanges.size > 1
}

/** Where a send goes: a brand-new question from home, or a follow-up on an open answer. */
sealed interface AskTarget {
    data object NewQuestion : AskTarget
    data class FollowUp(val threadId: Long) : AskTarget
}

data class AskUiState(
    /** The home screen's question box. */
    val draft: String = "",
    /** The follow-up box on an open answer. */
    val followUp: String = "",
    val tier: Tier = Tier.DEFAULT,
    val prices: List<TierPrice> = emptyList(),
    val balance: Double? = null,
    /** Every question asked this session, newest first (in memory only; saved history is v1.1). */
    val threads: List<AskThread> = emptyList(),
    /** The answer card on screen, or null for home. */
    val openThreadId: Long? = null,
    /** The follow-up composer is open on the current answer. */
    val composingFollowUp: Boolean = false,
    /** Out of balance (HTTP 402, or balance already below the estimate): show "add fare", not an error. */
    val needsTopUp: Boolean = false,
    val error: String? = null,
    /** Expert send waiting for confirmation, and what it was for. */
    val confirmExpert: AskTarget? = null,
    /** A saved conversation is being fetched to reopen it. */
    val openingSaved: Boolean = false,
    /** Saved conversations for the chats drawer; null until the first load finishes. */
    val history: List<HistoryItem>? = null,
    val historyError: String? = null,
) {
    /** The tier's flat, typical per-message estimate from the server (used for the pre-send balance check). */
    val estimate: Double? get() = prices.firstOrNull { it.tier == tier }?.estCostInr
    /** Tier rates scaled live to the question being typed on home (see [CostEstimate]). */
    val livePrices: List<TierPrice> get() = prices.map { it.copy(estCostInr = CostEstimate.forQuestion(it.estCostInr, draft)) }
    /** "about ₹X" on the Ask button: the selected tier, for the typed question. */
    val askEstimate: Double? get() = livePrices.firstOrNull { it.tier == tier }?.estCostInr
    /** Tokens of the open thread's earlier parts, which the server re-sends with a follow-up. */
    val followUpHistoryTokens: Int
        get() = openThread?.exchanges?.sumOf { CostEstimate.tokens(it.question) + CostEstimate.tokens(it.answer.orEmpty()) } ?: 0
    /** Follow-up rates per tier: the typed follow-up plus everything it re-sends. */
    val followUpPrices: List<TierPrice>
        get() = followUpHistoryTokens.let { h -> prices.map { it.copy(estCostInr = CostEstimate.forQuestion(it.estCostInr, followUp, h)) } }
    val followUpEstimate: Double? get() = followUpPrices.firstOrNull { it.tier == tier }?.estCostInr
    val openThread: AskThread? get() = threads.firstOrNull { it.id == openThreadId }
    /** One request at a time: the meter can only run for one fare. */
    val sending: Boolean get() = threads.any { it.pending }
    val canAsk: Boolean get() = draft.isNotBlank() && !sending
    val canFollowUp: Boolean get() = followUp.isNotBlank() && !sending && openThread != null
    val sessionCostInr: Double get() = threads.sumOf { it.totalCostInr }
}

class AskViewModel(private val backend: Backend, private val prefs: AppPrefs) : ViewModel() {
    companion object {
        const val CONTINUE_PROMPT = "Please continue from where you stopped."
    }

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()
    private var nextId = 1L
    private var expertConfirmSkipped = false
    /** The error shown because the first price fetch failed; cleared once a later refresh succeeds. */
    private var loadError: String? = null

    init {
        viewModelScope.launch {
            Tier.fromKey(prefs.tierKey())?.let { t -> _state.update { it.copy(tier = t) } }
            expertConfirmSkipped = prefs.expertConfirmSkipped()
        }
        viewModelScope.launch {
            backend.balance.collect { b ->
                _state.update {
                    // A higher balance (e.g. after a top-up) clears the "add fare" state.
                    val toppedUp = b != null && it.balance != null && b > it.balance
                    it.copy(balance = b, needsTopUp = it.needsTopUp && !toppedUp)
                }
            }
        }
        viewModelScope.launch { backend.tiers.collect { p -> _state.update { it.copy(prices = p) } } }
        refresh()
    }

    /**
     * Fetches tier prices and balance. Called at start and every time the ask screen resumes, so a
     * failed first load recovers and server-side price changes reach a running app. Prices are fetched
     * first and independently: a balance failure must not leave the rate meters blank. Quiet on
     * failure unless nothing is known yet.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                backend.refreshTiers()
                loadError?.let { shown -> _state.update { if (it.error == shown) it.copy(error = null) else it } }
                loadError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.prices.isEmpty()) {
                    val msg = ErrorText.of(e)
                    loadError = msg
                    _state.update { it.copy(error = msg) }
                }
            }
        }
        viewModelScope.launch {
            try {
                if (backend.isRegistered()) backend.refreshBalance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorText.of(e) // logged; the balance meter keeps its last value
            }
        }
    }

    /**
     * Forgets this session's answers and drafts (after logging out, or switching to another wallet),
     * keeping the tier choice and the prices, which don't depend on who is signed in.
     */
    fun reset() {
        loadError = null
        // Re-seed the balance from the backend: its StateFlow won't re-emit a value it already holds
        // (e.g. a new account's ₹0 fetched during signup), so the meter would otherwise stay blank.
        _state.update { AskUiState(tier = it.tier, prices = it.prices, balance = backend.balance.value) }
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun setFollowUp(text: String) = _state.update { it.copy(followUp = text) }

    /** Tapping an example replaces the draft with it (examples are starting points, not add-ons). */
    fun useExample(example: Example) = _state.update { it.copy(draft = example.fill) }

    /** Camera text goes to whichever box is in use: the follow-up box if it's open, otherwise the home draft. */
    fun appendRecognised(text: String) = _state.update {
        if (it.composingFollowUp && it.openThread != null) it.copy(followUp = OcrText.appendTo(it.followUp, text))
        else it.copy(draft = OcrText.appendTo(it.draft, text))
    }

    fun setTier(tier: Tier) {
        _state.update { it.copy(tier = tier) }
        viewModelScope.launch { prefs.saveTierKey(tier.key) }
    }

    fun openThread(id: Long) = _state.update {
        if (it.threads.none { t -> t.id == id }) it
        else it.copy(openThreadId = id, composingFollowUp = false, followUp = "", needsTopUp = false, error = null)
    }

    /** Saved conversations for the chats drawer (quiet on failure if a list is already shown). */
    fun loadHistory() {
        viewModelScope.launch {
            try {
                if (!backend.isRegistered()) return@launch
                val items = backend.history()
                _state.update { it.copy(history = items, historyError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.history == null) it.copy(historyError = ErrorText.of(e)) else it }
            }
        }
    }

    /**
     * Reopens a saved conversation from history. If it's already on screen this session, just opens
     * it; otherwise fetches its question/answer pairs and adds it to "This session" so a follow-up
     * continues the same conversation on the server.
     */
    fun openSaved(conversationId: String) {
        _state.value.threads.firstOrNull { it.conversationId == conversationId }?.let { openThread(it.id); return }
        if (_state.value.openingSaved) return
        _state.update { it.copy(openingSaved = true, error = null) }
        viewModelScope.launch {
            try {
                val r = backend.thread(conversationId)
                val legs = r.exchanges.map { e ->
                    Exchange(nextId++, e.question, Tier.fromKey(e.tier) ?: Tier.DEFAULT, answer = e.answer, costInr = e.costInr)
                }
                if (legs.isEmpty()) throw IllegalStateException("This conversation has no answers yet.")
                val thread = AskThread(nextId++, legs, conversationId = r.conversationId)
                _state.update {
                    it.copy(threads = listOf(thread) + it.threads, openThreadId = thread.id, openingSaved = false,
                        composingFollowUp = false, followUp = "", needsTopUp = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(openingSaved = false, error = ErrorText.of(e)) }
            }
        }
    }

    /** Back to home. A request still in flight keeps running; its card is in "This session". */
    fun closeThread() = _state.update {
        it.copy(openThreadId = null, composingFollowUp = false, followUp = "", needsTopUp = false, error = null)
    }

    fun startFollowUp() = _state.update { if (it.openThread == null) it else it.copy(composingFollowUp = true) }

    /** "Cut off · follow up to continue": opens the follow-up box with a ready-made "continue" request. */
    fun continueCutOff() = _state.update {
        if (it.openThread == null || it.sending) it
        else it.copy(composingFollowUp = true, followUp = it.followUp.ifBlank { CONTINUE_PROMPT })
    }

    fun cancelFollowUp() = _state.update { it.copy(composingFollowUp = false, followUp = "") }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissExpertConfirm() = _state.update { it.copy(confirmExpert = null) }

    fun confirmExpert(dontAskAgain: Boolean) {
        val target = _state.value.confirmExpert ?: return
        if (dontAskAgain) {
            expertConfirmSkipped = true
            viewModelScope.launch { prefs.setExpertConfirmSkipped(true) }
        }
        _state.update { it.copy(confirmExpert = null) }
        submit(target, confirmed = true)
    }

    /** Ask the home draft as a new, standalone question. */
    fun ask() = submit(AskTarget.NewQuestion, confirmed = false)

    /** Send the follow-up box as the next leg of the open answer. */
    fun followUp() {
        val id = _state.value.openThreadId ?: return
        submit(AskTarget.FollowUp(id), confirmed = false)
    }

    private fun submit(target: AskTarget, confirmed: Boolean) {
        val s = _state.value
        val thread = (target as? AskTarget.FollowUp)?.let { f -> s.threads.firstOrNull { it.id == f.threadId } ?: return }
        val text = (if (thread == null) s.draft else s.followUp).trim()
        if (text.isEmpty() || s.sending) return
        val est = s.estimate
        // Don't spend a round trip we know will fail: show the add-fare state straight away.
        if (s.balance != null && est != null && s.balance < est) {
            _state.update { it.copy(needsTopUp = true, error = null) }
            return
        }
        // Blueprint §7: Expert costs ~80x Fast, so it is confirmed before sending.
        if (s.tier == Tier.EXPERT && !confirmed && !expertConfirmSkipped) {
            _state.update { it.copy(confirmExpert = target) }
            return
        }

        val leg = Exchange(nextId++, text, s.tier)
        val threadId = thread?.id ?: nextId++
        _state.update {
            if (thread == null) {
                it.copy(
                    threads = listOf(AskThread(threadId, listOf(leg))) + it.threads,
                    openThreadId = threadId, draft = "", composingFollowUp = false,
                    error = null, needsTopUp = false,
                )
            } else {
                it.copy(
                    threads = it.threads.map { t -> if (t.id == threadId) t.copy(exchanges = t.exchanges + leg) else t },
                    followUp = "", composingFollowUp = false, error = null, needsTopUp = false,
                )
            }
        }

        viewModelScope.launch {
            try {
                val r = backend.chat(ChatRequest(s.tier.key, text, thread?.conversationId, imageBase64 = null))
                val done = leg.copy(answer = r.reply, costInr = r.costInr, balanceAfter = r.walletBalanceInr,
                    truncated = r.truncated, modelName = r.modelName)
                _state.update {
                    it.copy(threads = it.threads.map { t ->
                        if (t.id != threadId) t
                        else t.copy(exchanges = t.exchanges.map { e -> if (e.id == leg.id) done else e }, conversationId = r.conversationId)
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val outOfBalance = e is ApiException && e.isInsufficientBalance
                // Nothing was charged: take the leg back out and give the text back so nothing typed is lost.
                _state.update {
                    val base = it.copy(needsTopUp = outOfBalance, error = if (outOfBalance) null else ErrorText.of(e))
                    if (thread == null) {
                        base.copy(
                            threads = it.threads.filterNot { t -> t.id == threadId },
                            openThreadId = if (it.openThreadId == threadId) null else it.openThreadId,
                            draft = if (it.draft.isBlank()) text else it.draft,
                        )
                    } else {
                        base.copy(
                            threads = it.threads.map { t -> if (t.id == threadId) t.copy(exchanges = t.exchanges - leg) else t },
                            followUp = if (it.followUp.isBlank()) text else it.followUp,
                            composingFollowUp = it.openThreadId == threadId,
                        )
                    }
                }
                if (outOfBalance) runCatching { backend.refreshBalance() }
            }
        }
    }
}
