package dev.quatrioai.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** One free-text tag per conversation (e.g. "Homework", "Exam prep"), local to this phone only —
 * never sent to the server, so it survives independently of what the backend's /v1/history returns. */
interface HistoryTagsStore {
    suspend fun tag(conversationId: String): String?
    suspend fun setTag(conversationId: String, tag: String?)
    suspend fun allTags(): Map<String, String>
}

private val Context.historyTagsDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_tags")

class DataStoreHistoryTagsStore(private val context: Context) : HistoryTagsStore {
    private object Keys {
        val TAGS = stringPreferencesKey("tags_json")
    }

    override suspend fun tag(conversationId: String): String? = allTags()[conversationId]

    override suspend fun setTag(conversationId: String, tag: String?) {
        context.historyTagsDataStore.edit { prefs ->
            val current = decode(prefs[Keys.TAGS])
            val next = if (tag.isNullOrBlank()) current - conversationId else current + (conversationId to tag.trim())
            prefs[Keys.TAGS] = ApiClient.json.encodeToString(next)
        }
    }

    override suspend fun allTags(): Map<String, String> = decode(context.historyTagsDataStore.data.first()[Keys.TAGS])

    private fun decode(raw: String?): Map<String, String> =
        raw?.let { runCatching { ApiClient.json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()
}

/** Presets shown as quick-pick chips; a user can still type anything else. */
val PRESET_HISTORY_TAGS = listOf("Homework", "Exam prep", "Code", "General")
