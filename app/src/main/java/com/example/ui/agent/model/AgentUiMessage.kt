package com.example.ui.agent.model

import com.example.agent.AgentBenchmarkSummary
import com.example.agent.AgentStep

enum class AgentMessageRole {
    USER,
    ASSISTANT
}

data class AgentUiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: AgentMessageRole,
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    val isRunning: Boolean = false,
    val currentStatus: String? = null,
    val isError: Boolean = false,
    val benchmarkSummary: AgentBenchmarkSummary? = null,
    val timestamp: Long = System.currentTimeMillis()
)
