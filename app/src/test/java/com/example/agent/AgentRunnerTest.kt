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
        assertEquals("MAX_STEPS", maxResult.steps.last().metrics?.stopReason)
        assertTrue(logs.contains("[Agent] stop"))
    }

    @Test
    fun testMaxSteps_normalCompletionWithin5Steps() = runBlocking {
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
                    1 -> "<tool_call>calculator: 2 + 3</tool_call>"
                    2 -> "計算結果は 5 です。"
                    else -> "Unexpected step $callCount"
                }
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run("2 + 3 を計算して")
        assertTrue("Expected Success result", result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("計算結果は 5 です。", success.finalAnswer)
        assertEquals(2, success.steps.size)
        assertEquals(2, callCount)
        assertEquals("TOOL_CALL", success.steps[0].metrics?.stopReason)
        assertEquals("EOG", success.steps[1].metrics?.stopReason)
        assertTrue(success.steps[1].isFinal)
    }

    @Test
    fun testMaxSteps_stopsAtStep5AndNeverExecutesStep6() = runBlocking {
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
                return "<tool_call>calculator: $callCount + 1</tool_call>"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        // Pass maxSteps = 10, but hard limit MAX_AGENT_STEPS = 5 must restrict to 5
        val result = agent.run("無限計算タスク", maxSteps = 10)
        assertTrue("Expected MaxStepsReached result", result is AgentResult.MaxStepsReached)
        val maxResult = result as AgentResult.MaxStepsReached
        assertEquals(5, maxResult.steps.size)
        assertEquals(5, callCount) // Step 6 must NEVER be executed
        assertNotNull(maxResult.benchmarkSummary)
        assertEquals(5, maxResult.benchmarkSummary?.stepCount)

        // Steps 1..4 should have TOOL_CALL stopReason, Step 5 should have MAX_STEPS stopReason
        for (i in 0..3) {
            assertEquals("TOOL_CALL", maxResult.steps[i].metrics?.stopReason)
        }
        assertEquals("MAX_STEPS", maxResult.steps[4].metrics?.stopReason)
    }

    @Test
    fun testMaxSteps_unregisteredToolError_doesNotInfiniteLoopAndStopsAtStep5() = runBlocking {
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
                return "<tool_call>non_existent_tool: arg_$callCount</tool_call>"
            }
        }

        val agent = AgentRunner(
            llmRunner = mockRunner,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = agent.run("未登録ツール呼び出しループテスト", maxSteps = 8)
        assertTrue("Expected MaxStepsReached result", result is AgentResult.MaxStepsReached)
        val maxResult = result as AgentResult.MaxStepsReached
        assertEquals("Agent stopped: maximum step limit reached", maxResult.finalAnswer)
        assertEquals(5, maxResult.steps.size)
        assertEquals(5, callCount) // No infinite loop, stops strictly at 5

        // Verify Step 5 captured the unregistered tool call and error result
        val step5 = maxResult.steps[4]
        assertEquals(5, step5.stepNumber)
        assertNotNull(step5.toolCall)
        assertEquals("non_existent_tool", step5.toolCall?.toolName)
        assertTrue(step5.toolResult is ToolExecutionResult.Error)
        val err = step5.toolResult as ToolExecutionResult.Error
        assertTrue(err.errorMessage.contains("Tool 'non_existent_tool' is not registered"))
        assertEquals("MAX_STEPS", step5.metrics?.stopReason)

        // Benchmark summary is fully populated
        assertNotNull(maxResult.benchmarkSummary)
        assertEquals(5, maxResult.benchmarkSummary?.stepCount)
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

    @Test
    fun testAgentBenchmarkSummary_isPopulatedOnSuccess() = runBlocking {
        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                onMetrics?.invoke(
                    TalkDebugMetrics(
                        promptTokens = 120,
                        promptTimeMs = 1500.0,
                        ttftMs = 1500.0,
                        genTokens = 25,
                        genTimeMs = 2000.0,
                        speedTokPerSec = 12.5
                    )
                )
                return "The answer is 42."
            }

            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                return runStreamingInference(
                    prompt, temperature, topK, topP, minP, typicalP, repetitionPenalty,
                    penaltyLastN, seed, enableThinking, onToken, onTtft, onMetrics
                )
            }
        }

        val runner = AgentRunner(fakeLlm)
        val result = runner.run("Calculate something")

        assertTrue(result is AgentResult.Success)
        val summary = result.benchmarkSummary
        assertNotNull("Benchmark summary should be present", summary)
        assertEquals(1, summary!!.stepCount)
        assertEquals(120, summary.totalPromptTokens)
        assertEquals(25, summary.totalGenTokens)
        assertTrue(summary.totalTimeMs > 0.0)

        val step = result.steps.first()
        assertNotNull(step.metrics)
        assertEquals(120, step.metrics!!.promptTokens)
        assertEquals(1500.0, step.metrics!!.promptTimeMs, 0.001)
        assertEquals(25, step.metrics!!.genTokens)
        assertEquals(12.5, step.metrics!!.speedTokPerSec, 0.001)
    }

    @Test
    fun testAgentPrefixCaching_tracksCachedTokensAndPassesSessionId() = runBlocking {
        var clearCacheCount = 0
        val sessionIdsReceived = mutableListOf<String>()
        val prefixCacheFlagsReceived = mutableListOf<Boolean>()
        var stepCount = 0

        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override fun clearAgentPrefixCache() {
                clearCacheCount++
            }

            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String = ""

            override suspend fun runStreamingInferenceForAgent(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int,
                sessionId: String, enablePrefixCache: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                stepCount++
                sessionIdsReceived.add(sessionId)
                prefixCacheFlagsReceived.add(enablePrefixCache)

                if (stepCount == 1) {
                    // Step 1: Cold start, cachedTokens = 0
                    onMetrics?.invoke(
                        TalkDebugMetrics(
                            promptTokens = 500,
                            cachedTokens = 0,
                            newPromptTokens = 500,
                            promptTimeMs = 12000.0,
                            genTokens = 200,
                            genTimeMs = 15000.0
                        )
                    )
                    return "<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"1+1\"}}</tool_call>"
                } else {
                    // Step 2: Warm start with Prefix Caching! cachedTokens = 500, newPromptTokens = 100
                    onMetrics?.invoke(
                        TalkDebugMetrics(
                            promptTokens = 600,
                            cachedTokens = 500,
                            newPromptTokens = 100,
                            promptTimeMs = 2400.0, // Dramatically reduced prompt time!
                            genTokens = 50,
                            genTimeMs = 4000.0
                        )
                    )
                    return "答えは2です。"
                }
            }
        }

        val runner = AgentRunner(
            llmRunner = fakeLlm,
            toolRegistry = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = runner.run(
            userPrompt = "1+1を計算して",
            samplingConfig = AgentSamplingConfig(enablePrefixCache = true)
        )

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("答えは2です。", success.finalAnswer)
        assertEquals(2, success.steps.size)

        // Verify Prefix Cache was cleared on start and finish (at least 2 times)
        assertTrue("clearAgentPrefixCache should be called at start and end", clearCacheCount >= 2)

        // Verify sessionId is non-empty and identical across all steps of the run
        assertEquals(2, sessionIdsReceived.size)
        assertTrue(sessionIdsReceived[0].isNotBlank())
        assertEquals(sessionIdsReceived[0], sessionIdsReceived[1])
        assertTrue(prefixCacheFlagsReceived.all { it })

        // Verify Step 1 metrics
        val step1 = success.steps[0]
        assertEquals(500, step1.metrics?.promptTokens)
        assertEquals(0, step1.metrics?.cachedTokens)
        assertEquals(500, step1.metrics?.newPromptTokens)
        assertEquals(12000.0, step1.metrics?.promptTimeMs ?: 0.0, 0.01)

        // Verify Step 2 metrics (Prefix reused!)
        val step2 = success.steps[1]
        assertEquals(600, step2.metrics?.promptTokens)
        assertEquals(500, step2.metrics?.cachedTokens)
        assertEquals(100, step2.metrics?.newPromptTokens)
        assertEquals(2400.0, step2.metrics?.promptTimeMs ?: 0.0, 0.01)

        // Verify summary
        val summary = success.benchmarkSummary
        assertNotNull(summary)
        assertEquals(1100, summary!!.totalPromptTokens)
        assertEquals(500, summary.totalCachedTokens)
        assertEquals(600, summary.totalNewPromptTokens)
    }

    @Test
    fun testExtractThoughtInfo_step1ThinkingOn_withEndTag() {
        val raw = "Thinking about the calculation...\n</think>\n答えは42です。"
        val (thought, isPrefilled, isCompleted) = AgentRunner.extractThoughtInfo(
            rawOutput = raw,
            isFirstStep = true,
            enableThinking = true
        )
        assertEquals("Thinking about the calculation...", thought)
        assertTrue(isPrefilled)
        assertTrue(isCompleted)
    }

    @Test
    fun testExtractThoughtInfo_step1ThinkingOn_withoutEndTag() {
        val raw = "Thinking about the calculation and ran out of budget..."
        val (thought, isPrefilled, isCompleted) = AgentRunner.extractThoughtInfo(
            rawOutput = raw,
            isFirstStep = true,
            enableThinking = true
        )
        assertEquals("Thinking about the calculation and ran out of budget...", thought)
        assertTrue(isPrefilled)
        assertFalse(isCompleted)
    }

    @Test
    fun testExtractThoughtInfo_step2_withTags() {
        val raw = "<think>\nStep 2 reasoning\n</think>\nFinal result"
        val (thought, isPrefilled, isCompleted) = AgentRunner.extractThoughtInfo(
            rawOutput = raw,
            isFirstStep = false,
            enableThinking = true
        )
        assertEquals("Step 2 reasoning", thought)
        assertFalse(isPrefilled)
        assertTrue(isCompleted)
    }

    @Test
    fun testExtractThoughtInfo_thinkingOff() {
        val raw = "Regular answer without thinking"
        val (thought, isPrefilled, isCompleted) = AgentRunner.extractThoughtInfo(
            rawOutput = raw,
            isFirstStep = true,
            enableThinking = false
        )
        assertNull(thought)
        assertFalse(isPrefilled)
        assertFalse(isCompleted)
    }

    @Test
    fun testRunner_step1ThinkingOn_extractsThoughtAndCleansAnswer() = runBlocking {
        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun clearAgentPrefixCache() {}
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String = ""

            override suspend fun runStreamingInferenceForAgent(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int,
                sessionId: String, enablePrefixCache: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String {
                val tokens = listOf("Calcul", "ating...\n", "</think>\n", "答え", "は42", "です。")
                tokens.forEach { onToken(it) }
                return tokens.joinToString("")
            }
        }

        val runner = AgentRunner(llmRunner = fakeLlm)
        val thoughtUpdates = mutableListOf<Triple<String, Boolean, Boolean>>()

        val result = runner.run(
            userPrompt = "計算して",
            enableThinking = true,
            onThoughtUpdate = { thought, isPrefilled, isThinking ->
                thoughtUpdates.add(Triple(thought, isPrefilled, isThinking))
            }
        )

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("答えは42です。", success.finalAnswer)
        assertEquals(1, success.steps.size)

        val step1 = success.steps[0]
        assertEquals("Calculating...", step1.thoughtText)
        assertTrue(step1.isThoughtPrefilled)
        assertTrue(step1.isThoughtCompleted)

        // Verify thought updates were dispatched
        assertTrue(thoughtUpdates.isNotEmpty())
        // Last update should have isThinking = false after </think>
        val lastUpdate = thoughtUpdates.last()
        assertEquals("Calculating...\n", lastUpdate.first)
        assertTrue(lastUpdate.second) // isPrefilled
        assertFalse(lastUpdate.third) // isThinking completed
    }

    @Test
    fun testStep1ThinkingDisabled_step1HasThinkingOff_step2HasThinkingOn() = runBlocking {
        val capturedCalls = mutableListOf<Pair<Boolean, Int>>() // Pair<enableThinking, thinkingBudget>

        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String = ""

            override suspend fun runAgentSessionInit(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int, sessionId: String, enableDiagnostics: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?,
                onDiagnostics: ((StepDiagnostics) -> Unit)?
            ): String {
                capturedCalls.add(Pair(enableThinking, thinkingBudget))
                // Step 1: emit a tool call immediately without thinking
                val toolCallJson = """
                    <tool_call>
                    {"name": "datetime", "arguments": {"action": "today"}}
                    </tool_call>
                """.trimIndent()
                onToken(toolCallJson)
                return toolCallJson
            }

            override suspend fun runAgentSessionAppend(
                deltaPrompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int, sessionId: String, enableDiagnostics: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?,
                onDiagnostics: ((StepDiagnostics) -> Unit)?
            ): String {
                capturedCalls.add(Pair(enableThinking, thinkingBudget))
                // Step 2: final answer with thinking enabled
                val response = "今日は2026年9月24日です。"
                onToken(response)
                return response
            }
        }

        val runner = AgentRunner(
            llmRunner = fakeLlm,
            toolRegistry = ToolRegistry(listOf(DateTimeTool()))
        )

        val result = runner.run(
            userPrompt = "今日は何日？",
            enableThinking = true,
            thinkingBudget = 1024,
            disableStep1Thinking = true
        )

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals("今日は2026年9月24日です。", success.finalAnswer)
        assertEquals(2, success.steps.size)

        // Verify captured calls: Step 1 had enableThinking=false, Step 2 had enableThinking=true
        assertEquals(2, capturedCalls.size)
        // Step 1: Thinking OFF
        assertEquals(false, capturedCalls[0].first)
        assertEquals(0, capturedCalls[0].second)
        // Step 2: Thinking ON (restored to original enableThinking=true, budget=1024)
        assertEquals(true, capturedCalls[1].first)
        assertEquals(1024, capturedCalls[1].second)

        // Verify metrics
        val step1Metrics = success.steps[0].metrics
        assertNotNull(step1Metrics)
        assertEquals(0, step1Metrics!!.reasoningBudget)
        assertEquals("TOOL_CALL", step1Metrics.stopReason)
        assertEquals("datetime", step1Metrics.toolName)

        val step2Metrics = success.steps[1].metrics
        assertNotNull(step2Metrics)
        assertEquals(1024, step2Metrics!!.reasoningBudget)
        assertEquals("EOG", step2Metrics.stopReason)
        assertNull(step2Metrics.toolName)
    }

    @Test
    fun testStep1ThinkingDisabled_whenNoToolsAvailable_thinkingRemainsEnabled() = runBlocking {
        var capturedEnableThinking: Boolean? = null

        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String = ""

            override suspend fun runAgentSessionInit(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int, sessionId: String, enableDiagnostics: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?,
                onDiagnostics: ((StepDiagnostics) -> Unit)?
            ): String {
                capturedEnableThinking = enableThinking
                return "こんにちは！"
            }
        }

        // Empty tool registry
        val runner = AgentRunner(
            llmRunner = fakeLlm,
            toolRegistry = ToolRegistry(emptyList())
        )

        val result = runner.run(
            userPrompt = "こんにちは",
            enableThinking = true,
            disableStep1Thinking = true // Requested, but no tools available
        )

        assertTrue(result is AgentResult.Success)
        // Since no tools are registered, thinking should remain enabled (not disabled)
        assertEquals(true, capturedEnableThinking)
    }

    @Test
    fun testChristmasSavingsScenario_withStep1ThinkingDisabled_benchmarkMetricsComplete() = runBlocking {
        val capturedStepParams = mutableListOf<Pair<Boolean, Int>>()

        val fakeLlm = object : LlmStreamRunner {
            override fun isModelLoaded(): Boolean = true
            override fun cancelGeneration() {}
            override suspend fun runStreamingInference(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?
            ): String = ""

            private var stepCount = 0

            override suspend fun runAgentSessionInit(
                prompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int, sessionId: String, enableDiagnostics: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?,
                onDiagnostics: ((StepDiagnostics) -> Unit)?
            ): String {
                stepCount++
                capturedStepParams.add(Pair(enableThinking, thinkingBudget))
                onMetrics?.invoke(TalkDebugMetrics(
                    ttftMs = 120.0, promptTokens = 520, genTokens = 35,
                    promptTimeMs = 120.0, genTimeMs = 800.0, totalTimeMs = 920.0,
                    speedTokPerSec = 43.75
                ))
                // Step 1: datetime tool call with Thinking OFF
                val out = """<tool_call>{"name": "datetime", "arguments": {"action": "today"}}</tool_call>"""
                onToken(out)
                return out
            }

            override suspend fun runAgentSessionAppend(
                deltaPrompt: String, temperature: Float, topK: Int, topP: Float,
                minP: Float, typicalP: Float, repetitionPenalty: Float,
                penaltyLastN: Int, seed: Long, enableThinking: Boolean,
                thinkingBudget: Int, sessionId: String, enableDiagnostics: Boolean,
                onToken: (String) -> Unit, onTtft: ((Double) -> Unit)?,
                onMetrics: ((TalkDebugMetrics) -> Unit)?,
                onDiagnostics: ((StepDiagnostics) -> Unit)?
            ): String {
                stepCount++
                capturedStepParams.add(Pair(enableThinking, thinkingBudget))
                if (stepCount == 2) {
                    onMetrics?.invoke(TalkDebugMetrics(
                        ttftMs = 45.0, promptTokens = 580, genTokens = 40,
                        promptTimeMs = 45.0, genTimeMs = 900.0, totalTimeMs = 945.0,
                        speedTokPerSec = 44.44
                    ))
                    // Step 2: calculator tool call with Thinking ON
                    val out = """<tool_call>{"name": "calculator", "arguments": {"expression": "92 * 500"}}</tool_call>"""
                    onToken(out)
                    return out
                } else {
                    onMetrics?.invoke(TalkDebugMetrics(
                        ttftMs = 50.0, promptTokens = 650, genTokens = 120,
                        promptTimeMs = 50.0, genTimeMs = 2500.0, totalTimeMs = 2550.0,
                        speedTokPerSec = 48.0
                    ))
                    // Step 3: final answer
                    val out = "今日（9月24日）からクリスマス（12月25日）まではあと92日です。1日500円ずつ貯金すると、合計で46,000円貯まります！"
                    onToken(out)
                    return out
                }
            }
        }

        val runner = AgentRunner(
            llmRunner = fakeLlm,
            toolRegistry = ToolRegistry(listOf(DateTimeTool(), CalculatorTool()))
        )

        val result = runner.run(
            userPrompt = "今日からクリスマスまでの日数を計算して、1日500円貯金したらいくらになる？",
            enableThinking = true,
            thinkingBudget = 1024,
            disableStep1Thinking = true
        )

        assertTrue(result is AgentResult.Success)
        val success = result as AgentResult.Success
        assertEquals(3, success.steps.size)
        assertTrue(success.finalAnswer.contains("46,000円"))

        // Verify Thinking parameters per step:
        // Step 1: Thinking OFF
        assertEquals(false, capturedStepParams[0].first)
        assertEquals(0, capturedStepParams[0].second)
        // Step 2: Thinking ON
        assertEquals(true, capturedStepParams[1].first)
        assertEquals(1024, capturedStepParams[1].second)
        // Step 3: Thinking ON
        assertEquals(true, capturedStepParams[2].first)
        assertEquals(1024, capturedStepParams[2].second)

        // Verify Benchmark Summary & Metrics for each step:
        val summary = success.benchmarkSummary
        assertNotNull(summary)
        assertEquals(3, summary!!.stepCount)

        // Step 1 Metrics
        val s1 = summary.stepMetrics[0]
        assertEquals(1, s1.stepNumber)
        assertEquals(35, s1.genTokens)
        assertEquals(0, s1.reasoningBudget) // Reasoning Budget OFF
        assertEquals("datetime", s1.toolName)
        assertTrue(s1.toolExecutionTimeMs > 0.0 || s1.toolName != null)
        assertEquals("TOOL_CALL", s1.stopReason)
        assertEquals(120.0, s1.ttftMs, 0.01)
        assertTrue(s1.stepTotalTimeMs > 0.0)

        // Step 2 Metrics
        val s2 = summary.stepMetrics[1]
        assertEquals(2, s2.stepNumber)
        assertEquals(40, s2.genTokens)
        assertEquals(1024, s2.reasoningBudget) // Reasoning Budget 1024
        assertEquals("calculator", s2.toolName)
        assertEquals("TOOL_CALL", s2.stopReason)
        assertEquals(45.0, s2.ttftMs, 0.01)

        // Step 3 Metrics
        val s3 = summary.stepMetrics[2]
        assertEquals(3, s3.stepNumber)
        assertEquals(120, s3.genTokens)
        assertEquals(1024, s3.reasoningBudget) // Reasoning Budget 1024
        assertNull(s3.toolName)
        assertEquals("EOG", s3.stopReason)
        assertEquals(50.0, s3.ttftMs, 0.01)
    }
}
