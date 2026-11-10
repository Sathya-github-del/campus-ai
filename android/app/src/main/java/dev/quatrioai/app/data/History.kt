package dev.quatrioai.app.data

import dev.quatrioai.app.core.Tier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One saved question from GET /v1/history: the conversation's first question, when it was asked,
 * how many parts it grew to, what it cost in total and on which tier. [tier] is null when the
 * server sends a key the app doesn't know (shown without a tier tag rather than a raw name).
 */
data class HistoryItem(
    val conversationId: String,
    val firstQuestion: String,
    val createdAt: Instant?,
    val parts: Int,
    val totalCostInr: Double,
    val tier: Tier?,
)

/** A parsed history response. [unknownFields] and [skipped] are only for logging contract drift. */
data class HistoryPage(
    val items: List<HistoryItem>,
    val unknownFields: Set<String> = emptySet(),
    val skipped: Int = 0,
)

/**
 * Hand-rolled, deliberately lenient parsing of GET /v1/history. The backend is being built in
 * parallel and the field names "may differ slightly", so each field is looked up under the contract
 * name first and then a few likely aliases, numbers may arrive as JSON numbers or strings, and dates
 * as ISO-8601 (with or without a zone) or epoch seconds/milliseconds. Items without any id are
 * skipped (they could not be deleted), never fatal. Unknown keys are collected so the client can log
 * them. Only a body that isn't JSON at all, or has no list anywhere, is an error.
 */
object HistoryParser {
    private val ID = listOf("conversation_id", "conversationId", "id", "conversation")
    private val QUESTION = listOf("first_question", "firstQuestion", "question", "title", "preview", "first_message", "message")
    private val CREATED = listOf("created_at", "createdAt", "started_at", "timestamp", "created", "date")
    private val PARTS = listOf("parts", "part_count", "turns", "message_count", "messages", "exchanges")
    private val COST = listOf("total_cost_inr", "totalCostInr", "cost_inr", "total_inr", "cost", "total_cost")
    private val TIER = listOf("tier", "tier_key", "model_tier")
    private val KNOWN = (ID + QUESTION + CREATED + PARTS + COST + TIER).toSet()
    private val LIST_KEYS = listOf("items", "history", "conversations", "data", "results")

    fun parse(text: String): HistoryPage {
        val root = ApiClient.json.parseToJsonElement(text)
        val list: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> LIST_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray }
                ?: if (LIST_KEYS.any { root[it] is JsonNull }) JsonArray(emptyList())
                else throw IllegalArgumentException("no history list in response")
            else -> throw IllegalArgumentException("history response is not an object")
        }
        val unknown = linkedSetOf<String>()
        var skipped = 0
        val items = list.mapNotNull { el ->
            val o = el as? JsonObject ?: run { skipped++; return@mapNotNull null }
            unknown += o.keys - KNOWN
            item(o) ?: run { skipped++; null }
        }
        return HistoryPage(items, unknown, skipped)
    }

    private fun item(o: JsonObject): HistoryItem? {
        val id = string(o, ID)?.takeIf { it.isNotBlank() } ?: return null
        return HistoryItem(
            conversationId = id,
            firstQuestion = string(o, QUESTION)?.trim().orEmpty(),
            createdAt = first(o, CREATED)?.let(::instant),
            parts = first(o, PARTS)?.let(::count)?.coerceAtLeast(1) ?: 1,
            totalCostInr = first(o, COST)?.let(::number)?.coerceAtLeast(0.0) ?: 0.0,
            tier = Tier.fromKey(string(o, TIER)),
        )
    }

    private fun first(o: JsonObject, keys: List<String>): JsonElement? =
        keys.firstNotNullOfOrNull { k -> o[k]?.takeUnless { it is JsonNull } }

    private fun string(o: JsonObject, keys: List<String>): String? =
        (first(o, keys) as? JsonPrimitive)?.takeUnless { it.booleanOrNull != null }?.content

    private fun number(e: JsonElement): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }

    /** A count, or a list whose size is the count (e.g. `"messages": [...]`). */
    private fun count(e: JsonElement): Int? = when (e) {
        is JsonArray -> e.size
        is JsonPrimitive -> number(e)?.toInt()
        else -> null
    }

    /** ISO-8601 with offset or `Z`, ISO local date-time (assumed UTC), or epoch seconds / milliseconds. */
    internal fun instant(e: JsonElement): Instant? {
        val p = e as? JsonPrimitive ?: return null
        p.longOrNull?.let { n -> return if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n) }
        p.doubleOrNull?.let { d -> return Instant.ofEpochMilli((d * 1000).toLong()) }
        val s = p.content.trim().replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}
