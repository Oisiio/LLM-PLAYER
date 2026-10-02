package com.example.talk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

class TalkRepository(context: Context) {
    private val dbHelper = TalkDatabase(context.applicationContext)

    fun close() = dbHelper.close()

    fun listCharacters(search: String = ""): List<Character> {
        val db = dbHelper.readableDatabase
        val normalized = search.trim()
        val cursor = if (normalized.isEmpty()) {
            db.query(
                "characters", null, null, null, null, null,
                "favorite DESC, last_used_at DESC, id DESC"
            )
        } else {
            db.query(
                "characters", null, "name LIKE ? COLLATE NOCASE", arrayOf("%$normalized%"),
                null, null, "last_used_at DESC, id DESC"
            )
        }
        return cursor.use { c ->
            buildList {
                val id = c.getColumnIndexOrThrow("id")
                val name = c.getColumnIndexOrThrow("name")
                val icon = c.getColumnIndexOrThrow("icon_uri")
                val personality = c.getColumnIndexOrThrow("personality")
                val style = c.getColumnIndexOrThrow("style")
                val systemPrompt = c.getColumnIndexOrThrow("system_prompt")
                val firstMessage = c.getColumnIndexOrThrow("first_message")
                val scenario = c.getColumnIndexOrThrow("scenario")
                val exampleDialogue = c.getColumnIndexOrThrow("example_dialogue")
                val favorite = c.getColumnIndexOrThrow("favorite")
                val lastUsedAt = c.getColumnIndexOrThrow("last_used_at")
                while (c.moveToNext()) add(Character(
                    id = c.getLong(id), name = c.getString(name), iconUri = c.getStringOrNull(icon),
                    personality = c.getString(personality), style = c.getString(style),
                    systemPrompt = c.getString(systemPrompt), firstMessage = c.getString(firstMessage),
                    scenario = c.getString(scenario), exampleDialogue = c.getString(exampleDialogue),
                    favorite = c.getInt(favorite) != 0, lastUsedAt = c.getLong(lastUsedAt)
                ))
            }
        }
    }

    fun getCharacter(id: Long): Character? {
        val db = dbHelper.readableDatabase
        db.query("characters", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (!c.moveToFirst()) null else c.toCharacter()
        }
    }

    fun saveCharacter(character: Character): Long {
        require(character.name.trim().isNotEmpty()) { "Character name is required" }
        require(character.name.length <= 50) { "Character name must be at most 50 characters" }
        val values = character.toContentValues()
        val db = dbHelper.writableDatabase
        return if (character.id == 0L) db.insertOrThrow("characters", null, values)
        else {
            db.update("characters", values, "id = ?", arrayOf(character.id.toString()))
            character.id
        }
    }

    fun setCharacterFavorite(id: Long, favorite: Boolean) {
        dbHelper.writableDatabase.update(
            "characters", ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun markCharacterUsed(id: Long) {
        dbHelper.writableDatabase.update(
            "characters", ContentValues().apply { put("last_used_at", System.currentTimeMillis()) },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun deleteCharacter(id: Long) {
        dbHelper.writableDatabase.delete("characters", "id = ?", arrayOf(id.toString()))
    }

    fun listChats(characterId: Long): List<Chat> {
        val db = dbHelper.readableDatabase
        db.query("chats", null, "character_id = ?", arrayOf(characterId.toString()), null, null, "last_used_at DESC, id DESC").use { c ->
            return buildList {
                while (c.moveToNext()) add(Chat(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    characterId = c.getLong(c.getColumnIndexOrThrow("character_id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    lastMessage = c.getString(c.getColumnIndexOrThrow("last_message")),
                    lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
                ))
            }
        }
    }

    fun getChat(id: Long): Chat? {
        dbHelper.readableDatabase.query("chats", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (!c.moveToFirst()) null else Chat(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                characterId = c.getLong(c.getColumnIndexOrThrow("character_id")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                lastMessage = c.getString(c.getColumnIndexOrThrow("last_message")),
                lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
            )
        }
    }

    fun createChat(characterId: Long, name: String = "New Chat"): Long {
        val safeName = name.trim().ifEmpty { "New Chat" }.take(30)
        return dbHelper.writableDatabase.insertOrThrow("chats", null, ContentValues().apply {
            put("character_id", characterId); put("name", safeName); put("last_used_at", System.currentTimeMillis())
        })
    }

    fun renameChat(id: Long, name: String) {
        val safeName = name.trim().ifEmpty { "New Chat" }.take(30)
        dbHelper.writableDatabase.update("chats", ContentValues().apply { put("name", safeName) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteChat(id: Long) {
        dbHelper.writableDatabase.delete("chats", "id = ?", arrayOf(id.toString()))
    }

    fun listMessages(chatId: Long): List<Message> {
        dbHelper.readableDatabase.query("messages", null, "chat_id = ?", arrayOf(chatId.toString()), null, null, "timestamp ASC, id ASC").use { c ->
            return buildList {
                while (c.moveToNext()) add(c.toMessage())
            }
        }
    }

    fun insertMessage(message: Message): Long {
        return dbHelper.writableDatabase.insertOrThrow("messages", null, message.toContentValues())
    }

    fun deleteMessagesFrom(chatId: Long, messageId: Long) {
        dbHelper.writableDatabase.delete("messages", "chat_id = ? AND timestamp >= (SELECT timestamp FROM messages WHERE id = ?)", arrayOf(chatId.toString(), messageId.toString()))
    }

    private fun Character.toContentValues() = ContentValues().apply {
        put("name", name.trim()); put("icon_uri", iconUri); put("personality", personality)
        put("style", style); put("system_prompt", systemPrompt); put("first_message", firstMessage)
        put("scenario", scenario); put("example_dialogue", exampleDialogue); put("favorite", if (favorite) 1 else 0)
        put("last_used_at", lastUsedAt)
    }

    private fun Message.toContentValues() = ContentValues().apply {
        put("chat_id", chatId); put("role", role.name); put("content", content); put("timestamp", timestamp)
        put("candidate1", candidate1); put("candidate2", candidate2); put("candidate3", candidate3); put("current_candidate", currentCandidate)
    }

    private fun android.database.Cursor.toCharacter() = Character(
        id = getLong(getColumnIndexOrThrow("id")), name = getString(getColumnIndexOrThrow("name")),
        iconUri = getStringOrNull(getColumnIndexOrThrow("icon_uri")), personality = getString(getColumnIndexOrThrow("personality")),
        style = getString(getColumnIndexOrThrow("style")), systemPrompt = getString(getColumnIndexOrThrow("system_prompt")),
        firstMessage = getString(getColumnIndexOrThrow("first_message")), scenario = getString(getColumnIndexOrThrow("scenario")),
        exampleDialogue = getString(getColumnIndexOrThrow("example_dialogue")), favorite = getInt(getColumnIndexOrThrow("favorite")) != 0,
        lastUsedAt = getLong(getColumnIndexOrThrow("last_used_at"))
    )

    private fun android.database.Cursor.toMessage() = Message(
        id = getLong(getColumnIndexOrThrow("id")), chatId = getLong(getColumnIndexOrThrow("chat_id")),
        role = MessageRole.valueOf(getString(getColumnIndexOrThrow("role"))), content = getString(getColumnIndexOrThrow("content")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")), candidate1 = getStringOrNull(getColumnIndexOrThrow("candidate1")),
        candidate2 = getStringOrNull(getColumnIndexOrThrow("candidate2")), candidate3 = getStringOrNull(getColumnIndexOrThrow("candidate3")),
        currentCandidate = getInt(getColumnIndexOrThrow("current_candidate"))
    )

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
