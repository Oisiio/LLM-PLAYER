package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MessageRole {
    USER, CHARACTER
}

data class PersonalityData(
    val presets: List<String> = emptyList(),
    val custom: String = ""
)

data class StyleData(
    val presets: List<String> = emptyList(),
    val custom: String = ""
)

data class SystemPromptData(
    val template: String? = null,
    val custom: String = ""
)

data class ScenarioData(
    val template: String? = null,
    val content: String = ""
)

data class DialoguePair(
    val user: String,
    val character: String
)

data class ExampleDialogueData(
    val structured: List<DialoguePair> = emptyList(),
    val freeform: String = ""
)

data class Character(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val iconUri: String? = null,
    val personality: PersonalityData = PersonalityData(),
    val style: StyleData = StyleData(),
    val systemPrompt: SystemPromptData = SystemPromptData(),
    val firstMessage: String = "",
    val scenario: ScenarioData = ScenarioData(),
    val exampleDialogue: ExampleDialogueData = ExampleDialogueData(),
    val isFavorite: Boolean = false,
    val lastUsedAt: Long = System.currentTimeMillis()
)

object LlmDefaultSettings {
    const val CONTEXT_SIZE: Int = 512
    const val MAX_OUTPUT_TOKENS: Int = 128
    const val TEMPERATURE: Float = 0.7f
    const val TOP_K: Int = 40
    const val TOP_P: Float = 0.9f
    const val CPU_THREADS: Int = 4
    const val CPU_THREADS_BATCH: Int = 4
}

data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val title: String = "New Chat",
    val temperature: Float = LlmDefaultSettings.TEMPERATURE,
    val topK: Int = LlmDefaultSettings.TOP_K,
    val topP: Float = LlmDefaultSettings.TOP_P,
    val minP: Float = 0.0f,
    val typicalP: Float = 1.0f,
    val repetitionPenalty: Float = 1.1f,
    val penaltyLastN: Int = 64,
    val seed: Long = 12345L,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val candidates: List<String> = emptyList(),
    val selectedCandidateIndex: Int = 0
) {
    val displayContent: String
        get() = if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
            candidates[selectedCandidateIndex]
        } else {
            content
        }
}

object CharacterJsonConverter {
    fun toJson(character: Character): String {
        val root = JSONObject()
        root.put("format", "llm-player-character")
        root.put("version", 1)

        val charObj = JSONObject()
        charObj.put("name", character.name)

        val persObj = JSONObject()
        val persPresets = JSONArray()
        character.personality.presets.forEach { persPresets.put(it) }
        persObj.put("presets", persPresets)
        persObj.put("custom", character.personality.custom)
        charObj.put("personality", persObj)

        val styleObj = JSONObject()
        val stylePresets = JSONArray()
        character.style.presets.forEach { stylePresets.put(it) }
        styleObj.put("presets", stylePresets)
        styleObj.put("custom", character.style.custom)
        charObj.put("style", styleObj)

        val sysObj = JSONObject()
        if (character.systemPrompt.template != null) {
            sysObj.put("template", character.systemPrompt.template)
        } else {
            sysObj.put("template", JSONObject.NULL)
        }
        sysObj.put("custom", character.systemPrompt.custom)
        charObj.put("system_prompt", sysObj)

        charObj.put("first_message", character.firstMessage)

        val scnObj = JSONObject()
        if (character.scenario.template != null) {
            scnObj.put("template", character.scenario.template)
        } else {
            scnObj.put("template", JSONObject.NULL)
        }
        scnObj.put("content", character.scenario.content)
        charObj.put("scenario", scnObj)

        val exObj = JSONObject()
        val structuredArr = JSONArray()
        character.exampleDialogue.structured.take(10).forEach { pair ->
            val pairObj = JSONObject()
            pairObj.put("user", pair.user)
            pairObj.put("character", pair.character)
            structuredArr.put(pairObj)
        }
        exObj.put("structured", structuredArr)
        exObj.put("freeform", character.exampleDialogue.freeform.take(1000))
        charObj.put("example_dialogue", exObj)

        root.put("character", charObj)
        return root.toString(2)
    }

