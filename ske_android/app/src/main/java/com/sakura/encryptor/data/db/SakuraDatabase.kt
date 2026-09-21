package com.sakura.encryptor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        DownloadTaskEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SakuraDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: SakuraDatabase? = null

        fun get(context: Context): SakuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SakuraDatabase::class.java,
                    "sakura.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
