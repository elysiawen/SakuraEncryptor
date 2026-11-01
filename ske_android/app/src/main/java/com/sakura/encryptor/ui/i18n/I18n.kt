package com.sakura.encryptor.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.sakura.encryptor.data.prefs.AppLanguage
import java.util.Locale

/**
 * A concrete language the UI is drawn in.
 *
 * The translations themselves live in `res/values` (English, the default) and
 * `res/values-zh` (Chinese), one file per feature — add a language by dropping
 * a `values-<locale>` folder next to them.
 */
enum class Language(val code: String) {
    CHINESE("zh"),
    ENGLISH("en"),
}

/** Composition-scoped language, so a change repaints the whole tree. */
val LocalLanguage = compositionLocalOf { Language.CHINESE }

/**
 * Process-wide language for code that runs outside composition — view models,
 * notifications and exceptions raised by the transport layer.
 *
 * It is written by the root composable before any screen is drawn; a change
 * re-keys the whole tree, so readers always see the new value on the next pass.
 */
@Volatile
var currentLanguage: Language = Language.CHINESE

/** Application context, used to look strings up outside a composable. */
private lateinit var application: Context

/**
 * Resources for each shipped language, built once.
 *
 * The system configuration is copied and only the locale is swapped, so
 * density, font scale and night mode keep the values the device currently uses.
 */
private val resourcesByLanguage = mutableMapOf<Language, Resources>()

/** Called once from [com.sakura.encryptor.SakuraApp]; every string resolves through it. */
fun initializeI18n(context: Context) {
    application = context.applicationContext
}

/**
 * Translate a string resource for [currentLanguage].
 *
 * Use this outside composition; [tr] is the composable counterpart.
 */
fun t(@StringRes id: Int, vararg args: Any?): String = translate(currentLanguage, id, args)

/** Same as [t], but reads [LocalLanguage] so the caller recomposes on a switch. */
@Composable
fun tr(@StringRes id: Int, vararg args: Any?): String =
    translate(LocalLanguage.current, id, args)

fun translate(language: Language, @StringRes id: Int, args: Array<out Any?>): String {
    val resources = resourcesFor(language)
    return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
}

private fun resourcesFor(language: Language): Resources = synchronized(resourcesByLanguage) {
    resourcesByLanguage.getOrPut(language) {
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.code))
        application.createConfigurationContext(configuration).resources
    }
}

/**
 * Picks the language an explicit preference maps to on this device.
 *
 * "Follow the system" means Chinese on a Chinese device and English everywhere
 * else — the same rule the web and desktop clients use.
 */
fun resolveLanguage(preference: AppLanguage): Language =
    when (preference) {
        AppLanguage.CHINESE -> Language.CHINESE
        AppLanguage.ENGLISH -> Language.ENGLISH
        AppLanguage.SYSTEM ->
            if (Locale.getDefault().language == "zh") Language.CHINESE else Language.ENGLISH
    }
