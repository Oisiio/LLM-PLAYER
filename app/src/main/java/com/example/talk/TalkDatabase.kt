package com.example.talk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class TalkDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE characters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                icon_uri TEXT,
                personality TEXT NOT NULL DEFAULT '',
                style TEXT NOT NULL DEFAULT '',
                system_prompt TEXT NOT NULL DEFAULT '',
                first_message TEXT NOT NULL DEFAULT '',
                scenario TEXT NOT NULL DEFAULT '',
                example_dialogue TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                last_used_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_characters_recent ON characters(favorite DESC, last_used_at DESC)")

        db.execSQL("""
            CREATE TABLE chats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                character_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                last_message TEXT NOT NULL DEFAULT '',
                last_used_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(character_id) REFERENCES characters(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_chats_character_recent ON chats(character_id, last_used_at DESC)")

        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                candidate1 TEXT,
                candidate2 TEXT,
                candidate3 TEXT,
                current_candidate INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_messages_chat_time ON messages(chat_id, timestamp ASC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration yet. Future schema changes must be versioned here.
        if (oldVersion < 2) {
            // Reserved for the first released schema migration.
        }
    }

    companion object {
        private const val DB_NAME = "talk.db"
        private const val DB_VERSION = 1
    }
}
