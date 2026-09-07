package com.example.agent

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

sealed class ToolExecutionResult {
    data class Success(val output: String) : ToolExecutionResult()
    data class Error(val errorMessage: String) : ToolExecutionResult()

    val isSuccess: Boolean get() = this is Success
    val text: String get() = when (this) {
        is Success -> output
        is Error -> errorMessage
    }
}

interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>
    suspend fun execute(arguments: Map<String, String>): ToolExecutionResult
}
