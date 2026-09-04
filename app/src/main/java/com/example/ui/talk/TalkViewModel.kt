package com.example.ui.talk

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Character
import com.example.data.model.Chat
import com.example.data.model.Message
import com.example.data.model.MessageRole
import com.example.data.repository.CharacterViewMode
import com.example.data.repository.TalkRepository
import com.example.engine.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class TalkNavDestination {
    object CharacterList : TalkNavDestination()
    data class CharacterEditor(val character: Character?) : TalkNavDestination()
    data class ChatList(val character: Character) : TalkNavDestination()
    data class ChatRoom(val character: Character, val chat: Chat) : TalkNavDestination()
}

interface LlmStreamRunner {
    fun isModelLoaded(): Boolean
    suspend fun runStreamingInference(
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        typicalP: Float,
        repetitionPenalty: Float,
        penaltyLastN: Int,
        seed: Long,
        enableThinking: Boolean,
        onToken: (String) -> Unit,
        onTtft: ((Double) -> Unit)? = null,
        onMetrics: ((TalkDebugMetrics) -> Unit)? = null
    ): String
}

class TalkViewModel(
    context: Context,
    private val llmRunner: LlmStreamRunner
) : ViewModel() {

    private val repository = TalkRepository(context)

    val viewMode: StateFlow<CharacterViewMode> = repository.viewMode
    val characters: StateFlow<List<Character>> = repository.characters
    val lastMessages: StateFlow<Map<String, Message>> = repository.lastMessages

    private val _navDestination = MutableStateFlow<TalkNavDestination>(TalkNavDestination.CharacterList)
    val navDestination: StateFlow<TalkNavDestination> = _navDestination.asStateFlow()

    private val _currentChats = MutableStateFlow<List<Chat>>(emptyList())
    val currentChats: StateFlow<List<Chat>> = _currentChats.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<Message>>(emptyList())
    val currentMessages: StateFlow<List<Message>> = _currentMessages.asStateFlow()

    // Streaming state
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingMessageId = MutableStateFlow<String?>(null)
    val streamingMessageId: StateFlow<String?> = _streamingMessageId.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // Debug Metrics state
    private val _debugMetrics = MutableStateFlow<TalkDebugMetrics?>(null)
    val debugMetrics: StateFlow<TalkDebugMetrics?> = _debugMetrics.asStateFlow()

    // Deleted messages backup for Undo (within 10s)
    private var deletedMessagesBackup = mutableListOf<Message>()
    private var undoJob: Job? = null

    fun toggleViewMode() {
        val next = if (viewMode.value == CharacterViewMode.LIST) CharacterViewMode.CARD else CharacterViewMode.LIST
        repository.setViewMode(next)
    }

    // ==================== Navigation & Character Operations ====================

    fun navigateToCharacterList() {
        _navDestination.value = TalkNavDestination.CharacterList
        repository.refreshCharacters()
    }

    fun openNewCharacter() {
        _navDestination.value = TalkNavDestination.CharacterEditor(null)
    }

    fun openEditCharacter(character: Character) {
        _navDestination.value = TalkNavDestination.CharacterEditor(character)
    }

    fun saveCharacter(character: Character, imageUri: Uri?) {
        viewModelScope.launch {
            var iconPath = character.iconUri
            if (imageUri != null) {
                iconPath = repository.saveAvatarFromUri(imageUri)
            }
            val finalChar = character.copy(iconUri = iconPath)
            repository.saveCharacter(finalChar)
            _navDestination.value = TalkNavDestination.CharacterList
        }
    }

    fun deleteCharacter(character: Character) {
        viewModelScope.launch {
            repository.deleteCharacter(character.id)
        }
    }

    fun toggleFavorite(character: Character) {
        viewModelScope.launch {
            repository.toggleFavorite(character.id, !character.isFavorite)
        }
    }

    fun importCharacter(character: Character, overwriteExisting: Boolean, imageUri: Uri?) {
        viewModelScope.launch {
            var iconPath: String? = null
            if (imageUri != null) {
                iconPath = repository.saveAvatarFromUri(imageUri)
            }
            var targetChar = character.copy(iconUri = iconPath)

            if (overwriteExisting) {
                val existing = repository.getCharacterByName(character.name)
                if (existing != null) {
                    targetChar = targetChar.copy(id = existing.id)
                }
            }
            repository.saveCharacter(targetChar)

            // Auto create new chat and open conversation
            val newChat = createChatInternal(targetChar)
            openChatRoom(targetChar, newChat)
        }
    }

    // Select character from list
    fun selectCharacter(character: Character) {
        viewModelScope.launch {
            repository.updateCharacterLastUsed(character.id)
            val chats = repository.getChatsForCharacter(character.id)
            if (chats.isEmpty()) {
                // 仕様25: Chatが0件の場合、Chat一覧を表示せずNew Chatから直接Chat画面へ
                val newChat = createChatInternal(character)
                openChatRoom(character, newChat)
            } else {
                _currentChats.value = chats
                _navDestination.value = TalkNavDestination.ChatList(character)
            }
        }
    }

    // ==================== Chat Operations ====================

    fun createNewChat(character: Character) {
        viewModelScope.launch {
            val chat = createChatInternal(character)
            openChatRoom(character, chat)
        }
    }

    private suspend fun createChatInternal(character: Character): Chat {
        val chat = Chat(
            characterId = character.id,
            title = "New Chat"
        )
        repository.saveChat(chat)
        // 初期の「最初の文章」があればメッセージとして投入
        if (character.firstMessage.isNotBlank()) {
            val firstMsg = Message(
                chatId = chat.id,
                role = MessageRole.CHARACTER,
                content = character.firstMessage,
                candidates = listOf(character.firstMessage),
                selectedCandidateIndex = 0
            )
            repository.saveMessage(firstMsg)
        }
        return chat
    }

    fun openChatRoom(character: Character, chat: Chat) {
        viewModelScope.launch {
            _navDestination.value = TalkNavDestination.ChatRoom(character, chat)
            loadMessages(chat.id)
        }
    }

    private fun loadMessages(chatId: String) {
        _currentMessages.value = repository.getMessagesForChat(chatId)
    }

    fun renameChat(chat: Chat, newTitle: String) {
        viewModelScope.launch {
            repository.updateChatTitle(chat.id, newTitle)
            val char = repository.getCharacterById(chat.characterId) ?: return@launch
            _currentChats.value = repository.getChatsForCharacter(char.id)
            if (_navDestination.value is TalkNavDestination.ChatRoom) {
                _navDestination.value = TalkNavDestination.ChatRoom(char, chat.copy(title = newTitle))
            }
        }
    }

    fun deleteChat(chat: Chat) {
        viewModelScope.launch {
            repository.deleteChat(chat.id)
            val char = repository.getCharacterById(chat.characterId)
            if (char != null) {
                val remaining = repository.getChatsForCharacter(char.id)
                _currentChats.value = remaining
                if (_navDestination.value is TalkNavDestination.ChatRoom) {
                    if (remaining.isEmpty()) {
                        _navDestination.value = TalkNavDestination.CharacterList
                    } else {
                        _navDestination.value = TalkNavDestination.ChatList(char)
                    }
                }
            }
        }
    }

    fun saveChatSettings(updatedChat: Chat) {
        viewModelScope.launch {
            repository.saveChat(updatedChat)
            val char = repository.getCharacterById(updatedChat.characterId) ?: return@launch
            _navDestination.value = TalkNavDestination.ChatRoom(char, updatedChat)
        }
    }

    // ==================== Message & Streaming Operations ====================

    fun sendMessage(character: Character, chat: Chat, userInput: String) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val userMsg = Message(
                chatId = chat.id,
                role = MessageRole.USER,
                content = userInput
            )
            repository.saveMessage(userMsg)
            loadMessages(chat.id)

            // Trigger Character LLM Generation
            startCharacterInference(character, chat, userInput, existingCharMessage = null)
        }
    }

    private fun startCharacterInference(
        character: Character,
        chat: Chat,
        userInput: String,
        existingCharMessage: Message?
    ) {
        viewModelScope.launch {
            if (!llmRunner.isModelLoaded()) {
                val errorMsg = Message(
                    chatId = chat.id,
                    role = MessageRole.CHARACTER,
                    content = "モデルが読み込まれていません。AI > Model から GGUF モデルをロードしてください。",
                    candidates = listOf("モデルが読み込まれていません。AI > Model から GGUF モデルをロードしてください。")
                )
                repository.saveMessage(errorMsg)
                loadMessages(chat.id)
                return@launch
            }

            _isStreaming.value = true
            _streamingText.value = ""
            _debugMetrics.value = TalkDebugMetrics(isGenerating = true)

            // Target message placeholder
            val targetMessage = if (existingCharMessage != null) {
                existingCharMessage
            } else {
                val newCharMsg = Message(
                    chatId = chat.id,
                    role = MessageRole.CHARACTER,
                    content = "",
                    candidates = emptyList()
                )
                repository.saveMessage(newCharMsg)
                loadMessages(chat.id)
                newCharMsg
            }
            _streamingMessageId.value = targetMessage.id

            // Build Prompt
            val history = repository.getMessagesForChat(chat.id).filter { it.id != targetMessage.id }
            val prompt = PromptBuilder.buildPrompt(character, history, userInput)

            val textAccumulator = StringBuilder()
            val finalResult = withContext(Dispatchers.Default) {
                llmRunner.runStreamingInference(
                    prompt = prompt,
                    temperature = chat.temperature,
                    topK = chat.topK,
                    topP = chat.topP,
                    minP = chat.minP,
                    typicalP = chat.typicalP,
                    repetitionPenalty = chat.repetitionPenalty,
                    penaltyLastN = chat.penaltyLastN,
                    seed = chat.seed,
                    enableThinking = false, // Talkでは必ずThinking OFF
                    onToken = { token ->
                        textAccumulator.append(token)
                        _streamingText.value = textAccumulator.toString()
                    },
                    onTtft = { ttft ->
                        _debugMetrics.value = _debugMetrics.value?.copy(ttftMs = ttft) ?: TalkDebugMetrics(ttftMs = ttft, isGenerating = true)
                    },
                    onMetrics = { metrics ->
                        _debugMetrics.value = metrics
                    }
                )
            }

            if (finalResult.startsWith("ERROR:")) {
                _debugMetrics.value = TalkDebugMetrics(isGenerating = false)
            }

            // 短時間待って確定 (要件41)
            delay(150)

            val generatedAnswer = finalResult.ifBlank { textAccumulator.toString() }

            // Candidate management (最大3個, 要件39)
            val existingCandidates = targetMessage.candidates.toMutableList()
            if (existingCandidates.isEmpty()) {
                existingCandidates.add(generatedAnswer)
            } else {
                if (existingCandidates.size >= 3) {
                    existingCandidates.removeAt(0) // 4個目で最古を削除
                }
                existingCandidates.add(generatedAnswer)
            }

            val updatedMessage = targetMessage.copy(
                content = generatedAnswer,
                candidates = existingCandidates,
                selectedCandidateIndex = existingCandidates.size - 1,
                timestamp = System.currentTimeMillis()
            )

            repository.saveMessage(updatedMessage)
            _isStreaming.value = false
            _streamingMessageId.value = null
            _streamingText.value = ""
            _debugMetrics.value = _debugMetrics.value?.copy(isGenerating = false)
            loadMessages(chat.id)
        }
    }

    // 再生成 (要件38)
    fun regenerateCharacterMessage(character: Character, chat: Chat, message: Message) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val allMessages = repository.getMessagesForChat(chat.id)
            val msgIndex = allMessages.indexOfFirst { it.id == message.id }
            val previousUserMsg = if (msgIndex > 0) {
                allMessages.subList(0, msgIndex).lastOrNull { it.role == MessageRole.USER }
            } else null

            val userText = previousUserMsg?.content ?: "こんにちは"
            startCharacterInference(character, chat, userText, existingCharMessage = message)
        }
    }

    // Candidate 切り替え (要件39)
    fun changeCandidate(message: Message, newIndex: Int) {
        viewModelScope.launch {
            if (newIndex in message.candidates.indices) {
                val updated = message.copy(
                    selectedCandidateIndex = newIndex,
                    content = message.candidates[newIndex]
                )
                repository.saveMessage(updated)
                loadMessages(message.chatId)
            }
        }
    }

    // User メッセージ編集 (要件37)
    fun editUserMessageAndRegenerate(character: Character, chat: Chat, message: Message, newContent: String) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            // 1. そのメッセージ以降の会話を削除
            repository.deleteMessagesFromTimestamp(chat.id, message.timestamp)

            // 2. 編集後Userメッセージを保存
            val updatedUserMsg = message.copy(content = newContent, timestamp = System.currentTimeMillis())
            repository.saveMessage(updatedUserMsg)
            loadMessages(chat.id)

            // 3. 編集後内容から再生成（回答候補も1/1から開始）
            startCharacterInference(character, chat, newContent, existingCharMessage = null)
        }
    }

    // Character メッセージ削除 (要件36)
    fun deleteCharacterMessageWithUndo(chat: Chat, message: Message) {
        viewModelScope.launch {
            val all = repository.getMessagesForChat(chat.id)
            val targetIdx = all.indexOfFirst { it.id == message.id }
            if (targetIdx >= 0) {
                deletedMessagesBackup = all.subList(targetIdx, all.size).toMutableList()
                repository.deleteMessagesFromTimestamp(chat.id, message.timestamp)
                loadMessages(chat.id)

                // 10秒タイマー後に完全確定
                undoJob?.cancel()
                undoJob = viewModelScope.launch {
                    delay(10000)
                    deletedMessagesBackup.clear()
                }
            }
        }
    }

    // Undo 復元 (要件36)
    fun undoDeleteMessages(chatId: String) {
        viewModelScope.launch {
            undoJob?.cancel()
            for (msg in deletedMessagesBackup) {
                repository.saveMessage(msg)
            }
            deletedMessagesBackup.clear()
            loadMessages(chatId)
        }
    }
}
