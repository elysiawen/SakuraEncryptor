package com.sakura.encryptor.data.prefs

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

/** Light / dark / follow-system. */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /** Displayed in pickers, so it follows the interface language. */
    val label: String
        get() = when (this) {
            System -> t(R.string.theme_follow_system)
            Light -> t(R.string.theme_light)
            Dark -> t(R.string.theme_dark)
        }

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: System
    }
}
