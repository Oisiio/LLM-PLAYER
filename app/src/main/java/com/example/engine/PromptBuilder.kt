package com.example.engine

import com.example.data.model.Character
import com.example.data.model.Message
import com.example.data.model.MessageRole

object PromptBuilder {

    fun buildPrompt(
        character: Character,
        recentMessages: List<Message>,
        newUserInput: String
    ): String {
        val sb = StringBuilder()

        // 1. System Prompt / Base instructions
        val systemInstruction = character.systemPrompt.custom.ifBlank {
            character.systemPrompt.template ?: "あなたは以下のキャラクターとして振る舞い、ユーザーと自然に対話してください。"
        }
        sb.append("[指示]\n").append(systemInstruction).append("\n\n")

        // 2. Character Definition (Name, Personality, Style)
        sb.append("[キャラクター情報]\n")
        sb.append("名前: ").append(character.name).append("\n")

        val personalityItems = mutableListOf<String>()
        if (character.personality.presets.isNotEmpty()) {
            personalityItems.add(character.personality.presets.joinToString(", "))
        }
        if (character.personality.custom.isNotBlank()) {
            personalityItems.add(character.personality.custom)
        }
        if (personalityItems.isNotEmpty()) {
            sb.append("性格: ").append(personalityItems.joinToString(" / ")).append("\n")
        }

        val styleItems = mutableListOf<String>()
        if (character.style.presets.isNotEmpty()) {
            styleItems.add(character.style.presets.joinToString(", "))
        }
        if (character.style.custom.isNotBlank()) {
            styleItems.add(character.style.custom)
        }
        if (styleItems.isNotEmpty()) {
            sb.append("口調・話し方: ").append(styleItems.joinToString(" / ")).append("\n")
        }

        // 3. Scenario
        val scenarioText = character.scenario.content.ifBlank { character.scenario.template ?: "" }
        if (scenarioText.isNotBlank()) {
            sb.append("シチュエーション・背景: ").append(scenarioText).append("\n")
        }
        sb.append("\n")

        // 4. Example Dialogue (優先順位: 自由入力を優先し、構造化形式は補足)
        if (character.exampleDialogue.freeform.isNotBlank() || character.exampleDialogue.structured.isNotEmpty()) {
            sb.append("[会話例]\n")
            if (character.exampleDialogue.freeform.isNotBlank()) {
                sb.append(character.exampleDialogue.freeform.take(1000)).append("\n")
            }
            if (character.exampleDialogue.structured.isNotEmpty()) {
                for (pair in character.exampleDialogue.structured.take(10)) {
                    if (pair.user.isNotBlank()) sb.append("User: ").append(pair.user).append("\n")
                    if (pair.character.isNotBlank()) sb.append(character.name).append(": ").append(pair.character).append("\n")
                }
            }
            sb.append("\n")
        }

        // 5. Recent conversation history (最新の会話から3往復程度まで)
        // 過去のメッセージから直近最大6件を抽出
        val historyToInclude = recentMessages.takeLast(6)
        if (historyToInclude.isNotEmpty()) {
            sb.append("[これまでの会話]\n")
            for (msg in historyToInclude) {
                val speaker = if (msg.role == MessageRole.USER) "User" else character.name
                sb.append(speaker).append(": ").append(msg.displayContent).append("\n")
            }
            sb.append("\n")
        }

        // 6. Current User message and trigger for Character reply
        sb.append("[今回の会話]\n")
        sb.append("User: ").append(newUserInput).append("\n")
        sb.append(character.name).append(":")

        return sb.toString()
    }
}
