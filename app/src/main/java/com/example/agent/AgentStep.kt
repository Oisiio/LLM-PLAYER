package com.example.agent

enum class AgentState {
    IDLE,
    RUNNING,
    CANCELLING
}

data class AgentStepMetrics(
    val stepNumber: Int,
    val promptTokens: Int = 0,
    val promptTimeMs: Double = 0.0,
    val ttftMs: Double = 0.0,
    val genTokens: Int = 0,
    val genTimeMs: Double = 0.0,
    val speedTokPerSec: Double = 0.0,
    val toolName: String? = null,
    val toolExecutionTimeMs: Double = 0.0,
    val toolStartTime: Long = 0L,
    val toolEndTime: Long = 0L,
    val stepTotalTimeMs: Double = 0.0,
    val cachedTokens: Int = 0,
    val newPromptTokens: Int = promptTokens
)

data class AgentBenchmarkSummary(
    val totalTimeMs: Double = 0.0,
    val stepCount: Int = 0,
    val totalPromptTokens: Int = 0,
    val totalGenTokens: Int = 0,
    val totalToolTimeMs: Double = 0.0,
    val stepMetrics: List<AgentStepMetrics> = emptyList(),
    val totalCachedTokens: Int = 0,
    val totalNewPromptTokens: Int = totalPromptTokens
)

data class AgentStep(
    val stepNumber: Int,
    val prompt: String,
    val rawLlmOutput: String,
    val toolCall: ToolCall? = null,
    val toolResult: ToolExecutionResult? = null,
    val isFinal: Boolean = false,
    val metrics: AgentStepMetrics? = null
)

sealed class AgentResult {
    abstract val steps: List<AgentStep>
    open val benchmarkSummary: AgentBenchmarkSummary? = null

    data class Success(
        val finalAnswer: String,
        override val steps: List<AgentStep>,
        override val benchmarkSummary: AgentBenchmarkSummary? = null
    ) : AgentResult()

    data class MaxStepsReached(
        val finalAnswer: String = "Agent stopped: maximum step limit reached",
        override val steps: List<AgentStep>,
        override val benchmarkSummary: AgentBenchmarkSummary? = null
    ) : AgentResult()

    data class Cancelled(
        val message: String = "Agent stopped by user",
        override val steps: List<AgentStep>,
        override val benchmarkSummary: AgentBenchmarkSummary? = null
    ) : AgentResult()

    data class Error(
        val errorMessage: String,
        override val steps: List<AgentStep>,
        override val benchmarkSummary: AgentBenchmarkSummary? = null
    ) : AgentResult()
}
