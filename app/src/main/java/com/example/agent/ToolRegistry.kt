package com.example.agent

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val _tools = mutableMapOf<String, Tool>()

    init {
        tools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        _tools[tool.name.lowercase()] = tool
    }

    fun getTool(name: String): Tool? = _tools[name.lowercase()]

    fun getAllTools(): List<Tool> = _tools.values.toList()
}
