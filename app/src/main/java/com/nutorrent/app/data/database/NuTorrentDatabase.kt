package com.nutorrent.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TorrentEntity::class,
        TorrentFileEntity::class,
        TrackerEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class NuTorrentDatabase : RoomDatabase() {
    abstract fun torrentDao(): TorrentDao

    companion object {
        private const val DB_NAME = "nutorrent.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE torrents ADD COLUMN isMagnet INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE torrents ADD COLUMN uploadedBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE torrents ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE torrents ADD COLUMN selectedFiles TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE torrents ADD COLUMN sequentialDownload INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE torrents ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: NuTorrentDatabase? = null

        fun getInstance(context: Context): NuTorrentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NuTorrentDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
