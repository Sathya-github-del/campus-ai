package dev.straightanswer.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.straightanswer.app.core.ErrorText
import dev.straightanswer.app.data.Backend
import dev.straightanswer.app.data.HistoryItem
import dev.straightanswer.app.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "Delete account" dialog. The button only arms once [typed] matches [ProfileViewModel.CONFIRM_WORD]. */
data class DeleteAccountState(val typed: String = "", val busy: Boolean = false, val error: String? = null) {
    val armed: Boolean get() = typed.trim().equals(ProfileViewModel.CONFIRM_WORD, ignoreCase = true) && !busy
}

data class ProfileUiState(
    val session: Session = Session.Unknown,
    val balance: Double? = null,
    /** Null until the first load finishes. */
    val history: List<HistoryItem>? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    /** History item waiting for "Delete this question?" confirmation. */
    val confirmDelete: HistoryItem? = null,
    /** Conversation ids with a delete request in flight. */
    val deleting: Set<String> = emptySet(),
    val error: String? = null,
    val confirmLogout: Boolean = false,
    val deleteAccount: DeleteAccountState? = null,
) {
    val isGuest: Boolean get() = session == Session.Guest
    val email: String? get() = (session as? Session.Account)?.email
    val historyTotalInr: Double get() = history.orEmpty().sumOf { it.totalCostInr }
}

/**
 * Profile: who you are, your balance, your saved history (GET /v1/history) with per-item delete,
 * log out, and delete account. Signing out (or deleting the account) flips [Backend.session] to
 * SignedOut, which the app root turns into "back to the login screen".
 */
class ProfileViewModel(private val backend: Backend) : ViewModel() {
    companion object {
        /** Typed to arm "Delete account": a deliberate act, never a single accidental tap. */
        const val CONFIRM_WORD = "DELETE"
    }

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { backend.session.collect { s -> _state.update { it.copy(session = s) } } }
        viewModelScope.launch { backend.balance.collect { b -> _state.update { it.copy(balance = b) } } }
        load()
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            runCatching { backend.refreshBalance() }
            try {
                val items = backend.history()
                _state.update { it.copy(loading = false, history = items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = ErrorText.of(e)) }
            }
        }
    }

    fun askDelete(item: HistoryItem) = _state.update { it.copy(confirmDelete = item) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val item = _state.value.confirmDelete ?: return
        val id = item.conversationId
        _state.update { it.copy(confirmDelete = null, deleting = it.deleting + id, error = null) }
        viewModelScope.launch {
            try {
                backend.deleteHistory(id)
                _state.update { s -> s.copy(history = s.history?.filterNot { it.conversationId == id }, deleting = s.deleting - id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(deleting = it.deleting - id, error = "Couldn't delete that question. " + ErrorText.of(e)) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun askLogout() = _state.update { it.copy(confirmLogout = true) }

    fun cancelLogout() = _state.update { it.copy(confirmLogout = false) }

    fun logout() {
        _state.update { it.copy(confirmLogout = false) }
        viewModelScope.launch { backend.logout() }
    }

    fun openDeleteAccount() = _state.update { it.copy(deleteAccount = DeleteAccountState()) }

    fun closeDeleteAccount() = _state.update { if (it.deleteAccount?.busy == true) it else it.copy(deleteAccount = null) }

    fun setDeleteConfirm(text: String) = _state.update { s -> s.copy(deleteAccount = s.deleteAccount?.copy(typed = text, error = null)) }

    fun deleteAccount() {
        val d = _state.value.deleteAccount ?: return
        if (!d.armed) return
        _state.update { it.copy(deleteAccount = d.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                backend.deleteAccount()
                _state.update { it.copy(deleteAccount = null, history = emptyList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(deleteAccount = d.copy(busy = false, error = "Your account was not deleted. " + ErrorText.of(e))) }
            }
        }
    }
}
