package dev.straightanswer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextTest {
    private fun all(doc: LegalDoc) = LegalText.sections(doc).joinToString("\n") { it.heading + "\n" + it.body }

    @Test
    fun bothDocumentsAreMarkedAsDrafts() {
        assertTrue(LegalText.DRAFT_NOTICE.startsWith("Draft"))
        assertTrue("not been reviewed by a lawyer" in LegalText.DRAFT_NOTICE)
        LegalDoc.entries.forEach { assertTrue(LegalText.sections(it).isNotEmpty()) }
    }

    @Test
    fun theTermsSayTheWalletIsNonTransferableAndForfeitedOnDeletion() {
        val t = all(LegalDoc.TERMS)
        assertTrue("non-transferable" in t)
        assertTrue("forfeited" in t)
    }

    @Test
    fun thePrivacyPolicyNamesOpenRouterAndNoSelling() {
        val p = all(LegalDoc.PRIVACY)
        assertTrue("OpenRouter" in p)
        assertTrue("do not sell" in p)
    }

    @Test
    fun routesRoundTrip() {
        LegalDoc.entries.forEach { assertEquals(it, LegalDoc.fromRoute(it.route)) }
        assertEquals(LegalDoc.TERMS, LegalDoc.fromRoute("nonsense"))
    }
}
