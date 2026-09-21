package com.sakura.encryptor.data.prefs

/** Light / dark / follow-system. */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色");

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: System
    }
}
