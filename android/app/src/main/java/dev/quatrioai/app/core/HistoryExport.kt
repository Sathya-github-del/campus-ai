package dev.quatrioai.app.core

import dev.quatrioai.app.data.HistoryItem

/**
 * A plain-Markdown "notes" document built from a history list, for the "Export as notes" share
 * action. Pure Kotlin (no Android dependency), so it's covered by a JVM test; the file write and
 * share intent live in the UI layer.
 */
object HistoryExport {
    const val FILE_NAME = "quatrio-history.md"

    /** [tags] is local-only (never sent to the server), keyed by conversationId. */
    fun markdown(items: List<HistoryItem>, tags: Map<String, String> = emptyMap()): String = buildString {
        appendLine("# Quatrio AI — saved questions")
        appendLine()
        if (items.isEmpty()) {
            appendLine("_No saved questions._")
            return@buildString
        }
        items.forEach { item ->
            appendLine("## ${HistoryText.question(item.firstQuestion)}")
            val meta = listOfNotNull(
                item.tier?.label,
                HistoryText.date(item.createdAt).ifBlank { null },
                HistoryText.parts(item.parts),
                tags[item.conversationId]?.let { "Tag: $it" },
            )
            if (meta.isNotEmpty()) appendLine(meta.joinToString(" · "))
            appendLine("Cost: ${Money.cost(item.totalCostInr)}")
            appendLine()
        }
    }
}
