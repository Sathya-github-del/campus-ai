package dev.quatrioai.app.core

import dev.quatrioai.app.data.ApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Plain-language error messages for students; no stack traces, status codes or jargon. */
object ErrorText {
    /** Logs the real cause for developers (logcat tag "QuatrioAI") and returns the student-facing text. */
    fun of(t: Throwable): String {
        runCatching { android.util.Log.w("QuatrioAI", "request failed", t) }
        return text(t)
    }

    fun text(t: Throwable): String = when (t) {
        is ApiException -> when {
            t.code == 402 -> "Your balance is too low for this. Top up to continue."
            t.code == 401 || t.code == 403 -> "Your session expired. Please try again."
            t.code == 429 -> "Too many requests right now. Wait a moment and try again."
            t.code in 500..599 -> "Something went wrong on our side. Please try again in a moment."
            else -> t.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: "Something went wrong. Please try again."
        }
        is SocketTimeoutException -> "The answer took too long. Check your internet and try again."
        is UnknownHostException, is ConnectException -> "Can't reach the server. Check your internet and try again."
        is IOException -> "Connection problem. Check your internet and try again."
        else -> "Something went wrong. Please try again."
    }
}
