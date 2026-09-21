package com.sakura.encryptor.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sakura.encryptor.ui.theme.AccentTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sakura_settings")

/**
 * User preferences.
 *
 * Two password scopes are kept apart on purpose:
 *  - the *local* password is a single fixed secret used for local `.ske` files;
 *  - cloud profiles carry their own secrets, stored on [com.sakura.encryptor.data.db.ProfileEntity].
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ACCENT = stringPreferencesKey("accent")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CACHE_BLOCKS = intPreferencesKey("cache_blocks")

        // Local (server-independent) password.
        val LOCAL_REMEMBER_PASSWORD = booleanPreferencesKey("local_remember_password")
        val LOCAL_SEALED_PASSWORD = stringPreferencesKey("local_sealed_password")

        // Cloud profiles.
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val LAST_SERVER = stringPreferencesKey("last_server")
        val LAST_USERNAME = stringPreferencesKey("last_username")

        // Local folder grants.
        val LOCAL_TREE_URI = stringPreferencesKey("local_tree_uri")
        val LOCAL_OUTPUT_URI = stringPreferencesKey("local_output_uri")

        // Player.
        val SUBTITLE_TEXT_SIZE = intPreferencesKey("subtitle_text_size")

        // Launch gate.
        val APP_LOCK_CREDENTIAL = stringPreferencesKey("app_lock_credential")
        val APP_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
    }

    val accent: Flow<AccentTheme> =
        context.settingsDataStore.data.map { AccentTheme.fromId(it[Keys.ACCENT]) }

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { ThemeMode.fromId(it[Keys.THEME_MODE]) }

    /** Number of 1 MiB blocks kept decrypted in memory while streaming. */
    val cacheBlocks: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.CACHE_BLOCKS] ?: DEFAULT_CACHE_BLOCKS }

    // ---- local password ------------------------------------------------------

    val localRememberPassword: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_REMEMBER_PASSWORD] ?: false }

    val localSealedPassword: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_SEALED_PASSWORD] }

    suspend fun setLocalRememberPassword(enabled: Boolean) =
        context.settingsDataStore.edit {
            it[Keys.LOCAL_REMEMBER_PASSWORD] = enabled
            if (!enabled) it.remove(Keys.LOCAL_SEALED_PASSWORD)
        }

    suspend fun setLocalSealedPassword(value: String?) =
        context.settingsDataStore.edit {
            if (value == null) it.remove(Keys.LOCAL_SEALED_PASSWORD) else it[Keys.LOCAL_SEALED_PASSWORD] = value
        }

    // ---- cloud profiles ------------------------------------------------------

    val activeProfileId: Flow<Long?> =
        context.settingsDataStore.data.map { it[Keys.ACTIVE_PROFILE_ID] }

    val lastServer: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.LAST_SERVER] ?: "" }

    val lastUsername: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.LAST_USERNAME] ?: "" }

    suspend fun setActiveProfileId(id: Long?) =
        context.settingsDataStore.edit {
            if (id == null) it.remove(Keys.ACTIVE_PROFILE_ID) else it[Keys.ACTIVE_PROFILE_ID] = id
        }

    suspend fun setLastLogin(server: String, username: String) =
        context.settingsDataStore.edit {
            it[Keys.LAST_SERVER] = server
            it[Keys.LAST_USERNAME] = username
        }

    // ---- cache / appearance --------------------------------------------------

    suspend fun setAccent(theme: AccentTheme) =
        context.settingsDataStore.edit { it[Keys.ACCENT] = theme.name }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setCacheBlocks(blocks: Int) =
        context.settingsDataStore.edit {
            it[Keys.CACHE_BLOCKS] = blocks.coerceIn(MIN_CACHE_BLOCKS, MAX_CACHE_BLOCKS)
        }

    // ---- player --------------------------------------------------------------

    /** Subtitle font size, in sp, used by the video player. */
    val subtitleTextSize: Flow<Int> =
        context.settingsDataStore.data.map {
            it[Keys.SUBTITLE_TEXT_SIZE] ?: DEFAULT_SUBTITLE_TEXT_SIZE
        }

    suspend fun setSubtitleTextSize(sizeSp: Int) =
        context.settingsDataStore.edit {
            it[Keys.SUBTITLE_TEXT_SIZE] =
                sizeSp.coerceIn(MIN_SUBTITLE_TEXT_SIZE, MAX_SUBTITLE_TEXT_SIZE)
        }

    // ---- launch gate ---------------------------------------------------------

    /**
     * `salt:hash` of the optional launch password, both URL-safe Base64.
     * Null or empty means the gate is off.
     */
    val appLockCredential: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.APP_LOCK_CREDENTIAL] }

    suspend fun setAppLockCredential(value: String?) =
        context.settingsDataStore.edit {
            if (value.isNullOrEmpty()) {
                it.remove(Keys.APP_LOCK_CREDENTIAL)
            } else {
                it[Keys.APP_LOCK_CREDENTIAL] = value
            }
        }

    /** Whether the launch screen offers fingerprint / face unlock. */
    val appLockBiometric: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.APP_LOCK_BIOMETRIC] ?: false }

    suspend fun setAppLockBiometric(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.APP_LOCK_BIOMETRIC] = enabled }

    // ---- local folders -------------------------------------------------------

    /** SAF tree the user picked for browsing local `.ske` files. */
    val localTreeUri: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_TREE_URI] }

    /** SAF tree used as the default target when encrypting / decrypting. */
    val localOutputUri: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LOCAL_OUTPUT_URI] }

    suspend fun setLocalTreeUri(value: String?) =
        context.settingsDataStore.edit {
            if (value == null) it.remove(Keys.LOCAL_TREE_URI) else it[Keys.LOCAL_TREE_URI] = value
        }

    suspend fun setLocalOutputUri(value: String?) =
        context.settingsDataStore.edit {
            if (value == null) it.remove(Keys.LOCAL_OUTPUT_URI) else it[Keys.LOCAL_OUTPUT_URI] = value
        }

    companion object {
        const val DEFAULT_CACHE_BLOCKS = 64
        const val MIN_CACHE_BLOCKS = 8
        const val MAX_CACHE_BLOCKS = 256

        const val DEFAULT_SUBTITLE_TEXT_SIZE = 16
        const val MIN_SUBTITLE_TEXT_SIZE = 10
        const val MAX_SUBTITLE_TEXT_SIZE = 32
    }
}
