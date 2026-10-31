package com.sakura.encryptor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        DownloadTaskEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SakuraDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: SakuraDatabase? = null

        /**
         * Profiles gained a backend switch (AList / OpenList).
         *
         * Written out explicitly rather than left to the destructive fallback:
         * upgrading must not throw away the user's saved profiles and their
         * sealed passwords.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN provider TEXT NOT NULL DEFAULT 'alist'"
                )
            }
        }

        fun get(context: Context): SakuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SakuraDatabase::class.java,
                    "sakura.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
