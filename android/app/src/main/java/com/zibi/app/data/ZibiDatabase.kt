package com.zibi.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                localPath TEXT NOT NULL,
                serverDocId INTEGER,
                pages INTEGER NOT NULL DEFAULT 0,
                chunks INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'uploading',
                error TEXT,
                addedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS handoffs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                prompt TEXT NOT NULL,
                docIds TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                openedAt INTEGER,
                done INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

@Database(
    entities = [
        Reminder::class,
        ListEntity::class,
        ListItem::class,
        Task::class,
        MessageDraft::class,
        ActionLogEntry::class,
        LibraryDoc::class,
        Handoff::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ZibiDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun listDao(): ListDao
    abstract fun taskDao(): TaskDao
    abstract fun draftDao(): DraftDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun libraryDao(): LibraryDao
    abstract fun handoffDao(): HandoffDao
}
