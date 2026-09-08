package com.example.agent

enum class AgentState {
    IDLE,
    RUNNING,
    CANCELLING
}

data class AgentStep(
    val stepNumber: Int,
    val prompt: String,
    val rawLlmOutput: String,
    val toolCall: ToolCall? = null,
    val toolResult: ToolExecutionResult? = null,
    val isFinal: Boolean = false
)

sealed class AgentResult {
    abstract val steps: List<AgentStep>

    data class Success(
        val finalAnswer: String,
        override val steps: List<AgentStep>
    ) : AgentResult()

    data class MaxStepsReached(
        val finalAnswer: String = "Agent stopped: maximum step limit reached",
        override val steps: List<AgentStep>
    ) : AgentResult()

    data class Cancelled(
        val message: String = "Agent stopped by user",
        override val steps: List<AgentStep>
    ) : AgentResult()

    data class Error(
        val errorMessage: String,
        override val steps: List<AgentStep>
    ) : AgentResult()
}
