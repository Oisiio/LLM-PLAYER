package com.example.agent

import com.example.agent.tools.CalculatorTool
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
}
