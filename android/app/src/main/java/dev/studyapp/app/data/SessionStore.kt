package dev.studyapp.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow

/** What the app persists. An interface so view models and [Backend] can be unit-tested with an in-memory fake. */
interface AppPrefs {
    suspend fun token(): String?
    suspend fun saveToken(token: String)
    suspend fun clearToken()
    suspend fun tierKey(): String?
    suspend fun saveTierKey(key: String)
    suspend fun expertConfirmSkipped(): Boolean
    suspend fun setExpertConfirmSkipped(skip: Boolean)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * DataStore-backed prefs. The bearer token is stored in app-private storage, excluded from backup.
 * Deliberately NOT Keystore-encrypted in v1: the token is the only key to the student's wallet
 * (no login yet), and a Keystore key lost on some OEM ROMs would silently lose their balance.
 * See README "Known gaps" (account recovery comes before real money).
 */
class SessionStore(private val context: Context) : AppPrefs {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val TIER = stringPreferencesKey("tier")
        val EXPERT_SKIP = booleanPreferencesKey("expert_confirm_skipped")
    }

    val hasToken: Flow<Boolean> = context.dataStore.data.map { !it[Keys.TOKEN].isNullOrBlank() }

    override suspend fun token(): String? = context.dataStore.data.first()[Keys.TOKEN]?.takeIf { it.isNotBlank() }
    override suspend fun saveToken(token: String) { context.dataStore.edit { it[Keys.TOKEN] = token } }
    override suspend fun clearToken() { context.dataStore.edit { it.remove(Keys.TOKEN) } }
    override suspend fun tierKey(): String? = context.dataStore.data.first()[Keys.TIER]
    override suspend fun saveTierKey(key: String) { context.dataStore.edit { it[Keys.TIER] = key } }
    override suspend fun expertConfirmSkipped(): Boolean = context.dataStore.data.first()[Keys.EXPERT_SKIP] ?: false
    override suspend fun setExpertConfirmSkipped(skip: Boolean) { context.dataStore.edit { it[Keys.EXPERT_SKIP] = skip } }
}
