package com.sakura.encryptor.core.remote

/**
 * Which remote backend a profile talks to.
 *
 * Stored as [storageKey] in the profile row so an added backend never changes
 * the meaning of a value that is already on disk. Unknown values fall back to
 * AList: every profile written before OpenList support existed is an AList
 * account.
 */
enum class RemoteProvider(val storageKey: String, val label: String) {
    ALIST("alist", "AList"),
    OPENLIST("openlist", "OpenList");

    companion object {
        fun fromStorage(value: String?): RemoteProvider =
            entries.firstOrNull { it.storageKey == value } ?: ALIST
    }
}
