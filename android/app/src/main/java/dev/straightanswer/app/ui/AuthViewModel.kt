package dev.straightanswer.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.straightanswer.app.core.AuthText
import dev.straightanswer.app.core.ErrorText
import dev.straightanswer.app.data.Backend
import dev.straightanswer.app.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGNUP, LOGIN }

/** The "forgot password" dialog. [sent] = the generic "if that email exists…" confirmation is showing. */
data class ForgotState(
    val email: String = "",
    val emailError: String? = null,
    val busy: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
)

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGNUP,
    val email: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    /** Field errors appear only after a submit attempt and clear as soon as that field is edited. */
    val emailError: String? = null,
    val passwordError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Signup hit 409: offer "Log in instead" with the email kept. */
    val duplicateEmail: Boolean = false,
    val forgot: ForgotState? = null,
    /** Signed in (account or guest): the screen moves on. */
    val done: Boolean = false,
    val session: Session = Session.Unknown,
    val balance: Double? = null,
) {
    val isGuest: Boolean get() = session == Session.Guest
    /** A guest with money in their wallet is warned that signing in switches wallets. */
    val guestBalanceAtRisk: Double? get() = balance?.takeIf { isGuest && it > 0.0 }
}

/**
 * Signup / login / forgot password, plus the "use without an account" guest path. Client-side
 * checks (email shape, non-empty password) run before anything is sent.
 */
class AuthViewModel(private val backend: Backend, initialMode: AuthMode = AuthMode.SIGNUP) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState(mode = initialMode))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { backend.session.collect { s -> _state.update { it.copy(session = s) } } }
        viewModelScope.launch { backend.balance.collect { b -> _state.update { it.copy(balance = b) } } }
    }

    fun setMode(mode: AuthMode) = _state.update {
        if (it.busy) it else it.copy(mode = mode, error = null, duplicateEmail = false, emailError = null, passwordError = null)
    }

    fun setEmail(v: String) = _state.update { it.copy(email = v, emailError = null, error = null, duplicateEmail = false) }

    fun setPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, error = null) }

    fun togglePassword() = _state.update { it.copy(showPassword = !it.showPassword) }

    /** After a 409: same email, switch to login, password kept so one tap logs in. */
    fun loginInstead() = _state.update { it.copy(mode = AuthMode.LOGIN, error = null, duplicateEmail = false) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val emailErr = AuthText.emailError(s.email)
        val pwErr = AuthText.passwordError(s.password)
        if (emailErr != null || pwErr != null) {
            _state.update { it.copy(emailError = emailErr, passwordError = pwErr, error = null) }
            return
        }
        val email = AuthText.normalizeEmail(s.email)
        _state.update { it.copy(busy = true, error = null, duplicateEmail = false) }
        viewModelScope.launch {
            try {
                if (s.mode == AuthMode.SIGNUP) backend.signup(email, s.password) else backend.login(email, s.password)
                // Never keep the password around longer than the request needs it.
                _state.update { it.copy(busy = false, password = "", done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorText.of(e) // logs the cause
                _state.update {
                    it.copy(
                        busy = false,
                        duplicateEmail = s.mode == AuthMode.SIGNUP && AuthText.isDuplicateEmail(e),
                        error = if (s.mode == AuthMode.SIGNUP) AuthText.signupError(e) else AuthText.loginError(e),
                    )
                }
            }
        }
    }

    /** "Skip for now": an anonymous device wallet via POST /v1/auth/register (the pre-accounts flow). */
    fun continueAsGuest() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                backend.ensureRegistered()
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorText.of(e)) }
            }
        }
    }

    fun openForgot() = _state.update { it.copy(forgot = ForgotState(email = it.email.trim())) }

    fun closeForgot() = _state.update { it.copy(forgot = null) }

    fun setForgotEmail(v: String) = _state.update { s -> s.copy(forgot = s.forgot?.copy(email = v, emailError = null, error = null)) }

    fun sendReset() {
        val f = _state.value.forgot ?: return
        if (f.busy) return
        AuthText.emailError(f.email)?.let { err -> _state.update { it.copy(forgot = f.copy(emailError = err)) }; return }
        _state.update { it.copy(forgot = f.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val next = try {
                backend.forgotPassword(AuthText.normalizeEmail(f.email))
                f.copy(busy = false, sent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only a failed request is an error. The server's answer never says whether the email exists.
                f.copy(busy = false, error = ErrorText.of(e))
            }
            _state.update { it.copy(forgot = if (it.forgot == null) null else next) }
        }
    }
}
