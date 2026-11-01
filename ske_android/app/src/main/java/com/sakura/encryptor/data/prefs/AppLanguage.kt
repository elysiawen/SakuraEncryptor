package com.sakura.encryptor.data.prefs

import com.sakura.encryptor.R
import com.sakura.encryptor.ui.i18n.t

/** Interface language: follow the device, or pin one explicitly. */
enum class AppLanguage(val id: String) {
    SYSTEM("system"),
    CHINESE("zh"),
    ENGLISH("en");

    /**
     * Shown in the picker. The two concrete languages keep their native names
     * untranslated — that is how every language menu spells them.
     */
    val label: String
        get() = when (this) {
            SYSTEM -> t(R.string.theme_follow_system)
            CHINESE -> "简体中文"
            ENGLISH -> "English"
        }

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
