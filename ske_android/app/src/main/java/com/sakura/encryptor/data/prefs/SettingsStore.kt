package com.sakura.encryptor.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sakura.encryptor.core.security.AppLockFactor
import com.sakura.encryptor.core.security.AppLockMatch
import com.sakura.encryptor.ui.theme.AccentTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

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
        val CACHE_THUMBNAILS = booleanPreferencesKey("cache_thumbnails")
        val CACHE_FILES = booleanPreferencesKey("cache_files")
        val CACHE_DERIVED_KEYS = booleanPreferencesKey("cache_derived_keys")
        val CACHE_THUMBNAIL_LIMIT_MB = intPreferencesKey("cache_thumbnail_limit_mb")
        val CACHE_FILE_LIMIT_MB = intPreferencesKey("cache_file_limit_mb")

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

        // File listings.
        val GRID_VIEW = booleanPreferencesKey("grid_view")

        // Player.
        val SUBTITLE_TEXT_SIZE = intPreferencesKey("subtitle_text_size")

        // Launch gate.
        val APP_LOCK_CREDENTIAL = stringPreferencesKey("app_lock_credential")
        val APP_LOCK_FACTORS = stringSetPreferencesKey("app_lock_factors")
        val APP_LOCK_MATCH = stringPreferencesKey("app_lock_match")

        /** Keystore-sealed Base32 TOTP secret; null until the user binds a token. */
        val APP_LOCK_TOTP_SECRET = stringPreferencesKey("app_lock_totp_secret")

        // Written by builds that had only "biometric on/off" and "both".
        val APP_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val APP_LOCK_REQUIRE_BOTH = booleanPreferencesKey("app_lock_require_both")
    }

    val accent: Flow<AccentTheme> =
        context.settingsDataStore.data.map { AccentTheme.fromId(it[Keys.ACCENT]) }

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { ThemeMode.fromId(it[Keys.THEME_MODE]) }

    /** Number of 1 MiB blocks kept decrypted in memory while streaming. */
    val cacheBlocks: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.CACHE_BLOCKS] ?: DEFAULT_CACHE_BLOCKS }

    /** Keep decrypted thumbnails on disk so revisiting a folder is instant. */
    val cacheThumbnails: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.CACHE_THUMBNAILS] ?: true }

    /** Keep decrypted media blocks on disk so replaying a file skips the network. */
    val cacheFiles: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.CACHE_FILES] ?: false }

    /** Memoise derived file keys (in memory only, ~100 ms saved per repeat). */
    val cacheDerivedKeys: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.CACHE_DERIVED_KEYS] ?: true }

    /** Disk budget for thumbnails, in MB; [UNLIMITED_CACHE_MB] means no cap. */
    val cacheThumbnailLimitMb: Flow<Int> =
        context.settingsDataStore.data.map {
            normalizeCacheLimitMb(
                it[Keys.CACHE_THUMBNAIL_LIMIT_MB] ?: DEFAULT_THUMBNAIL_CACHE_MB,
                THUMBNAIL_CACHE_OPTIONS_MB,
            )
        }

    /** Disk budget for cached media content, in MB; 0 means no cap. */
    val cacheFileLimitMb: Flow<Int> =
        context.settingsDataStore.data.map {
            normalizeCacheLimitMb(
                it[Keys.CACHE_FILE_LIMIT_MB] ?: DEFAULT_FILE_CACHE_MB,
                FILE_CACHE_OPTIONS_MB,
            )
        }

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

    suspend fun setCacheThumbnails(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.CACHE_THUMBNAILS] = enabled }

    suspend fun setCacheFiles(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.CACHE_FILES] = enabled }

    suspend fun setCacheDerivedKeys(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.CACHE_DERIVED_KEYS] = enabled }

    suspend fun setCacheThumbnailLimitMb(mb: Int) =
        context.settingsDataStore.edit {
            it[Keys.CACHE_THUMBNAIL_LIMIT_MB] =
                normalizeCacheLimitMb(mb, THUMBNAIL_CACHE_OPTIONS_MB)
        }

    suspend fun setCacheFileLimitMb(mb: Int) =
        context.settingsDataStore.edit {
            it[Keys.CACHE_FILE_LIMIT_MB] =
                normalizeCacheLimitMb(mb, FILE_CACHE_OPTIONS_MB)
        }

    /**
     * `0` means "unlimited" and stays untouched; any other value snaps to the
     * closest preset, so a value left behind by an older build can never select
     * nothing in the picker.
     */
    private fun normalizeCacheLimitMb(mb: Int, options: List<Int>): Int {
        if (mb <= UNLIMITED_CACHE_MB) return UNLIMITED_CACHE_MB
        return options.filter { it > 0 }.minByOrNull { abs(it - mb) } ?: options.first()
    }

    // ---- file listings -------------------------------------------------------

    /**
     * Whether file listings are drawn as a grid.
     *
     * Global on purpose: the local and the cloud screen are the same kind of
     * list, so switching once should stick for both, and for the next launch.
     */
    val gridView: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.GRID_VIEW] ?: false }

    suspend fun setGridView(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.GRID_VIEW] = enabled }

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

    /**
     * Which proofs the launch gate demands. Every one of them has to pass.
     *
     * Falls back to the two booleans older builds wrote when the set has never
     * been saved, and never yields an empty set (that would be an open gate).
     */
    val appLockFactors: Flow<Set<AppLockFactor>> =
        context.settingsDataStore.data.map { prefs ->
            val stored = prefs[Keys.APP_LOCK_FACTORS]
            if (stored != null) {
                stored.mapNotNull(AppLockFactor::fromId).toSet()
                    .ifEmpty { AppLockFactor.DEFAULT }
            } else {
                legacyFactors(prefs)
            }
        }

    /** Whether the ticked factors must all pass, or a single one is enough. */
    val appLockMatch: Flow<AppLockMatch> =
        context.settingsDataStore.data.map { AppLockMatch.fromId(it[Keys.APP_LOCK_MATCH]) }

    suspend fun setAppLockMatch(match: AppLockMatch) =
        context.settingsDataStore.edit { it[Keys.APP_LOCK_MATCH] = match.id }

    /** Keystore-sealed TOTP secret; the plaintext secret is never stored. */
    val appLockTotpSecret: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.APP_LOCK_TOTP_SECRET] }

    suspend fun setAppLockTotpSecret(sealed: String?) =
        context.settingsDataStore.edit {
            if (sealed.isNullOrEmpty()) {
                it.remove(Keys.APP_LOCK_TOTP_SECRET)
            } else {
                it[Keys.APP_LOCK_TOTP_SECRET] = sealed
            }
        }

    suspend fun setAppLockFactors(factors: Set<AppLockFactor>) =
        context.settingsDataStore.edit {
            it[Keys.APP_LOCK_FACTORS] = factors
                .ifEmpty { AppLockFactor.DEFAULT }
                .map(AppLockFactor::id)
                .toSet()
        }

    /**
     * "Fingerprint on" used to mean "sensor *or* password", which maps to the
     * sensor alone here: the password is still stored and the system prompt
     * keeps it as its fallback button. "Both" maps to both factors.
     */
    private fun legacyFactors(prefs: Preferences): Set<AppLockFactor> = when {
        prefs[Keys.APP_LOCK_BIOMETRIC] != true -> AppLockFactor.DEFAULT
        prefs[Keys.APP_LOCK_REQUIRE_BOTH] == true ->
            setOf(AppLockFactor.PASSWORD, AppLockFactor.BIOMETRIC)
        else -> setOf(AppLockFactor.BIOMETRIC)
    }

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

        // Cache budgets are the user's call; these are only the starting point.
        const val DEFAULT_THUMBNAIL_CACHE_MB = 128
        const val DEFAULT_FILE_CACHE_MB = 1024

        /** Stored value meaning "no budget": nothing is ever evicted. */
        const val UNLIMITED_CACHE_MB = 0

        /** The presets offered in the UI; [UNLIMITED_CACHE_MB] is always last. */
        val THUMBNAIL_CACHE_OPTIONS_MB =
            listOf(128, 256, 512, 1024, 5120, 10240, UNLIMITED_CACHE_MB)

        val FILE_CACHE_OPTIONS_MB =
            listOf(1024, 5120, 10240, 20480, 51200, UNLIMITED_CACHE_MB)

        const val DEFAULT_SUBTITLE_TEXT_SIZE = 16
        const val MIN_SUBTITLE_TEXT_SIZE = 10
        const val MAX_SUBTITLE_TEXT_SIZE = 32
    }
}
