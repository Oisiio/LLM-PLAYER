package com.example.talk

/** Persistent Talk domain models. IDs are opaque database IDs and are never derived from names. */
data class Character(
    val id: Long = 0L,
    val name: String,
    val iconUri: String? = null,
    val personality: String = "",
    val style: String = "",
    val systemPrompt: String = "",
    val firstMessage: String = "",
    val scenario: String = "",
    val exampleDialogue: String = "",
    val favorite: Boolean = false,
    val lastUsedAt: Long = 0L
)

data class Chat(
    val id: Long = 0L,
    val characterId: Long,
    val name: String = "New Chat",
    val lastMessage: String = "",
    val lastUsedAt: Long = 0L
)

enum class MessageRole { USER, CHARACTER }

data class Message(
    val id: Long = 0L,
    val chatId: Long,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val candidate1: String? = null,
    val candidate2: String? = null,
    val candidate3: String? = null,
    val currentCandidate: Int = 1
)
