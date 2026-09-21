package com.sakura.encryptor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY lastUsedAt DESC, id ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY lastUsedAt DESC, id ASC")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("UPDATE profiles SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun find(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun pending(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, " +
            "localUri = :localUri, error = :error, totalBytes = :totalBytes WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        downloadedBytes: Long,
        totalBytes: Long,
        localUri: String?,
        error: String?,
    )

    /**
     * Persist download progress only while the task is still running, so a
     * pause/cancel that lands mid-download cannot be overwritten.
     */
    @Query(
        "UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes " +
            "WHERE id = :id AND status = 'RUNNING'"
    )
    suspend fun updateDownloadedBytes(id: String, downloadedBytes: Long, totalBytes: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE status IN ('DONE', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM history WHERE `key` = :key LIMIT 1")
    suspend fun find(key: String): PlayHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlayHistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}
