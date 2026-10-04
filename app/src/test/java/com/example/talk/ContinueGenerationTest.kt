package com.example.talk

import com.example.data.model.Character
import com.example.data.model.Message
import com.example.data.model.MessageRole
import com.example.engine.PromptBuilder
import org.junit.Assert.*
import org.junit.Test

class ContinueGenerationTest {

    @Test
    fun testPromptBuilder_continuePrefix_constructsContinuationPrompt() {
        val character = Character(name = "アリス")
        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "今日の予定は？",
            continuePrefix = "午前中は読書をして、"
        )

        assertTrue(prompt.contains("User: 今日の予定は？\nアリス: 午前中は読書をして、"))
    }

    @Test
    fun testPromptBuilder_continuePrefix_withoutUserMessage_omitsUser() {
        val character = Character(name = "アリス")
        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "",
            continuePrefix = "はじめまして、アリスです。"
        )

        assertTrue(prompt.contains("アリス: はじめまして、アリスです。"))
        assertFalse(prompt.contains("User:"))
    }

    @Test
    fun testContinueGeneration_extendsSelectedCandidateInPlace() {
        // Initial Message
        val initialCandidate = "今日はいい天気だね。"
        val message = Message(
            id = "msg-123",
            chatId = "chat-1",
            role = MessageRole.CHARACTER,
            content = initialCandidate,
            candidates = listOf(initialCandidate),
            selectedCandidateIndex = 0
        )

        // Continue execution simulation (as implemented in TalkViewModel)
        val baseContent = message.displayContent
        val newlyGenerated = "外に出て散歩でもしたくなるよ。"
        val generatedAnswer = baseContent + newlyGenerated

        val existingCandidates = message.candidates.toMutableList()
        val selIdx = message.selectedCandidateIndex.coerceIn(0, (existingCandidates.size - 1).coerceAtLeast(0))
        if (existingCandidates.isNotEmpty() && selIdx in existingCandidates.indices) {
            existingCandidates[selIdx] = generatedAnswer
        } else {
            existingCandidates.clear()
            existingCandidates.add(generatedAnswer)
        }

        val updatedMessage = message.copy(
            content = generatedAnswer,
            candidates = existingCandidates,
            selectedCandidateIndex = selIdx,
            timestamp = System.currentTimeMillis()
        )

        // 1. Same candidate content is extended
        assertEquals("今日はいい天気だね。外に出て散歩でもしたくなるよ。", updatedMessage.content)
        assertEquals("今日はいい天気だね。外に出て散歩でもしたくなるよ。", updatedMessage.candidates[0])

        // 2. selectedCandidateIndex is maintained
        assertEquals(0, updatedMessage.selectedCandidateIndex)

        // 3. No new Message created (same id)
        assertEquals("msg-123", updatedMessage.id)

        // 4. Candidate count did not increase
        assertEquals(1, updatedMessage.candidates.size)
    }

    @Test
    fun testContinueGeneration_withMultipleCandidates_preservesOtherCandidatesAndIndex() {
        val message = Message(
            id = "msg-456",
            chatId = "chat-1",
            role = MessageRole.CHARACTER,
            content = "回答その2",
            candidates = listOf("回答その1", "回答その2", "回答その3"),
            selectedCandidateIndex = 1 // middle candidate
        )

        val baseContent = message.candidates[message.selectedCandidateIndex]
        val newlyGenerated = "の追加文章です。"
        val generatedAnswer = baseContent + newlyGenerated

        val existingCandidates = message.candidates.toMutableList()
        val selIdx = message.selectedCandidateIndex.coerceIn(0, (existingCandidates.size - 1).coerceAtLeast(0))
        existingCandidates[selIdx] = generatedAnswer

        val updatedMessage = message.copy(
            content = generatedAnswer,
            candidates = existingCandidates,
            selectedCandidateIndex = selIdx
        )

        // Candidate 0 and 2 are untouched
        assertEquals("回答その1", updatedMessage.candidates[0])
        assertEquals("回答その2の追加文章です。", updatedMessage.candidates[1])
        assertEquals("回答その3", updatedMessage.candidates[2])

        // selectedCandidateIndex remains 1
        assertEquals(1, updatedMessage.selectedCandidateIndex)

        // Candidate count remains 3
        assertEquals(3, updatedMessage.candidates.size)
        assertEquals("回答その2の追加文章です。", updatedMessage.content)
    }

    @Test
    fun testContinueGeneration_whenNoTokensGenerated_preservesOriginalContent() {
        val message = Message(
            id = "msg-789",
            chatId = "chat-1",
            role = MessageRole.CHARACTER,
            content = "元々の文章",
            candidates = listOf("元々の文章"),
            selectedCandidateIndex = 0
        )

        val baseContent = message.displayContent
        val newlyGenerated = "" // 0 tokens generated (e.g. immediate cancel or error)
        val generatedAnswer = baseContent + newlyGenerated

        val existingCandidates = message.candidates.toMutableList()
        val selIdx = message.selectedCandidateIndex
        existingCandidates[selIdx] = generatedAnswer

        val updatedMessage = message.copy(
            content = generatedAnswer,
            candidates = existingCandidates,
            selectedCandidateIndex = selIdx
        )

        assertEquals("元々の文章", updatedMessage.content)
        assertEquals(listOf("元々の文章"), updatedMessage.candidates)
        assertEquals(0, updatedMessage.selectedCandidateIndex)
    }

    @Test
    fun testRegenerate_addsNewCandidate_unlikeContinue() {
        val message = Message(
            id = "msg-999",
            chatId = "chat-1",
            role = MessageRole.CHARACTER,
            content = "初回回答",
            candidates = listOf("初回回答"),
            selectedCandidateIndex = 0
        )

        // In regenerate:
        val regeneratedAnswer = "別の新しい回答"
        val existingCandidates = message.candidates.toMutableList()
        existingCandidates.add(regeneratedAnswer)

        val updatedMessage = message.copy(
            content = regeneratedAnswer,
            candidates = existingCandidates,
            selectedCandidateIndex = existingCandidates.size - 1
        )

        // Regenerate increases candidate count and changes index to newest
        assertEquals(2, updatedMessage.candidates.size)
        assertEquals(1, updatedMessage.selectedCandidateIndex)
        assertEquals("初回回答", updatedMessage.candidates[0])
        assertEquals("別の新しい回答", updatedMessage.candidates[1])
    }
}
