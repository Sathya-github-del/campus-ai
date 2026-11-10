package dev.quatrioai.app.core

import dev.quatrioai.app.data.ApiException

/** Client-side checks before an auth request goes out, and the wording of auth failures. Pure Kotlin, unit-tested. */
object AuthText {
    // Something@something.tld: no spaces, one @, a dot in the domain and a TLD of 2+ letters.
    // Deliberately loose (real validation is the server's and the inbox's job), but it catches typos
    // like "name@gmail", "name gmail.com" or a stray space before a round trip.
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@.]{2,}$")

    fun normalizeEmail(raw: String): String = raw.trim()

    fun emailError(raw: String): String? {
        val e = normalizeEmail(raw)
        return when {
            e.isEmpty() -> "Enter your email address."
            !EMAIL.matches(e) || ".." in e || e.substringAfter('@').startsWith(".") -> "That doesn't look like an email address."
            else -> null
        }
    }

    fun passwordError(password: String): String? = if (password.isBlank()) "Enter your password." else null

    /** True when signup failed because the email already has an account (HTTP 409). */
    fun isDuplicateEmail(t: Throwable): Boolean = t is ApiException && t.code == 409

    const val DUPLICATE_EMAIL = "There's already an account with this email. Log in instead?"
    /** Login failures never say which field was wrong (the server doesn't either). */
    const val BAD_LOGIN = "That email and password don't match. Check both and try again."
    const val RESET_SENT = "If an account exists for that email, we've sent it a link to reset the password. Check your inbox and spam folder."

    fun signupError(t: Throwable): String = when {
        isDuplicateEmail(t) -> DUPLICATE_EMAIL
        // Validation errors (weak password, odd email) carry a readable server message.
        t is ApiException && (t.code == 400 || t.code == 422) ->
            t.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: "Check your email and password and try again."
        else -> ErrorText.text(t)
    }

    fun loginError(t: Throwable): String = when {
        t is ApiException && t.code in setOf(400, 401, 403, 404, 422) -> BAD_LOGIN
        else -> ErrorText.text(t)
    }
}
