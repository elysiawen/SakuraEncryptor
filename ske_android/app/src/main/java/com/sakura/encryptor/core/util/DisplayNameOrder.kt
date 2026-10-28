package com.sakura.encryptor.core.util

import java.text.Collator
import java.util.Locale

/**
 * Orders display names exactly like the web client.
 *
 * `FileBrowser.vue` sorts decrypted names with
 * `decName.localeCompare(decName, 'zh-CN')`, which is a locale-aware collation
 * without numeric awareness. Java's [Collator] with [Locale.CHINA] uses the
 * same collation, so both clients show the same order — including the quirk
 * that "10" comes before "2".
 *
 * Cloud names are opaque tokens, so every listing has to be decrypted first and
 * then ordered with this comparators; sorting the raw names yields a seemingly
 * random order.
 */
object DisplayNameOrder : Comparator<String> {

    // Collator is stateful and not thread-safe, while listings are built on IO
    // threads; each thread therefore keeps its own instance.
    private val collators = ThreadLocal.withInitial { Collator.getInstance(Locale.CHINA) }

    override fun compare(a: String, b: String): Int = collators.get().compare(a, b)
}
