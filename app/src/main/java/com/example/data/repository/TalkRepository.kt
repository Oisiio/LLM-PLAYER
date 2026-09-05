package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.db.TalkDatabaseHelper
import com.example.data.model.Character
import com.example.data.model.Chat
import com.example.data.model.LlmDefaultSettings
import com.example.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class CharacterViewMode {
    LIST, CARD
}

class TalkRepository(private val context: Context) {
    private val dbHelper = TalkDatabaseHelper(context)
    private val prefs = context.getSharedPreferences("talk_prefs", Context.MODE_PRIVATE)

    private val _viewMode = MutableStateFlow(
        if (prefs.getString("character_view_mode", "LIST") == "CARD") CharacterViewMode.CARD else CharacterViewMode.LIST
    )
    val viewMode: StateFlow<CharacterViewMode> = _viewMode.asStateFlow()

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    val characters: StateFlow<List<Character>> = _characters.asStateFlow()

    private val _lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
    val lastMessages: StateFlow<Map<String, Message>> = _lastMessages.asStateFlow()

    init {
        refreshCharacters()
    }

    fun setViewMode(mode: CharacterViewMode) {
        _viewMode.value = mode
        prefs.edit().putString("character_view_mode", mode.name).apply()
    }

    // ==================== Default Generation Settings ====================

    fun getDefaultTemperature(): Float = prefs.getFloat("default_temperature", LlmDefaultSettings.TEMPERATURE)
    fun setDefaultTemperature(value: Float) {
        prefs.edit().putFloat("default_temperature", value).apply()
    }

    fun getDefaultTopK(): Int = prefs.getInt("default_top_k", LlmDefaultSettings.TOP_K)
    fun setDefaultTopK(value: Int) {
        prefs.edit().putInt("default_top_k", value).apply()
    }

    fun getDefaultTopP(): Float = prefs.getFloat("default_top_p", LlmDefaultSettings.TOP_P)
    fun setDefaultTopP(value: Float) {
        prefs.edit().putFloat("default_top_p", value).apply()
    }

    fun refreshCharacters() {
        val list = dbHelper.getAllCharacters()
        _characters.value = list
        val lastMsgMap = mutableMapOf<String, Message>()
        for (char in list) {
            val msg = dbHelper.getLastMessageForCharacter(char.id)
            if (msg != null) {
                lastMsgMap[char.id] = msg
            }
        }
        _lastMessages.value = lastMsgMap
    }

    suspend fun saveCharacter(character: Character) = withContext(Dispatchers.IO) {
        dbHelper.insertOrUpdateCharacter(character)
        refreshCharacters()
    }

    suspend fun deleteCharacter(id: String) = withContext(Dispatchers.IO) {
        dbHelper.deleteCharacter(id)
        refreshCharacters()
    }

    suspend fun toggleFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dbHelper.updateCharacterFavorite(id, isFavorite)
        refreshCharacters()
    }

    suspend fun updateCharacterLastUsed(id: String) = withContext(Dispatchers.IO) {
        dbHelper.updateCharacterLastUsed(id)
        refreshCharacters()
    }

    fun getCharacterById(id: String): Character? {
        return dbHelper.getCharacterById(id)
    }

    fun getCharacterByName(name: String): Character? {
        return dbHelper.getCharacterByName(name)
    }

    // ==================== Chats ====================

    fun getChatsForCharacter(characterId: String): List<Chat> {
        return dbHelper.getChatsForCharacter(characterId)
    }

    fun getChatById(id: String): Chat? {
        return dbHelper.getChatById(id)
    }

    suspend fun saveChat(chat: Chat) = withContext(Dispatchers.IO) {
        dbHelper.insertOrUpdateChat(chat)
        dbHelper.updateCharacterLastUsed(chat.characterId, chat.lastUsedAt)
        refreshCharacters()
    }

    suspend fun deleteChat(id: String) = withContext(Dispatchers.IO) {
        dbHelper.deleteChat(id)
        refreshCharacters()
    }

    suspend fun updateChatTitle(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        dbHelper.updateChatTitle(id, newTitle)
    }

    fun getLastMessageForChat(chatId: String): Message? {
        return dbHelper.getLastMessageForChat(chatId)
    }

    // ==================== Messages ====================

    fun getMessagesForChat(chatId: String): List<Message> {
        return dbHelper.getMessagesForChat(chatId)
    }

    suspend fun saveMessage(message: Message) = withContext(Dispatchers.IO) {
        dbHelper.insertOrUpdateMessage(message)
        dbHelper.updateChatLastUsed(message.chatId, message.timestamp)
        val chat = dbHelper.getChatById(message.chatId)
        if (chat != null) {
            dbHelper.updateCharacterLastUsed(chat.characterId, message.timestamp)
        }
        refreshCharacters()
    }

    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        dbHelper.deleteMessage(id)
        refreshCharacters()
    }

    suspend fun deleteMessagesFromTimestamp(chatId: String, timestamp: Long) = withContext(Dispatchers.IO) {
        dbHelper.deleteMessagesFromTimestamp(chatId, timestamp)
        refreshCharacters()
    }

    // ==================== Image Avatar Helper ====================

    suspend fun saveAvatarFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val avatarsDir = File(context.filesDir, "avatars").apply { mkdirs() }
            val fileName = "avatar_${UUID.randomUUID()}.jpg"
            val destFile = File(avatarsDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return@withContext null
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
