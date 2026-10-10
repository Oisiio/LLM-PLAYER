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

    @Test
    fun testPromptStructure_withUserPersona_allFields() {
        val character = Character(name = "エレナ")
        val persona = UserPersona(
            name = "太郎",
            description = "図書館に通う高校生",
            persona = "穏やかで礼儀正しい性格"
        )

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "こんにちは",
            userPersona = persona
        )

        // Verify section order: [指示] -> [ユーザー情報] -> [キャラクター情報]
        val idxInstruction = prompt.indexOf("[指示]")
        val idxUser = prompt.indexOf("[ユーザー情報]")
        val idxChar = prompt.indexOf("[キャラクター情報]")

        assertTrue(idxInstruction != -1)
        assertTrue(idxUser != -1)
        assertTrue(idxChar != -1)
        assertTrue(idxUser > idxInstruction)
        assertTrue(idxChar > idxUser)

        assertTrue(prompt.contains("名前: 太郎"))
        assertTrue(prompt.contains("説明: 図書館に通う高校生"))
        assertTrue(prompt.contains("ペルソナ: 穏やかで礼儀正しい性格"))
    }

    @Test
    fun testPromptStructure_withUserPersona_partialFields() {
        val character = Character(name = "エレナ")
        val persona = UserPersona(
            name = "次郎",
            description = "", // empty
            persona = "元気で活発"
        )

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "こんにちは",
            userPersona = persona
        )

        assertTrue(prompt.contains("[ユーザー情報]"))
        assertTrue(prompt.contains("名前: 次郎"))
        assertFalse(prompt.contains("説明:"))
        assertTrue(prompt.contains("ペルソナ: 元気で活発"))
    }

    @Test
    fun testPromptStructure_withUserPersona_empty_omitsSection() {
        val character = Character(name = "エレナ")
        val persona = UserPersona(name = "", description = "   ", persona = "")

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "こんにちは",
            userPersona = persona
        )

        assertFalse(prompt.contains("[ユーザー情報]"))
    }

    @Test
    fun testResolvePlaceholders_replacesUserAndChar() {
        val template = "こんにちは、{{user}}！私は{{char}}です。<USER>さん、<CHAR>と呼びます。{{user_description}}"
        val resolved = PromptBuilder.resolvePlaceholders(
            template = template,
            userName = "アリス",
            charName = "ボブ",
            userDescription = "読書好き"
        )
        assertEquals("こんにちは、アリス！私はボブです。アリスさん、ボブと呼びます。読書好き", resolved)
    }

    @Test
    fun testResolvePlaceholders_fallbackWhenUserUnset() {
        val template = "{{user}}: こんにちは\n{{char}}: やあ"
        val resolved = PromptBuilder.resolvePlaceholders(
            template = template,
            userName = "", // 未設定
            charName = "ボブ"
        )
        assertEquals("User: こんにちは\nボブ: やあ", resolved)
    }

    @Test
    fun testPromptBuild_replacesPlaceholders_acrossAllSections() {
        val character = Character(
            name = "エレナ",
            description = "{{user}}の頼れる相棒。",
            scenario = com.example.data.model.ScenarioData(null, "{{user}}と{{char}}が旅をする。"),
            systemPrompt = com.example.data.model.SystemPromptData(null, "{{user}}のために全力を尽くすこと。"),
            exampleDialogue = com.example.data.model.ExampleDialogueData(
                freeform = "{{user}}: 助けて！\n{{char}}: 任せて！"
            ),
            postHistoryInstructions = "{{user}}に敬意を払って応答すること。"
        )

        val persona = UserPersona(
            name = "健太",
            description = "新米冒険者",
            persona = "勇敢"
        )

        val prompt = PromptBuilder.buildPrompt(
            character = character,
            recentMessages = emptyList(),
            newUserInput = "出発しよう",
            userPersona = persona
        )

        // {{user}} が 健太 に置換されていること
        assertFalse(prompt.contains("{{user}}"))
        assertFalse(prompt.contains("{{char}}"))
        assertTrue(prompt.contains("健太の頼れる相棒。"))
        assertTrue(prompt.contains("健太とエレナが旅をする。"))
        assertTrue(prompt.contains("健太のために全力を尽くすこと。"))
        assertTrue(prompt.contains("健太: 助けて！\nエレナ: 任せて！"))
        assertTrue(prompt.contains("健太に敬意を払って応答すること。"))
        assertTrue(prompt.contains("健太: 出発しよう\nエレナ:"))
    }
}
