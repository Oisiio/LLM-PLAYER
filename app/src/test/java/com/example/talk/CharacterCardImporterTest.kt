package com.example.talk

import com.example.data.importer.CharacterCardImporter
import com.example.data.importer.CharacterImportResult
import com.example.data.importer.PngMetadataExtractor
import com.example.data.model.Character
import com.example.data.model.CharacterJsonConverter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class CharacterCardImporterTest {

    @Test
    fun testImport_CharacterCardV2_success() {
        val v2Json = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "エレナ",
            "description": "魔法学校の特待生",
            "personality": "勝気で負けず嫌い",
            "scenario": "試験直前の図書館",
            "first_mes": "何よ、邪魔しないでよね！",
            "alternate_greetings": [
              "あら、奇遇ね。",
              "また会ったわね。"
            ],
            "mes_example": "<START>\n{{user}}: こんにちは\n{{char}}: ごきげんよう",
            "system_prompt": "常にエレナとして会話すること。",
            "post_history_instructions": "ツンデレな態度を崩さないでください。"
          }
        }
        """.trimIndent()

        val result = CharacterCardImporter.importFromJson(v2Json)
        assertTrue(result is CharacterImportResult.Success)
        val char = (result as CharacterImportResult.Success).character

        assertEquals("エレナ", char.name)
        assertEquals("魔法学校の特待生", char.description)
        assertEquals("勝気で負けず嫌い", char.personality.custom)
        assertEquals("試験直前の図書館", char.scenario.content)
        assertEquals("何よ、邪魔しないでよね！", char.firstMessage)
        assertEquals(2, char.alternateGreetings.size)
        assertEquals("あら、奇遇ね。", char.alternateGreetings[0])
        assertEquals("また会ったわね。", char.alternateGreetings[1])
        assertEquals("常にエレナとして会話すること。", char.systemPrompt.custom)
        assertEquals("ツンデレな態度を崩さないでください。", char.postHistoryInstructions)
        assertTrue(char.exampleDialogue.freeform.contains("<START>"))
    }

    @Test
    fun testImport_TavernV1_success() {
        val v1Json = """
        {
          "name": "ソフィア",
          "description": "カフェの店主",
          "personality": "おっとり",
          "scenario": "午後のティータイム",
          "first_mes": "いらっしゃいませ。紅茶はいかが？",
          "mes_example": "User: おすすめは？\nソフィア: アールグレイですよ。"
        }
        """.trimIndent()

        val result = CharacterCardImporter.importFromJson(v1Json)
        assertTrue(result is CharacterImportResult.Success)
        val char = (result as CharacterImportResult.Success).character

        assertEquals("ソフィア", char.name)
        assertEquals("カフェの店主", char.description)
        assertEquals("おっとり", char.personality.custom)
        assertEquals("午後のティータイム", char.scenario.content)
        assertEquals("いらっしゃいませ。紅茶はいかが？", char.firstMessage)
    }

    @Test
    fun testImport_LlmPlayerNative_roundtrip() {
        val original = Character(
            name = "オリジナルキャラ",
            description = "独自フォーマットのキャラ",
            firstMessage = "初めまして！",
            alternateGreetings = listOf("やあ！", "ハロー！"),
            postHistoryInstructions = "日本語で回答してください。"
        )

        val json = CharacterJsonConverter.toJson(original)
        val result = CharacterCardImporter.importFromJson(json)

        assertTrue(result is CharacterImportResult.Success)
        val imported = (result as CharacterImportResult.Success).character
        assertEquals(original.name, imported.name)
        assertEquals(original.description, imported.description)
        assertEquals(original.firstMessage, imported.firstMessage)
        assertEquals(original.alternateGreetings, imported.alternateGreetings)
        assertEquals(original.postHistoryInstructions, imported.postHistoryInstructions)
    }

    @Test
    fun testImport_missingName_failsGracefully() {
        val invalidJson = """
        {
          "spec": "chara_card_v2",
          "data": {
            "description": "名前がないキャラ"
          }
        }
        """.trimIndent()

        val result = CharacterCardImporter.importFromJson(invalidJson)
        assertTrue(result is CharacterImportResult.Error)
        val msg = (result as CharacterImportResult.Error).message
        assertTrue(msg.contains("name"))
    }

    @Test
    fun testImport_malformedJson_failsGracefully() {
        val brokenJson = "not a json at all {{"
        val result = CharacterCardImporter.importFromJson(brokenJson)
        assertTrue(result is CharacterImportResult.Error)
    }

    @Test
    fun testPngMetadataExtractor_withValidPngChunk() {
        // Construct a minimal valid PNG with a tEXt chunk
        val pngBytes = createTestPngWithTextChunk("chara", """{"name":"PNGキャラ","first_mes":"画像から読み込まれました"}""")

        val extracted = PngMetadataExtractor.extractCharacterCardJson(pngBytes)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("PNGキャラ"))

        val result = CharacterCardImporter.importFromBytes(pngBytes)
        assertTrue(result is CharacterImportResult.Success)
        val char = (result as CharacterImportResult.Success).character
        assertEquals("PNGキャラ", char.name)
        assertEquals("画像から読み込まれました", char.firstMessage)
    }

    private fun createTestPngWithTextChunk(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG Signature
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR chunk (13 bytes)
        val ihdrData = ByteArray(13)
        writePngChunk(out, "IHDR", ihdrData)

        // tEXt chunk
        val textPayload = ByteArrayOutputStream()
        textPayload.write(keyword.toByteArray(Charsets.ISO_8859_1))
        textPayload.write(0) // null separator
        textPayload.write(text.toByteArray(Charsets.UTF_8))
        writePngChunk(out, "tEXt", textPayload.toByteArray())

        // IEND chunk (0 bytes)
        writePngChunk(out, "IEND", ByteArray(0))

        return out.toByteArray()
    }

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)

        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcVal = crc.value.toInt()
        out.write((crcVal ushr 24) and 0xFF)
        out.write((crcVal ushr 16) and 0xFF)
        out.write((crcVal ushr 8) and 0xFF)
        out.write(crcVal and 0xFF)
    }
}
