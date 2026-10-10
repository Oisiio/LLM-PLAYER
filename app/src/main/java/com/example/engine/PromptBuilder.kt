package com.example.engine

import com.example.data.model.Character
import com.example.data.model.LlmDefaultSettings
import com.example.data.model.Message
import com.example.data.model.MessageRole
import com.example.data.model.UserPersona

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

    /**
     * Character Card V2 および標準テンプレートマクロ ({{user}}, <USER>, {{char}}, <CHAR>, <BOT>, {{user_description}}, {{user_persona}}) を
     * ユーザー設定・キャラクター設定に基づいて安全に置換します。
     */
    fun resolvePlaceholders(
        template: String,
        userName: String,
        charName: String,
        userDescription: String = "",
        userPersonaText: String = ""
    ): String {
        if (template.isEmpty()) return template
        var result = template
        if (userDescription.isNotBlank()) {
            result = result.replace("{{user_description}}", userDescription, ignoreCase = true)
        } else {
            result = result.replace("{{user_description}}", "", ignoreCase = true)
        }
        if (userPersonaText.isNotBlank()) {
            result = result.replace("{{user_persona}}", userPersonaText, ignoreCase = true)
        } else {
            result = result.replace("{{user_persona}}", "", ignoreCase = true)
        }

        val safeUserName = userName.ifBlank { "User" }
        result = result.replace("{{user}}", safeUserName, ignoreCase = true)
            .replace("<USER>", safeUserName, ignoreCase = true)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("<CHAR>", charName, ignoreCase = true)
            .replace("<BOT>", charName, ignoreCase = true)
        return result
    }

    fun buildPrompt(
        character: Character,
        recentMessages: List<Message>,
        newUserInput: String,
        contextSize: Int = LlmDefaultSettings.CONTEXT_SIZE,
        maxOutputTokens: Int = LlmDefaultSettings.MAX_OUTPUT_TOKENS,
        userPersona: UserPersona = UserPersona(),
        continuePrefix: String? = null
    ): String {
        val effectiveUserName = userPersona.name.ifBlank { "User" }.trim()
        val charName = character.name.trim()

        fun applyPlaceholders(text: String): String {
            return resolvePlaceholders(
                template = text,
                userName = effectiveUserName,
                charName = charName,
                userDescription = userPersona.description.trim(),
                userPersonaText = userPersona.persona.trim()
            )
        }

        // 1. System Prompt / Base instructions
        val rawSystemInstruction = character.systemPrompt.custom.ifBlank {
            character.systemPrompt.template ?: "あなたは以下のキャラクターとして振る舞い、ユーザーと自然に対話してください。"
        }
        val systemInstruction = applyPlaceholders(rawSystemInstruction)
        val systemSection = "[指示]\n${systemInstruction.trim()}\n\n"

        // 2. User Persona (ユーザー情報) - 未設定時はセクションごと省略、設定された項目のみ出力
        val userSection = if (!userPersona.isEmpty) {
            val userSb = StringBuilder("[ユーザー情報]\n")
            if (userPersona.name.isNotBlank()) {
                userSb.append("名前: ").append(userPersona.name.trim()).append("\n")
            }
            if (userPersona.description.isNotBlank()) {
                userSb.append("説明: ").append(userPersona.description.trim()).append("\n")
            }
            if (userPersona.persona.isNotBlank()) {
                userSb.append("ペルソナ: ").append(userPersona.persona.trim()).append("\n")
            }
            userSb.append("\n").toString()
        } else ""

        // 3. Character Definition (Name, Description, Personality, Style, Scenario)
        val charInfo = StringBuilder("[キャラクター情報]\n名前: ").append(charName).append("\n")
        if (character.description.isNotBlank()) {
            charInfo.append("説明: ").append(applyPlaceholders(character.description.trim())).append("\n")
        }

        val personalityItems = mutableListOf<String>()
        if (character.personality.presets.isNotEmpty()) {
            personalityItems.add(character.personality.presets.joinToString(", "))
        }
        if (character.personality.custom.isNotBlank()) {
            personalityItems.add(applyPlaceholders(character.personality.custom.trim()))
        }
        if (personalityItems.isNotEmpty()) {
            charInfo.append("性格: ").append(personalityItems.joinToString(" / ")).append("\n")
        }

        val styleItems = mutableListOf<String>()
        if (character.style.presets.isNotEmpty()) {
            styleItems.add(character.style.presets.joinToString(", "))
        }
        if (character.style.custom.isNotBlank()) {
            styleItems.add(applyPlaceholders(character.style.custom.trim()))
        }
        if (styleItems.isNotEmpty()) {
            charInfo.append("口調・話し方: ").append(styleItems.joinToString(" / ")).append("\n")
        }

        val scenarioText = character.scenario.content.ifBlank { character.scenario.template ?: "" }.trim()
        if (scenarioText.isNotBlank()) {
            charInfo.append("シチュエーション・背景: ").append(applyPlaceholders(scenarioText)).append("\n")
        }
        charInfo.append("\n")
        val charInfoSection = charInfo.toString()

        // 3. Example Dialogue
        val exSection = if (character.exampleDialogue.freeform.isNotBlank() || character.exampleDialogue.structured.isNotEmpty()) {
            val exSb = StringBuilder("[会話例]\n")
            if (character.exampleDialogue.freeform.isNotBlank()) {
                exSb.append(applyPlaceholders(character.exampleDialogue.freeform.take(1000).trim())).append("\n")
            }
            if (character.exampleDialogue.structured.isNotEmpty()) {
                for (pair in character.exampleDialogue.structured.take(10)) {
                    if (pair.user.isNotBlank()) exSb.append(effectiveUserName).append(": ").append(applyPlaceholders(pair.user.trim())).append("\n")
                    if (pair.character.isNotBlank()) exSb.append(charName).append(": ").append(applyPlaceholders(pair.character.trim())).append("\n")
                }
            }
            exSb.append("\n").toString()
        } else ""

        // 4. Post History Instructions
        val postHistorySection = if (character.postHistoryInstructions.isNotBlank()) {
            "[追加指示]\n${applyPlaceholders(character.postHistoryInstructions.trim())}\n\n"
        } else ""

        // 5. Current User message and trigger for Character reply
        val currentTurnSection = if (!continuePrefix.isNullOrBlank()) {
            if (newUserInput.isNotBlank()) {
                "[今回の会話]\n$effectiveUserName: ${newUserInput.trim()}\n${charName}: ${continuePrefix.trim()}"
            } else {
                "[今回の会話]\n${charName}: ${continuePrefix.trim()}"
            }
        } else {
            "[今回の会話]\n$effectiveUserName: ${newUserInput.trim()}\n${charName}:"
        }

        // Token budgeting for history
        val promptBudget = (contextSize - maxOutputTokens).coerceAtLeast(64)
        val fixedTokens = estimateTokens(systemSection) +
                estimateTokens(userSection) +
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
            val speaker = if (msg.role == MessageRole.USER) effectiveUserName else charName
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

        return systemSection + userSection + charInfoSection + exSection + historySection + postHistorySection + currentTurnSection
    }
}
