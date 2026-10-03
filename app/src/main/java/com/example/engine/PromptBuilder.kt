package com.example.engine

import com.example.data.model.Character
import com.example.data.model.LlmDefaultSettings
import com.example.data.model.Message
import com.example.data.model.MessageRole

object PromptBuilder {

    /**
     * Context overflow を防ぐための保守的なトークン数概算（ヒューリスティック推定）。
     * ※厳密な BPE / SentencePiece Tokenizer ではなく、安全域を確保した予算管理用です。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            val block = java.lang.Character.UnicodeBlock.of(ch)
            if (block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == java.lang.Character.UnicodeBlock.HIRAGANA ||
                block == java.lang.Character.UnicodeBlock.KATAKANA ||
                block == java.lang.Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == java.lang.Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + Math.ceil(other / 3.0).toInt() + 2
    }

    fun buildPrompt(
        character: Character,
        recentMessages: List<Message>,
        newUserInput: String,
        contextSize: Int = LlmDefaultSettings.CONTEXT_SIZE,
        maxOutputTokens: Int = LlmDefaultSettings.MAX_OUTPUT_TOKENS
    ): String {
        // 1. System Prompt / Base instructions
        val systemInstruction = character.systemPrompt.custom.ifBlank {
            character.systemPrompt.template ?: "あなたは以下のキャラクターとして振る舞い、ユーザーと自然に対話してください。"
        }
        val systemSection = "[指示]\n${systemInstruction.trim()}\n\n"

        // 2. Character Definition (Name, Description, Personality, Style, Scenario)
        val charInfo = StringBuilder("[キャラクター情報]\n名前: ").append(character.name).append("\n")
        if (character.description.isNotBlank()) {
            charInfo.append("説明: ").append(character.description.trim()).append("\n")
        }

        val personalityItems = mutableListOf<String>()
        if (character.personality.presets.isNotEmpty()) {
            personalityItems.add(character.personality.presets.joinToString(", "))
        }
        if (character.personality.custom.isNotBlank()) {
            personalityItems.add(character.personality.custom.trim())
        }
        if (personalityItems.isNotEmpty()) {
            charInfo.append("性格: ").append(personalityItems.joinToString(" / ")).append("\n")
        }

        val styleItems = mutableListOf<String>()
        if (character.style.presets.isNotEmpty()) {
            styleItems.add(character.style.presets.joinToString(", "))
        }
        if (character.style.custom.isNotBlank()) {
            styleItems.add(character.style.custom.trim())
        }
        if (styleItems.isNotEmpty()) {
            charInfo.append("口調・話し方: ").append(styleItems.joinToString(" / ")).append("\n")
        }

        val scenarioText = character.scenario.content.ifBlank { character.scenario.template ?: "" }.trim()
        if (scenarioText.isNotBlank()) {
            charInfo.append("シチュエーション・背景: ").append(scenarioText).append("\n")
        }
        charInfo.append("\n")
        val charInfoSection = charInfo.toString()

        // 3. Example Dialogue
        val exSection = if (character.exampleDialogue.freeform.isNotBlank() || character.exampleDialogue.structured.isNotEmpty()) {
            val exSb = StringBuilder("[会話例]\n")
            if (character.exampleDialogue.freeform.isNotBlank()) {
                exSb.append(character.exampleDialogue.freeform.take(1000).trim()).append("\n")
            }
            if (character.exampleDialogue.structured.isNotEmpty()) {
                for (pair in character.exampleDialogue.structured.take(10)) {
                    if (pair.user.isNotBlank()) exSb.append("User: ").append(pair.user.trim()).append("\n")
                    if (pair.character.isNotBlank()) exSb.append(character.name).append(": ").append(pair.character.trim()).append("\n")
                }
            }
            exSb.append("\n").toString()
        } else ""

        // 4. Post History Instructions
        val postHistorySection = if (character.postHistoryInstructions.isNotBlank()) {
            "[追加指示]\n${character.postHistoryInstructions.trim()}\n\n"
        } else ""

        // 5. Current User message and trigger for Character reply
        val currentTurnSection = "[今回の会話]\nUser: ${newUserInput.trim()}\n${character.name}:"

        // Token budgeting for history
        val promptBudget = (contextSize - maxOutputTokens).coerceAtLeast(64)
        val fixedTokens = estimateTokens(systemSection) +
                estimateTokens(charInfoSection) +
                estimateTokens(exSection) +
                estimateTokens(postHistorySection) +
                estimateTokens(currentTurnSection) + 8

        val availableForHistory = (promptBudget - fixedTokens).coerceAtLeast(0)

        // Select history from newest to oldest up to available budget
        val selectedHistoryLines = mutableListOf<String>()
        var historyUsedTokens = 0

        // Take up to 50 most recent messages, iterate in reverse (newest first)
        val candidates = recentMessages.takeLast(50)
        for (i in candidates.indices.reversed()) {
            val msg = candidates[i]
            val content = msg.displayContent.trim()
            if (content.isEmpty()) continue
            val speaker = if (msg.role == MessageRole.USER) "User" else character.name
            val line = "$speaker: $content\n"
            val lineTokens = estimateTokens(line)
            if (historyUsedTokens + lineTokens <= availableForHistory || selectedHistoryLines.isEmpty()) {
                selectedHistoryLines.add(line)
                historyUsedTokens += lineTokens
                // Even if history exceeds slightly on the very first message, we allow at least 1 message if budget is tight
                if (historyUsedTokens >= availableForHistory) break
            } else {
                break
            }
        }

        val historySection = if (selectedHistoryLines.isNotEmpty()) {
            val sb = StringBuilder("[これまでの会話]\n")
            // Restore chronological order
            for (line in selectedHistoryLines.reversed()) {
                sb.append(line)
            }
            sb.append("\n").toString()
        } else ""

        return systemSection + charInfoSection + exSection + historySection + postHistorySection + currentTurnSection
    }
}
