package com.example.data.importer

import android.util.Base64
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.InflaterInputStream

sealed class CharacterImportResult {
    data class Success(val character: Character, val imageBytes: ByteArray? = null) : CharacterImportResult()
    data class Error(val message: String) : CharacterImportResult()
}

object CharacterCardImporter {

    fun importFromBytes(bytes: ByteArray): CharacterImportResult {
        if (bytes.isEmpty()) {
            return CharacterImportResult.Error("ファイルが空です。正しいファイルを選択してください。")
        }

        // Check if PNG image
        if (isPng(bytes)) {
            val extractedJson = PngMetadataExtractor.extractCharacterCardJson(bytes)
                ?: return CharacterImportResult.Error("PNG画像内にCharacter Card情報 (chara/tEXtメタデータ) が見つかりませんでした。")
            return importFromJson(extractedJson, imageBytes = bytes)
        }

        // Try as text JSON
        val text = try {
            String(bytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            return CharacterImportResult.Error("ファイルのテキスト読み込みに失敗しました: ${e.localizedMessage}")
        }
        return importFromJson(text, imageBytes = null)
    }

    fun importFromJson(jsonString: String, imageBytes: ByteArray? = null): CharacterImportResult {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) {
            return CharacterImportResult.Error("Character Cardを読み込めませんでした: JSONデータが空です。")
        }

        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            return CharacterImportResult.Error("Character Cardを読み込めませんでした: JSON構文エラー (${e.localizedMessage})")
        }

        // 1. LLM-PLAYER Native V1 Format
        val format = root.optString("format", "")
        if (format == "llm-player-character") {
            return when (val result = CharacterJsonConverter.fromJson(trimmed)) {
                is CharacterJsonConverter.ValidationResult.Success -> {
                    CharacterImportResult.Success(result.character, imageBytes)
                }
                is CharacterJsonConverter.ValidationResult.Error -> {
                    CharacterImportResult.Error("Character Cardを読み込めませんでした: ${result.message}")
                }
            }
        }

        // 2. Character Card V2 (spec: chara_card_v2) or V1 (root contains data fields)
        val spec = root.optString("spec", "")
        val specVersion = root.optString("spec_version", "")
        val isV2 = spec.equals("chara_card_v2", ignoreCase = true) ||
                specVersion.startsWith("2") ||
                root.has("data")

        val dataObj = if (isV2 && root.has("data")) {
            root.optJSONObject("data") ?: root
        } else {
            root
        }

        val name = dataObj.optString("name", "").trim()
        if (name.isEmpty()) {
            return CharacterImportResult.Error("Character Cardを読み込めませんでした: 必須項目 \"name\" が見つかりません。")
        }
        val safeName = if (name.length > 50) name.take(50) else name

        val description = dataObj.optString("description", "").trim()
        val personalityText = dataObj.optString("personality", "").trim()
        val scenarioText = dataObj.optString("scenario", "").trim()
        val systemPromptText = dataObj.optString("system_prompt", "").trim()
        val firstMessage = dataObj.optString("first_mes", "").trim()
        val mesExample = dataObj.optString("mes_example", "").trim()
        val postHistory = dataObj.optString("post_history_instructions", "").trim()

        val altGreetings = mutableListOf<String>()
        val altArr = dataObj.optJSONArray("alternate_greetings")
        if (altArr != null) {
            for (i in 0 until altArr.length()) {
                val g = altArr.optString(i, "").trim()
                if (g.isNotEmpty()) altGreetings.add(g)
            }
        }

        val character = Character(
            id = UUID.randomUUID().toString(),
            name = safeName,
            description = description,
            iconUri = null,
            personality = PersonalityData(presets = emptyList(), custom = personalityText),
            style = StyleData(presets = emptyList(), custom = ""),
            systemPrompt = SystemPromptData(template = null, custom = systemPromptText),
            firstMessage = firstMessage,
            alternateGreetings = altGreetings,
            scenario = ScenarioData(template = null, content = scenarioText),
            exampleDialogue = ExampleDialogueData(structured = emptyList(), freeform = mesExample),
            postHistoryInstructions = postHistory,
            isFavorite = false,
            lastUsedAt = System.currentTimeMillis()
        )

        return CharacterImportResult.Success(character, imageBytes)
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0 until 8) {
            if (bytes[i] != pngSig[i]) return false
        }
        return true
    }
}

object PngMetadataExtractor {

    fun extractCharacterCardJson(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0 until 8) {
            if (bytes[i] != pngHeader[i]) return null
        }

        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            offset += 8

            if (length < 0 || offset + length > bytes.size) break

            when (type) {
                "tEXt" -> {
                    val chunkData = bytes.copyOfRange(offset, offset + length)
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex in 1 until chunkData.size) {
                        val keyword = String(chunkData, 0, nullIndex, Charsets.ISO_8859_1).lowercase()
                        val text = String(chunkData, nullIndex + 1, chunkData.size - nullIndex - 1, Charsets.UTF_8)
                        val candidate = parseCandidateJson(keyword, text)
                        if (candidate != null) return candidate
                    }
                }
                "zTXt" -> {
                    val chunkData = bytes.copyOfRange(offset, offset + length)
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex in 1 until chunkData.size - 2) {
                        val keyword = String(chunkData, 0, nullIndex, Charsets.ISO_8859_1).lowercase()
                        val compressionMethod = chunkData[nullIndex + 1]
                        if (compressionMethod == 0.toByte()) {
                            try {
                                val compressed = chunkData.copyOfRange(nullIndex + 2, chunkData.size)
                                val text = InflaterInputStream(ByteArrayInputStream(compressed))
                                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                                val candidate = parseCandidateJson(keyword, text)
                                if (candidate != null) return candidate
                            } catch (_: Exception) {}
                        }
                    }
                }
                "iTXt" -> {
                    val chunkData = bytes.copyOfRange(offset, offset + length)
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex in 1 until chunkData.size - 4) {
                        val keyword = String(chunkData, 0, nullIndex, Charsets.ISO_8859_1).lowercase()
                        val compressionFlag = chunkData[nullIndex + 1]
                        var idx = nullIndex + 3
                        while (idx < chunkData.size && chunkData[idx] != 0.toByte()) idx++
                        idx++
                        while (idx < chunkData.size && chunkData[idx] != 0.toByte()) idx++
                        idx++
                        if (idx < chunkData.size) {
                            val textBytes = chunkData.copyOfRange(idx, chunkData.size)
                            val text = if (compressionFlag == 1.toByte()) {
                                try {
                                    InflaterInputStream(ByteArrayInputStream(textBytes))
                                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                                } catch (_: Exception) { "" }
                            } else {
                                String(textBytes, Charsets.UTF_8)
                            }
                            val candidate = parseCandidateJson(keyword, text)
                            if (candidate != null) return candidate
                        }
                    }
                }
                "IEND" -> break
            }

            offset += length + 4 // skip data and 4 bytes CRC
        }
        return null
    }

    private fun parseCandidateJson(keyword: String, rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        // Try base64 decoding (common in chara / ccv3 metadata)
        try {
            val bytes = try {
                java.util.Base64.getDecoder().decode(trimmed)
            } catch (_: Throwable) {
                Base64.decode(trimmed, Base64.DEFAULT)
            }
            val str = String(bytes, Charsets.UTF_8).trim()
            if (str.startsWith("{") && str.endsWith("}")) {
                return str
            }
        } catch (_: Exception) {}
        return null
    }
}
