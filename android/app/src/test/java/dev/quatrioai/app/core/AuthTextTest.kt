package dev.quatrioai.app.core

import dev.quatrioai.app.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class AuthTextTest {
    @Test
    fun acceptsRealEmailShapes() {
        listOf("a@b.co", "riya.sharma+test@gmail.com", " name@college.ac.in ", "x_y@sub.domain.org").forEach {
            assertNull(it, AuthText.emailError(it))
        }
    }

    @Test
    fun rejectsTyposBeforeTheNetwork() {
        assertEquals("Enter your email address.", AuthText.emailError("   "))
        listOf("name", "name@gmail", "name gmail.com", "na me@gmail.com", "@gmail.com", "a@@b.com", "a@b.c", "a@.com", "a@b..com")
            .forEach { assertNotNull(it, AuthText.emailError(it)) }
    }

    @Test
    fun passwordMustNotBeEmpty() {
        assertNotNull(AuthText.passwordError(""))
        assertNotNull(AuthText.passwordError("   "))
        assertNull(AuthText.passwordError("x"))
    }

    @Test
    fun duplicateSignupIsRecognised() {
        assertTrue(AuthText.isDuplicateEmail(ApiException(409, "exists")))
        assertEquals(AuthText.DUPLICATE_EMAIL, AuthText.signupError(ApiException(409, "exists")))
    }

    @Test
    fun signupValidationErrorsShowTheServerMessage() {
        assertEquals("Password must be at least 8 characters", AuthText.signupError(ApiException(422, "Password must be at least 8 characters")))
        assertEquals("Check your email and password and try again.", AuthText.signupError(ApiException(422, "HTTP 422")))
    }

    @Test
    fun loginFailuresNeverSayWhichFieldWasWrong() {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            assertEquals(AuthText.BAD_LOGIN, AuthText.loginError(ApiException(code, "Unknown email")))
        }
        // The server's own text (which could name a field) is never shown for these.
        assertTrue("Unknown" !in AuthText.BAD_LOGIN)
        assertEquals(ErrorText.text(UnknownHostException()), AuthText.loginError(UnknownHostException()))
    }
}
