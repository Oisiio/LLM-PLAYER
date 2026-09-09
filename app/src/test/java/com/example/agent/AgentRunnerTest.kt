package com.example.agent

import com.example.agent.tools.CalculatorTool
import com.example.agent.tools.DateTimeTool
import com.example.ui.talk.LlmStreamRunner
import com.example.ui.talk.TalkDebugMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentRunnerTest {

    @Test
    fun testToolCallParser_jsonFormat() {
        val output = """
            Thinking: I need to calculate this.
            <tool_call>
            {"name": "calculator", "arguments": {"expression": "12345 * 678"}}
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(output)
        assertNotNull("ToolCall should be parsed", call)
        assertEquals("calculator", call!!.toolName)
        assertEquals("12345 * 678", call.arguments["expression"])
    }

    @Test
    fun testToolCallParser_textColonFormat() {
        val output = """
            <tool_call>
            calculator: (100 + 50) * 2
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(output)
        assertNotNull(call)
        assertEquals("calculator", call!!.toolName)
        assertEquals("(100 + 50) * 2", call.arguments["expression"])
    }

    @Test
    fun testToolCallParser_lineBasedFormat() {
        val output = """
            <tool_call>
            calculator
            expression: 12.5 * 2
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(output)
        assertNotNull(call)
        assertEquals("calculator", call!!.toolName)
        assertEquals("12.5 * 2", call.arguments["expression"])
    }

    @Test
    fun testToolCallParser_markdownInsideTag() {
        val output = """
            <tool_call>
            ```json
            {"name": "calculator", "arguments": {"expression": "100 / 4"}}
            ```
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(output)
        assertNotNull(call)
        assertEquals("calculator", call!!.toolName)
        assertEquals("100 / 4", call.arguments["expression"])
    }

    @Test
    fun test7_normalConversationWithoutTool() = runBlocking {
        val logs = mutableListOf<String>()
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                return "こんにちは！何かお手伝いできることはありますか？"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool())),
            logger = { msg -> logs.add(msg) }
        )

        val result = agent.run("こんにちは")

        assertTrue("Expected Success result", result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("こんにちは！何かお手伝いできることはありますか？", success.finalAnswer)
        assertEquals(1, success.steps.size)
        assertTrue(success.steps[0].isFinal)
        assertNull(success.steps[0].toolCall)

        // Check logs
        assertTrue(logs.contains("[Agent] start"))
        assertTrue(logs.contains("[Agent] step=1"))
        assertTrue(logs.contains("[Agent] final"))
        assertFalse(logs.any { it.startsWith("[Agent] tool=") })
    }

    @Test
    fun test8_agentLoopWithCalculator() = runBlocking {
        val logs = mutableListOf<String>()
        var inferenceCount = 0

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                inferenceCount++
                return when (inferenceCount) {
                    1 -> {
                        """
                        計算ツールを呼び出します。
                        <tool_call>
                        {"name": "calculator", "arguments": {"expression": "123 + 456"}}
                        </tool_call>
                        """.trimIndent()
                    }
                    2 -> {
                        // Verifying that previous tool result was provided in the prompt
                        assertTrue(prompt.contains("579"))
                        "123 + 456 の計算結果は 579 です。"
                    }
                    else -> "Unexpected"
                }
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool())),
            logger = { msg -> logs.add(msg) }
        )

        val result = agent.run("123 + 456 を計算して")

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("123 + 456 の計算結果は 579 です。", success.finalAnswer)
        assertEquals(2, success.steps.size)

        // Step 1 check
        assertEquals("calculator", success.steps[0].toolCall?.toolName)
        assertEquals("579", (success.steps[0].toolResult as ToolExecutionResult.Success).output)

        // Step 2 check
        assertTrue(success.steps[1].isFinal)

        // Check required logs
        assertTrue(logs.contains("[Agent] start"))
        assertTrue(logs.contains("[Agent] step=1"))
        assertTrue(logs.contains("[Agent] tool=calculator"))
        assertTrue(logs.contains("[Agent] expression=123 + 456"))
        assertTrue(logs.contains("[Agent] result=579"))
        assertTrue(logs.contains("[Agent] step=2"))
        assertTrue(logs.contains("[Agent] final"))
    }

    @Test
    fun test6_toolErrorHandlingInLoop() = runBlocking {
        val logs = mutableListOf<String>()
        var inferenceCount = 0

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                inferenceCount++
                return when (inferenceCount) {
                    1 -> {
                        """
                        <tool_call>
                        {"name": "calculator", "arguments": {"expression": "10 / 0"}}
                        </tool_call>
                        """.trimIndent()
                    }
                    2 -> {
                        assertTrue(prompt.contains("Division by zero"))
                        "0で除算することはできません。"
                    }
                    else -> "Unexpected"
                }
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool())),
            logger = { msg -> logs.add(msg) }
        )

        val result = agent.run("10 / 0")

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("0で除算することはできません。", success.finalAnswer)
        assertEquals(2, success.steps.size)

        val step1Result = success.steps[0].toolResult
        assertTrue(step1Result is ToolExecutionResult.Error)
        assertTrue((step1Result as ToolExecutionResult.Error).errorMessage.contains("Division by zero"))

        assertTrue(logs.contains("[Agent] tool=calculator"))
        assertTrue(logs.contains("[Agent] expression=10 / 0"))
        assertTrue(logs.any { it.startsWith("[Agent] error=") })
    }

    @Test
    fun test9_stopCancellation() = runBlocking {
        val logs = mutableListOf<String>()
        var cancelledOnLlm = false

        lateinit var agent: AgentRunner
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {
                cancelledOnLlm = true
            }
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                agent.cancel()
                return "<tool_call>calculator: 10 + 10</tool_call>"
            }
        }

        agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool())),
            logger = { msg -> logs.add(msg) }
        )

        val result = agent.run("10 + 10")

        assertTrue("Expected Cancelled result", result is AgentResult.Cancelled)
        assertTrue("Native cancel should be triggered", cancelledOnLlm)
        assertTrue(logs.contains("[Agent] stop"))
    }

    @Test
    fun testMaxStepsLimit() = runBlocking {
        val logs = mutableListOf<String>()

        val endlessToolRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                return "<tool_call>calculator: 1 + 1</tool_call>"
            }
        }

        val agent = AgentRunner(
            llmRunner = endlessToolRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool())),
            logger = { msg -> logs.add(msg) }
        )

        val result = agent.run("エンドレス計算", maxSteps = 3)

        assertTrue("Expected MaxStepsReached result", result is AgentResult.MaxStepsReached)
        val maxResult = result as AgentResult.MaxStepsReached
        assertEquals("Agent stopped: maximum step limit reached", maxResult.finalAnswer)
        assertEquals(3, maxResult.steps.size)
        assertTrue(logs.contains("[Agent] stop"))
    }

    @Test
    fun test10_stateTransitionsAndStopLifecycle() = runBlocking {
        lateinit var agent: AgentRunner
        var stateDuringLlm: AgentState? = null
        var stateAfterCancel: AgentState? = null

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                stateDuringLlm = agent.state.value
                agent.cancel()
                stateAfterCancel = agent.state.value
                return "cancelled output"
            }
        }

        agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        assertEquals(AgentState.IDLE, agent.state.value)
        val result = agent.run("test")
        assertTrue(result is AgentResult.Cancelled)
        assertEquals(AgentState.RUNNING, stateDuringLlm)
        assertEquals(AgentState.CANCELLING, stateAfterCancel)
        assertEquals(AgentState.IDLE, agent.state.value)
    }

    @Test
    fun test11_reExecutionAfterStop() = runBlocking {
        lateinit var agent: AgentRunner
        var runCount = 0

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                runCount++
                return if (runCount == 1) {
                    agent.cancel()
                    "cancelled output"
                } else {
                    "2回目の回答です"
                }
            }
        }

        agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        // 1st run: stopped
        val result1 = agent.run("1回目")
        assertTrue(result1 is AgentResult.Cancelled)
        assertEquals(AgentState.IDLE, agent.state.value)

        // 2nd run: clean execution from IDLE
        val result2 = agent.run("2回目")
        assertTrue(result2 is AgentResult.Success)
        assertEquals("2回目の回答です", (result2 as AgentResult.Success).finalAnswer)
        assertEquals(AgentState.IDLE, agent.state.value)
    }

    @Test
    fun test12_stopSpammingIsIdempotent() = runBlocking {
        lateinit var agent: AgentRunner

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                // Spam cancel 5 times
                repeat(5) {
                    agent.cancel()
                }
                return "cancelled output"
            }
        }

        agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run("spam cancel test")
        assertTrue(result is AgentResult.Cancelled)
        assertEquals(AgentState.IDLE, agent.state.value)

        // Calling cancel when already IDLE is also harmless
        repeat(3) {
            agent.cancel()
        }
        assertEquals(AgentState.IDLE, agent.state.value)
    }

    @Test
    fun test13_preventDoubleRun() = runBlocking {
        lateinit var agent: AgentRunner
        var secondRunResult: AgentResult? = null

        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                // Try to start a second run while the first is running
                secondRunResult = agent.run("同時実行テスト")
                return "最初の回答です"
            }
        }

        agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result1 = agent.run("1回目")
        assertTrue("result1 expected Success but was: $result1", result1 is AgentResult.Success)
        assertTrue("secondRunResult expected Error but was: $secondRunResult", secondRunResult is AgentResult.Error)
        assertTrue((secondRunResult as AgentResult.Error).errorMessage.contains("already running", ignoreCase = true))
        assertEquals(AgentState.IDLE, agent.state.value)
    }

    @Test
    fun test14_toolChaining_dateTimeAndCalculator() = runBlocking {
        // Simulates:
        // User: "今日(2026-09-08)からクリスマス(2026-12-25)までの日数を計算して、1日500円貯金したらいくら？"
        // Step 1: LLM calls datetime diff_days -> returns 108
        // Step 2: LLM calls calculator 108 * 500 -> returns 54000
        // Step 3: LLM provides Final Answer: "合計54000円になります"
        var callCount = 0
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                callCount++
                return when (callCount) {
                    1 -> {
                        // Check that both tools are presented in prompt
                        assertTrue(prompt.contains("datetime"))
                        assertTrue(prompt.contains("calculator"))
                        """
                        思考: まず今日からクリスマスまでの日数を計算します。
                        <tool_call>
                        {"name": "datetime", "arguments": {"action": "diff_days", "from": "2026-09-08", "to": "2026-12-25"}}
                        </tool_call>
                        """.trimIndent()
                    }
                    2 -> {
                        // Check that step 1 tool result (108) is presented in prompt
                        assertTrue(prompt.contains("108"))
                        """
                        思考: 日数は108日です。次に 108 * 500 を計算します。
                        <tool_call>
                        {"name": "calculator", "arguments": {"expression": "108 * 500"}}
                        </tool_call>
                        """.trimIndent()
                    }
                    3 -> {
                        // Check that step 2 tool result (54000) is presented in prompt
                        assertTrue(prompt.contains("54000"))
                        "今日からクリスマスまでは108日です。1日500円貯金すると合計54,000円になります。"
                    }
                    else -> "予期しない呼び出しです"
                }
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool(), DateTimeTool()))
        )

        val result = agent.run("今日からクリスマスまでの日数を計算して、1日500円貯金したらいくらになる？")

        assertTrue("Expected Success but got: $result", result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals(3, success.steps.size)
        assertEquals("datetime", success.steps[0].toolCall?.toolName)
        assertEquals("108", success.steps[0].toolResult?.text)
        assertEquals("calculator", success.steps[1].toolCall?.toolName)
        assertEquals("54000", success.steps[1].toolResult?.text)
        assertTrue(success.steps[2].isFinal)
        assertTrue(success.finalAnswer.contains("54,000"))
        assertEquals(AgentState.IDLE, agent.state.value)
    }

    @Test
    fun testToolCallParser_dateTimeJsonFormat() {
        val output = """
            <tool_call>
            {"name": "datetime", "arguments": {"action": "diff_days", "from": "2026-09-08", "to": "2026-12-25"}}
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(output)
        assertNotNull(call)
        assertEquals("datetime", call!!.toolName)
        assertEquals("diff_days", call.arguments["action"])
        assertEquals("2026-09-08", call.arguments["from"])
        assertEquals("2026-12-25", call.arguments["to"])
    }

    @Test
    fun test15_agentSettings_defaultSystemPrompt_usedWhenNotSpecified() = runBlocking {
        var receivedPrompt = ""
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                receivedPrompt = prompt
                return "こんにちは！"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool(), DateTimeTool()))
        )

        val result = agent.run("こんにちは")
        assertTrue(result is AgentResult.Success)
        assertTrue(receivedPrompt.contains("あなたはLLM-PLAYERのAgentです。"))
        assertTrue(receivedPrompt.contains("必要に応じて利用可能なToolを使用してユーザーの質問に回答してください。"))
    }

    @Test
    fun test16_agentSettings_customSystemPrompt_passedToLlm() = runBlocking {
        var receivedPrompt = ""
        val customPrompt = "カスタム指示: 必ず関西弁で回答し、計算はすべて確認すること。"
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                receivedPrompt = prompt
                return "まいど！"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run(
            userPrompt = "こんにちは",
            systemPrompt = customPrompt
        )
        assertTrue(result is AgentResult.Success)
        assertTrue(receivedPrompt.contains(customPrompt))
        assertFalse(receivedPrompt.contains("あなたはLLM-PLAYERのAgentです。"))
    }

    @Test
    fun test17_agentSettings_enableThinkingTrue_passedToLlmRunner() = runBlocking {
        var receivedThinking: Boolean? = null
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                receivedThinking = enableThinking
                return "Thinkingテスト完了"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run(
            userPrompt = "テスト",
            enableThinking = true
        )
        assertTrue(result is AgentResult.Success)
        assertEquals(true, receivedThinking)
    }

    @Test
    fun test18_agentSettings_enableThinkingFalse_passedToLlmRunner_andToolsWorkNormally() = runBlocking {
        var receivedThinking: Boolean? = null
        var callCount = 0
        val mockRunner = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                receivedThinking = enableThinking
                callCount++
                return if (callCount == 1) {
                    """
                    <tool_call>
                    {"name": "calculator", "arguments": {"expression": "20 + 30"}}
                    </tool_call>
                    """.trimIndent()
                } else {
                    "計算結果は50です。"
                }
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run(
            userPrompt = "20 + 30 を計算して",
            enableThinking = false
        )
        assertTrue(result is AgentResult.Success)
        assertEquals(false, receivedThinking)
        val success = result as AgentResult.Success
        assertEquals(2, success.steps.size)
        assertEquals("calculator", success.steps[0].toolCall?.toolName)
        assertEquals("50", success.steps[0].toolResult?.text)
        assertEquals("計算結果は50です。", success.finalAnswer)
    }

    @Test
    fun test19_agentPromptBuilder_structurePreservedWithCustomPrompt() {
        val customPrompt = "カスタムシステム指示です。"
        val tools = listOf(CalculatorTool(), DateTimeTool())
        val previousSteps = listOf(
            AgentStep(
                stepNumber = 1,
                prompt = "step 1 prompt",
                rawLlmOutput = "<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"10 + 20\"}}</tool_call>",
                toolCall = ToolCall(toolName = "calculator", arguments = mapOf("expression" to "10 + 20"), rawCallText = "<tool_call>...</tool_call>"),
                toolResult = ToolExecutionResult.Success("30"),
                isFinal = false
            )
        )

        val prompt = AgentPromptBuilder.buildStepPrompt(
            userMessage = "計算して",
            tools = tools,
            previousSteps = previousSteps,
            systemPrompt = customPrompt
        )

        val idxInstructions = prompt.indexOf("[指示]")
        val idxCustomPrompt = prompt.indexOf(customPrompt)
        val idxTools = prompt.indexOf("利用可能なツール一覧:")
        val idxFormat = prompt.indexOf("ツールを呼び出す場合は、以下の形式のみを出力してください:")
        val idxInput = prompt.indexOf("[ユーザーの入力]")
        val idxSteps = prompt.indexOf("[これまでのステップ]")
        val idxAnswer = prompt.indexOf("[アシスタントの回答]")

        assertTrue(idxInstructions != -1)
        assertTrue(idxCustomPrompt != -1)
        assertTrue(idxTools != -1)
        assertTrue(idxFormat != -1)
        assertTrue(idxInput != -1)
        assertTrue(idxSteps != -1)
        assertTrue(idxAnswer != -1)

        // Verify order: [指示] -> customPrompt -> ツール一覧 -> 形式 -> [ユーザーの入力] -> [これまでのステップ] -> [アシスタントの回答]
        assertTrue(idxInstructions < idxCustomPrompt)
        assertTrue(idxCustomPrompt < idxTools)
        assertTrue(idxTools < idxFormat)
        assertTrue(idxFormat < idxInput)
        assertTrue(idxInput < idxSteps)
        assertTrue(idxSteps < idxAnswer)
    }
}
