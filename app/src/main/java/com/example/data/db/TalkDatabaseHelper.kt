package com.example.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject

class TalkDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "llm_player_talk.db"
        const val DATABASE_VERSION = 1

        // Table Characters
        const val TABLE_CHARACTERS = "characters"
        const val COL_CHAR_ID = "id"
        const val COL_CHAR_NAME = "name"
        const val COL_CHAR_ICON_URI = "icon_uri"
        const val COL_CHAR_PERSONALITY = "personality_json"
        const val COL_CHAR_STYLE = "style_json"
        const val COL_CHAR_SYSTEM_PROMPT = "system_prompt_json"
        const val COL_CHAR_FIRST_MESSAGE = "first_message"
        const val COL_CHAR_SCENARIO = "scenario_json"
        const val COL_CHAR_EXAMPLE_DIALOGUE = "example_dialogue_json"
        const val COL_CHAR_IS_FAVORITE = "is_favorite"
        const val COL_CHAR_LAST_USED_AT = "last_used_at"

        // Table Chats
        const val TABLE_CHATS = "chats"
        const val COL_CHAT_ID = "id"
        const val COL_CHAT_CHAR_ID = "character_id"
        const val COL_CHAT_TITLE = "title"
        const val COL_CHAT_TEMPERATURE = "temperature"
        const val COL_CHAT_TOP_K = "top_k"
        const val COL_CHAT_TOP_P = "top_p"
        const val COL_CHAT_MIN_P = "min_p"
        const val COL_CHAT_TYPICAL_P = "typical_p"
        const val COL_CHAT_REPETITION_PENALTY = "repetition_penalty"
        const val COL_CHAT_PENALTY_LAST_N = "penalty_last_n"
        const val COL_CHAT_SEED = "seed"
        const val COL_CHAT_CREATED_AT = "created_at"
        const val COL_CHAT_LAST_USED_AT = "last_used_at"

        // Table Messages
        const val TABLE_MESSAGES = "messages"
        const val COL_MSG_ID = "id"
        const val COL_MSG_CHAT_ID = "chat_id"
        const val COL_MSG_ROLE = "role"
        const val COL_MSG_CONTENT = "content"
        const val COL_MSG_TIMESTAMP = "timestamp"
        const val COL_MSG_CANDIDATES = "candidates_json"
        const val COL_MSG_SELECTED_CANDIDATE = "selected_candidate_index"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CHARACTERS (
                $COL_CHAR_ID TEXT PRIMARY KEY,
                $COL_CHAR_NAME TEXT NOT NULL,
                $COL_CHAR_ICON_URI TEXT,
                $COL_CHAR_PERSONALITY TEXT,
                $COL_CHAR_STYLE TEXT,
                $COL_CHAR_SYSTEM_PROMPT TEXT,
                $COL_CHAR_FIRST_MESSAGE TEXT,
                $COL_CHAR_SCENARIO TEXT,
                $COL_CHAR_EXAMPLE_DIALOGUE TEXT,
                $COL_CHAR_IS_FAVORITE INTEGER DEFAULT 0,
                $COL_CHAR_LAST_USED_AT INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CHATS (
                $COL_CHAT_ID TEXT PRIMARY KEY,
                $COL_CHAT_CHAR_ID TEXT NOT NULL,
                $COL_CHAT_TITLE TEXT NOT NULL,
                $COL_CHAT_TEMPERATURE REAL DEFAULT 0.7,
                $COL_CHAT_TOP_K INTEGER DEFAULT 40,
                $COL_CHAT_TOP_P REAL DEFAULT 0.9,
                $COL_CHAT_MIN_P REAL DEFAULT 0.0,
                $COL_CHAT_TYPICAL_P REAL DEFAULT 1.0,
                $COL_CHAT_REPETITION_PENALTY REAL DEFAULT 1.1,
                $COL_CHAT_PENALTY_LAST_N INTEGER DEFAULT 64,
                $COL_CHAT_SEED INTEGER DEFAULT 12345,
                $COL_CHAT_CREATED_AT INTEGER,
                $COL_CHAT_LAST_USED_AT INTEGER,
                FOREIGN KEY($COL_CHAT_CHAR_ID) REFERENCES $TABLE_CHARACTERS($COL_CHAR_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_CHAT_ID TEXT NOT NULL,
                $COL_MSG_ROLE TEXT NOT NULL,
                $COL_MSG_CONTENT TEXT NOT NULL,
                $COL_MSG_TIMESTAMP INTEGER,
                $COL_MSG_CANDIDATES TEXT,
                $COL_MSG_SELECTED_CANDIDATE INTEGER DEFAULT 0,
                FOREIGN KEY($COL_MSG_CHAT_ID) REFERENCES $TABLE_CHATS($COL_CHAT_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Add index for faster message and chat queries
        db.execSQL("CREATE INDEX idx_chats_char_id ON $TABLE_CHATS($COL_CHAT_CHAR_ID)")
        db.execSQL("CREATE INDEX idx_messages_chat_id ON $TABLE_MESSAGES($COL_MSG_CHAT_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe migration strategy if needed in future
    }

    // ==================== Characters CRUD ====================

    fun insertOrUpdateCharacter(character: Character) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAR_ID, character.id)
            put(COL_CHAR_NAME, character.name)
            put(COL_CHAR_ICON_URI, character.iconUri)
            put(COL_CHAR_PERSONALITY, personalityToJson(character.personality))
            put(COL_CHAR_STYLE, styleToJson(character.style))
            put(COL_CHAR_SYSTEM_PROMPT, systemPromptToJson(character.systemPrompt))
            put(COL_CHAR_FIRST_MESSAGE, character.firstMessage)
            put(COL_CHAR_SCENARIO, scenarioToJson(character.scenario))
            put(COL_CHAR_EXAMPLE_DIALOGUE, exampleDialogueToJson(character.exampleDialogue))
            put(COL_CHAR_IS_FAVORITE, if (character.isFavorite) 1 else 0)
            put(COL_CHAR_LAST_USED_AT, character.lastUsedAt)
        }
        db.insertWithOnConflict(TABLE_CHARACTERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllCharacters(): List<Character> {
        val db = readableDatabase
        val list = mutableListOf<Character>()
        val cursor = db.query(
            TABLE_CHARACTERS, null, null, null, null, null,
            "$COL_CHAR_IS_FAVORITE DESC, $COL_CHAR_LAST_USED_AT DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToCharacter(c))
            }
        }
        return list
    }

    fun getCharacterById(id: String): Character? {
        val db = readableDatabase
        val cursor = db.query(TABLE_CHARACTERS, null, "$COL_CHAR_ID = ?", arrayOf(id), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToCharacter(c)
        }
        return null
    }

    fun getCharacterByName(name: String): Character? {
        val db = readableDatabase
        val cursor = db.query(TABLE_CHARACTERS, null, "$COL_CHAR_NAME = ?", arrayOf(name), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToCharacter(c)
        }
        return null
    }

    fun deleteCharacter(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Find all chats for this character
            val chatCursor = db.query(TABLE_CHATS, arrayOf(COL_CHAT_ID), "$COL_CHAT_CHAR_ID = ?", arrayOf(id), null, null, null)
            val chatIds = mutableListOf<String>()
            chatCursor.use { c ->
                while (c.moveToNext()) {
                    chatIds.add(c.getString(0))
                }
            }
            // Delete messages of these chats
            for (cId in chatIds) {
                db.delete(TABLE_MESSAGES, "$COL_MSG_CHAT_ID = ?", arrayOf(cId))
            }
            // Delete chats
            db.delete(TABLE_CHATS, "$COL_CHAT_CHAR_ID = ?", arrayOf(id))
            // Delete character
            db.delete(TABLE_CHARACTERS, "$COL_CHAR_ID = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateCharacterFavorite(id: String, isFavorite: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAR_IS_FAVORITE, if (isFavorite) 1 else 0)
        }
        db.update(TABLE_CHARACTERS, cv, "$COL_CHAR_ID = ?", arrayOf(id))
    }

    fun updateCharacterLastUsed(id: String, timestamp: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAR_LAST_USED_AT, timestamp)
        }
        db.update(TABLE_CHARACTERS, cv, "$COL_CHAR_ID = ?", arrayOf(id))
    }

    // ==================== Chats CRUD ====================

    fun insertOrUpdateChat(chat: Chat) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAT_ID, chat.id)
            put(COL_CHAT_CHAR_ID, chat.characterId)
            put(COL_CHAT_TITLE, chat.title)
            put(COL_CHAT_TEMPERATURE, chat.temperature)
            put(COL_CHAT_TOP_K, chat.topK)
            put(COL_CHAT_TOP_P, chat.topP)
            put(COL_CHAT_MIN_P, chat.minP)
            put(COL_CHAT_TYPICAL_P, chat.typicalP)
            put(COL_CHAT_REPETITION_PENALTY, chat.repetitionPenalty)
            put(COL_CHAT_PENALTY_LAST_N, chat.penaltyLastN)
            put(COL_CHAT_SEED, chat.seed)
            put(COL_CHAT_CREATED_AT, chat.createdAt)
            put(COL_CHAT_LAST_USED_AT, chat.lastUsedAt)
        }
        db.insertWithOnConflict(TABLE_CHATS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getChatsForCharacter(characterId: String): List<Chat> {
        val db = readableDatabase
        val list = mutableListOf<Chat>()
        val cursor = db.query(
            TABLE_CHATS, null, "$COL_CHAT_CHAR_ID = ?", arrayOf(characterId),
            null, null, "$COL_CHAT_LAST_USED_AT DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToChat(c))
            }
        }
        return list
    }

    fun getChatById(id: String): Chat? {
        val db = readableDatabase
        val cursor = db.query(TABLE_CHATS, null, "$COL_CHAT_ID = ?", arrayOf(id), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToChat(c)
        }
        return null
    }

    fun deleteChat(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, "$COL_MSG_CHAT_ID = ?", arrayOf(id))
            db.delete(TABLE_CHATS, "$COL_CHAT_ID = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateChatTitle(id: String, newTitle: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAT_TITLE, newTitle.take(30))
            put(COL_CHAT_LAST_USED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_CHATS, cv, "$COL_CHAT_ID = ?", arrayOf(id))
    }

    fun updateChatLastUsed(id: String, timestamp: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_CHAT_LAST_USED_AT, timestamp)
        }
        db.update(TABLE_CHATS, cv, "$COL_CHAT_ID = ?", arrayOf(id))
    }

    fun getLastMessageForChat(chatId: String): Message? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MESSAGES, null, "$COL_MSG_CHAT_ID = ?", arrayOf(chatId),
            null, null, "$COL_MSG_TIMESTAMP DESC", "1"
        )
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToMessage(c)
        }
        return null
    }

    fun getLastMessageForCharacter(characterId: String): Message? {
        val db = readableDatabase
        val query = """
            SELECT m.* FROM $TABLE_MESSAGES m
            INNER JOIN $TABLE_CHATS c ON m.$COL_MSG_CHAT_ID = c.$COL_CHAT_ID
            WHERE c.$COL_CHAT_CHAR_ID = ?
            ORDER BY m.$COL_MSG_TIMESTAMP DESC LIMIT 1
        """.trimIndent()
        val cursor = db.rawQuery(query, arrayOf(characterId))
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToMessage(c)
        }
        return null
    }

    // ==================== Messages CRUD ====================

    fun insertOrUpdateMessage(message: Message) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_MSG_CHAT_ID, message.chatId)
            put(COL_MSG_ROLE, message.role.name)
            put(COL_MSG_CONTENT, message.content)
            put(COL_MSG_TIMESTAMP, message.timestamp)
            put(COL_MSG_CANDIDATES, candidatesToJson(message.candidates))
            put(COL_MSG_SELECTED_CANDIDATE, message.selectedCandidateIndex)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMessagesForChat(chatId: String): List<Message> {
        val db = readableDatabase
        val list = mutableListOf<Message>()
        val cursor = db.query(
            TABLE_MESSAGES, null, "$COL_MSG_CHAT_ID = ?", arrayOf(chatId),
            null, null, "$COL_MSG_TIMESTAMP ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMessage(c))
            }
        }
        return list
    }

    fun deleteMessage(id: String) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "$COL_MSG_ID = ?", arrayOf(id))
    }

    fun deleteMessagesFromTimestamp(chatId: String, timestamp: Long) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "$COL_MSG_CHAT_ID = ? AND $COL_MSG_TIMESTAMP >= ?", arrayOf(chatId, timestamp.toString()))
    }

    // ==================== Helpers / Converters ====================

    private fun cursorToCharacter(c: Cursor): Character {
        return Character(
            id = c.getString(c.getColumnIndexOrThrow(COL_CHAR_ID)),
            name = c.getString(c.getColumnIndexOrThrow(COL_CHAR_NAME)),
            iconUri = c.getString(c.getColumnIndexOrThrow(COL_CHAR_ICON_URI)),
            personality = jsonToPersonality(c.getString(c.getColumnIndexOrThrow(COL_CHAR_PERSONALITY))),
            style = jsonToStyle(c.getString(c.getColumnIndexOrThrow(COL_CHAR_STYLE))),
            systemPrompt = jsonToSystemPrompt(c.getString(c.getColumnIndexOrThrow(COL_CHAR_SYSTEM_PROMPT))),
            firstMessage = c.getString(c.getColumnIndexOrThrow(COL_CHAR_FIRST_MESSAGE)) ?: "",
            scenario = jsonToScenario(c.getString(c.getColumnIndexOrThrow(COL_CHAR_SCENARIO))),
            exampleDialogue = jsonToExampleDialogue(c.getString(c.getColumnIndexOrThrow(COL_CHAR_EXAMPLE_DIALOGUE))),
            isFavorite = c.getInt(c.getColumnIndexOrThrow(COL_CHAR_IS_FAVORITE)) == 1,
            lastUsedAt = c.getLong(c.getColumnIndexOrThrow(COL_CHAR_LAST_USED_AT))
        )
    }

    private fun cursorToChat(c: Cursor): Chat {
        return Chat(
            id = c.getString(c.getColumnIndexOrThrow(COL_CHAT_ID)),
            characterId = c.getString(c.getColumnIndexOrThrow(COL_CHAT_CHAR_ID)),
            title = c.getString(c.getColumnIndexOrThrow(COL_CHAT_TITLE)),
            temperature = c.getFloat(c.getColumnIndexOrThrow(COL_CHAT_TEMPERATURE)),
            topK = c.getInt(c.getColumnIndexOrThrow(COL_CHAT_TOP_K)),
            topP = c.getFloat(c.getColumnIndexOrThrow(COL_CHAT_TOP_P)),
            minP = c.getFloat(c.getColumnIndexOrThrow(COL_CHAT_MIN_P)),
            typicalP = c.getFloat(c.getColumnIndexOrThrow(COL_CHAT_TYPICALP)),
            repetitionPenalty = c.getFloat(c.getColumnIndexOrThrow(COL_CHAT_REPETITION_PENALTY)),
            penaltyLastN = c.getInt(c.getColumnIndexOrThrow(COL_CHAT_PENALTY_LAST_N)),
            seed = c.getLong(c.getColumnIndexOrThrow(COL_CHAT_SEED)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CHAT_CREATED_AT)),
            lastUsedAt = c.getLong(c.getColumnIndexOrThrow(COL_CHAT_LAST_USED_AT))
        )
    }

    private fun cursorToMessage(c: Cursor): Message {
        return Message(
            id = c.getString(c.getColumnIndexOrThrow(COL_MSG_ID)),
            chatId = c.getString(c.getColumnIndexOrThrow(COL_MSG_CHAT_ID)),
            role = MessageRole.valueOf(c.getString(c.getColumnIndexOrThrow(COL_MSG_ROLE))),
            content = c.getString(c.getColumnIndexOrThrow(COL_MSG_CONTENT)),
            timestamp = c.getLong(c.getColumnIndexOrThrow(COL_MSG_TIMESTAMP)),
            candidates = jsonToCandidates(c.getString(c.getColumnIndexOrThrow(COL_MSG_CANDIDATES))),
            selectedCandidateIndex = c.getInt(c.getColumnIndexOrThrow(COL_MSG_SELECTED_CANDIDATE))
        )
    }

    private val COL_CHAT_TYPICALP = COL_CHAT_TYPICAL_P

    private fun personalityToJson(p: PersonalityData): String {
        val o = JSONObject()
        val a = JSONArray()
        p.presets.forEach { a.put(it) }
        o.put("presets", a)
        o.put("custom", p.custom)
        return o.toString()
    }

    private fun jsonToPersonality(s: String?): PersonalityData {
        if (s.isNullOrBlank()) return PersonalityData()
        return try {
            val o = JSONObject(s)
            val a = o.optJSONArray("presets")
            val list = mutableListOf<String>()
            if (a != null) {
                for (i in 0 until a.length()) list.add(a.optString(i))
            }
            PersonalityData(list, o.optString("custom", ""))
        } catch (e: Exception) { PersonalityData() }
    }

    private fun styleToJson(st: StyleData): String {
        val o = JSONObject()
        val a = JSONArray()
        st.presets.forEach { a.put(it) }
        o.put("presets", a)
        o.put("custom", st.custom)
        return o.toString()
    }

    private fun jsonToStyle(s: String?): StyleData {
        if (s.isNullOrBlank()) return StyleData()
        return try {
            val o = JSONObject(s)
            val a = o.optJSONArray("presets")
            val list = mutableListOf<String>()
            if (a != null) {
                for (i in 0 until a.length()) list.add(a.optString(i))
            }
            StyleData(list, o.optString("custom", ""))
        } catch (e: Exception) { StyleData() }
    }

    private fun systemPromptToJson(sp: SystemPromptData): String {
        val o = JSONObject()
        if (sp.template != null) o.put("template", sp.template) else o.put("template", JSONObject.NULL)
        o.put("custom", sp.custom)
        return o.toString()
    }

    private fun jsonToSystemPrompt(s: String?): SystemPromptData {
        if (s.isNullOrBlank()) return SystemPromptData()
        return try {
            val o = JSONObject(s)
            val t = if (o.isNull("template")) null else o.optString("template", null)
            SystemPromptData(t, o.optString("custom", ""))
        } catch (e: Exception) { SystemPromptData() }
    }

    private fun scenarioToJson(sc: ScenarioData): String {
        val o = JSONObject()
        if (sc.template != null) o.put("template", sc.template) else o.put("template", JSONObject.NULL)
        o.put("content", sc.content)
        return o.toString()
    }

    private fun jsonToScenario(s: String?): ScenarioData {
        if (s.isNullOrBlank()) return ScenarioData()
        return try {
            val o = JSONObject(s)
            val t = if (o.isNull("template")) null else o.optString("template", null)
            ScenarioData(t, o.optString("content", ""))
        } catch (e: Exception) { ScenarioData() }
    }

    private fun exampleDialogueToJson(ed: ExampleDialogueData): String {
        val o = JSONObject()
        val a = JSONArray()
        ed.structured.forEach {
            val p = JSONObject()
            p.put("user", it.user)
            p.put("character", it.character)
            a.put(p)
        }
        o.put("structured", a)
        o.put("freeform", ed.freeform)
        return o.toString()
    }

    private fun jsonToExampleDialogue(s: String?): ExampleDialogueData {
        if (s.isNullOrBlank()) return ExampleDialogueData()
        return try {
            val o = JSONObject(s)
            val a = o.optJSONArray("structured")
            val list = mutableListOf<DialoguePair>()
            if (a != null) {
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i)
                    if (p != null) {
                        list.add(DialoguePair(p.optString("user", ""), p.optString("character", "")))
                    }
                }
            }
            ExampleDialogueData(list, o.optString("freeform", ""))
        } catch (e: Exception) { ExampleDialogueData() }
    }

    private fun candidatesToJson(cands: List<String>): String {
        val a = JSONArray()
        cands.forEach { a.put(it) }
        return a.toString()
    }

    private fun jsonToCandidates(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            val list = mutableListOf<String>()
            for (i in 0 until a.length()) {
                list.add(a.optString(i))
            }
            list
        } catch (e: Exception) { emptyList() }
    }
}