    sealed class ValidationResult {
        data class Success(val character: Character) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    fun fromJson(jsonString: String): ValidationResult {
        if (jsonString.isBlank()) {
            return ValidationResult.Error("JSONが空です。正しいCharacter JSONを選択してください。")
        }
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return ValidationResult.Error("JSONの構文解析に失敗しました: ${e.localizedMessage ?: "構文エラー"}")
        }

        val format = root.optString("format", "")
        if (format != "llm-player-character") {
            return ValidationResult.Error("formatが正しくありません。\"llm-player-character\" である必要があります。(現在: \"$format\")")
        }

        val version = root.optInt("version", -1)
        if (version != 1) {
            return ValidationResult.Error("対応していないバージョンです。versionは 1 である必要があります。(現在: $version)")
        }

        val charObj = root.optJSONObject("character")
            ?: return ValidationResult.Error("必須項目 \"character\" オブジェクトが見つかりません。")

        val name = charObj.optString("name", "").trim()
        if (name.isEmpty()) {
            return ValidationResult.Error("Character名 (\"name\") は必須です。空文字にはできません。")
        }
        if (name.length > 50) {
            return ValidationResult.Error("Character名 (\"name\") は最大50文字です。(現在: ${name.length}文字)")
        }

        // personality
        val persObj = charObj.optJSONObject("personality")
        val persPresets = mutableListOf<String>()
        var persCustom = ""
        if (persObj != null) {
            val arr = persObj.optJSONArray("presets")
            if (arr != null) {
                for (i in 0 until arr.length()) persPresets.add(arr.optString(i))
            }
            persCustom = persObj.optString("custom", "")
        }

        // style
        val styleObj = charObj.optJSONObject("style")
        val stylePresets = mutableListOf<String>()
        var styleCustom = ""
        if (styleObj != null) {
            val arr = styleObj.optJSONArray("presets")
            if (arr != null) {
                for (i in 0 until arr.length()) stylePresets.add(arr.optString(i))
            }
            styleCustom = styleObj.optString("custom", "")
        }

        // system_prompt
        val sysObj = charObj.optJSONObject("system_prompt")
        var sysTemplate: String? = null
        var sysCustom = ""
        if (sysObj != null) {
            if (!sysObj.isNull("template")) sysTemplate = sysObj.optString("template", null)
            sysCustom = sysObj.optString("custom", "")
        }

        val firstMessage = charObj.optString("first_message", "")

        // scenario
        val scnObj = charObj.optJSONObject("scenario")
        var scnTemplate: String? = null
        var scnContent = ""
        if (scnObj != null) {
            if (!scnObj.isNull("template")) scnTemplate = scnObj.optString("template", null)
            scnContent = scnObj.optString("content", "")
        }

        // example_dialogue
        val exObj = charObj.optJSONObject("example_dialogue")
        val structuredList = mutableListOf<DialoguePair>()
        var freeform = ""
        if (exObj != null) {
            val sArr = exObj.optJSONArray("structured")
            if (sArr != null) {
                for (i in 0 until minOf(sArr.length(), 10)) {
                    val pObj = sArr.optJSONObject(i)
                    if (pObj != null) {
                        val u = pObj.optString("user", "")
                        val c = pObj.optString("character", "")
                        if (u.isNotBlank() || c.isNotBlank()) {
                            structuredList.add(DialoguePair(u, c))
                        }
                    }
                }
            }
            freeform = exObj.optString("freeform", "")
            if (freeform.length > 1000) {
                freeform = freeform.take(1000)
            }
        }

        val character = Character(
            id = UUID.randomUUID().toString(),
            name = name,
            iconUri = null,
            personality = PersonalityData(persPresets, persCustom),
            style = StyleData(stylePresets, styleCustom),
            systemPrompt = SystemPromptData(sysTemplate, sysCustom),
            firstMessage = firstMessage,
            scenario = ScenarioData(scnTemplate, scnContent),
            exampleDialogue = ExampleDialogueData(structuredList, freeform),
            isFavorite = false,
            lastUsedAt = System.currentTimeMillis()
        )
        return ValidationResult.Success(character)
    }
}
