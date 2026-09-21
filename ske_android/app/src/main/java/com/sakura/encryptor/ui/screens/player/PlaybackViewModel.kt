package com.sakura.encryptor.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakura.encryptor.data.db.PlayHistoryEntity
import com.sakura.encryptor.data.db.SakuraDatabase
import kotlinx.coroutines.launch

/** Persists resume positions so playback continues where it stopped. */
class PlaybackViewModel(
    private val database: SakuraDatabase,
) : ViewModel() {

    suspend fun resumePosition(key: String): Long =
        runCatching { database.historyDao().find(key)?.positionMs ?: 0L }.getOrDefault(0L)

    fun savePosition(
        key: String,
        displayName: String,
        remotePath: String,
        server: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (positionMs <= 0) return
        viewModelScope.launch {
            runCatching {
                database.historyDao().upsert(
                    PlayHistoryEntity(
                        key = key,
                        displayName = displayName,
                        remotePath = remotePath,
                        server = server,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }
}
