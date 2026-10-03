package com.example.talk

import com.example.data.model.*
import com.example.data.model.Character
import com.example.data.model.Message
import com.example.data.model.MessageRole
import com.example.engine.PromptBuilder
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun testPromptStructure_withAllFields() {
        val character = Character(
            name = "アリス",
            description = "図書館で働く温厚な少女",
            personality = PersonalityData(listOf("優しい", "知的"), "読書家"),
            style = StyleData(listOf("丁寧語"), "〜ですよ"),
            systemPrompt = SystemPromptData(null, "あなたはアリスとしてロールプレイしてください。"),
            firstMessage = "こんにちは！",
            alternateGreetings = listOf("やあ！", "ごきげんよう"),
            scenario = ScenarioData(null, "静かな図書館での対話"),
            exampleDialogue = ExampleDialogueData(
                structured = listOf(DialoguePair("おすすめの本は？", "この詩集が素敵ですよ。")),
                freeform = "User: こんにちは\nアリス: こんにちは、いらっしゃいませ。"
            ),
            postHistoryInstructions = "常にアリスの性格を崩さず、2文以内で回答してください。"
        )

        val messages = listOf(
            Message(chatId = "1", role = MessageRole.USER, content = "本を探しています"),
            Message(chatId = "1", role = MessageRole.CHARACTER, content = "どんなジャンルがお好みですか？")
        )

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = messages,
            newUserInput = "ミステリー小説を読みたいです",
            contextSize = 2048,
            maxOutputTokens = 128
        )

        // Verify section order:
        // [指示] -> [キャラクター情報] -> [会話例] -> [これまでの会話] -> [追加指示] -> [今回の会話]
        val idxInstruction = prompt.indexOf("[指示]")
        val idxCharInfo = prompt.indexOf("[キャラクター情報]")
        val idxExamples = prompt.indexOf("[会話例]")
        val idxHistory = prompt.indexOf("[これまでの会話]")
        val idxPostHistory = prompt.indexOf("[追加指示]")
        val idxCurrent = prompt.indexOf("[今回の会話]")

        assertTrue(idxInstruction != -1)
        assertTrue(idxCharInfo > idxInstruction)
        assertTrue(idxExamples > idxCharInfo)
        assertTrue(idxHistory > idxExamples)
        assertTrue(idxPostHistory > idxHistory)
        assertTrue(idxCurrent > idxPostHistory)

        // Verify content
        assertTrue(prompt.contains("説明: 図書館で働く温厚な少女"))
        assertTrue(prompt.contains("常にアリスの性格を崩さず、2文以内で回答してください。"))
        assertTrue(prompt.contains("User: ミステリー小説を読みたいです\nアリス:"))
    }

    @Test
    fun testPromptStructure_emptyFieldsOmitted() {
        val character = Character(
            name = "ボブ",
            description = "",
            personality = PersonalityData(),
            style = StyleData(),
            systemPrompt = SystemPromptData(),
            scenario = ScenarioData(),
            exampleDialogue = ExampleDialogueData(),
            postHistoryInstructions = ""
        )

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "テスト"
        )

        assertFalse(prompt.contains("説明:"))
        assertFalse(prompt.contains("[会話例]"))
        assertFalse(prompt.contains("[これまでの会話]"))
        assertFalse(prompt.contains("[追加指示]"))
        assertTrue(prompt.contains("[指示]"))
        assertTrue(prompt.contains("[キャラクター情報]"))
        assertTrue(prompt.contains("[今回の会話]"))
    }

    @Test
    fun testPromptBudget_trimsOldestMessagesFirst() {
        val character = Character(name = "テストキャラ")

        val messages = (1..20).map { i ->
            Message(
                chatId = "1",
                role = if (i % 2 == 1) MessageRole.USER else MessageRole.CHARACTER,
                content = "メッセージ番号 $i です。長い文章を書いてトークン数を消費します。"
            )
        }

        // Very small context budget so only newest messages fit
        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = messages,
            newUserInput = "最新の質問",
            contextSize = 300,
            maxOutputTokens = 100
        )

        // Newest messages should be present
        assertTrue(prompt.contains("メッセージ番号 20"))
        // Oldest messages should have been trimmed away
        assertFalse(prompt.contains("メッセージ番号 1 "))
    }
}
