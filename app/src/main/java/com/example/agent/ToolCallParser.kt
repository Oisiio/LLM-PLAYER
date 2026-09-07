package com.example.agent

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, String>,
    val rawCallText: String
)

object ToolCallParser {

    private val TOOL_CALL_TAG_REGEX = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|$)", RegexOption.IGNORE_CASE)

    fun parse(llmOutput: String): ToolCall? {
        val match = TOOL_CALL_TAG_REGEX.find(llmOutput) ?: return null
        val innerContent = match.groupValues[1].trim()
        if (innerContent.isBlank()) return null

        val cleaned = stripMarkdownCodeBlock(innerContent)

        // 1. Try parsing JSON-like structures
        val jsonToolCall = tryParseJson(cleaned, match.value)
        if (jsonToolCall != null) {
            return jsonToolCall
        }

        // 2. Try line / colon based parsing
        return tryParseTextFormat(cleaned, match.value)
    }

    private fun stripMarkdownCodeBlock(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            val firstLineEnd = s.indexOf('\n')
            if (firstLineEnd != -1) {
                s = s.substring(firstLineEnd + 1)
            } else {
                s = s.removePrefix("```")
            }
            if (s.endsWith("```")) {
                s = s.removeSuffix("```").trim()
            }
        }
        return s.trim()
    }

    private fun tryParseJson(content: String, rawCallText: String): ToolCall? {
        // Simple, resilient key-value / JSON extractor without Android-specific or third-party dependencies
        // Handles {"name": "calculator", "arguments": {"expression": "..."}}
        // Handles {"tool": "calculator", "expression": "..."}
        if (!content.contains("{") || !content.contains("}")) return null

        val name = extractStringProperty(content, "name")
            ?: extractStringProperty(content, "tool")
            ?: extractFirstWordBeforeJson(content)
            ?: return null

        val args = mutableMapOf<String, String>()

        // Look for expression directly
        val directExpression = extractStringProperty(content, "expression")
            ?: extractStringProperty(content, "expr")
            ?: extractStringProperty(content, "input")

        if (directExpression != null) {
            args["expression"] = directExpression
        } else {
            // Check inside "arguments": { ... }
            val argsBlock = extractJsonObject(content, "arguments")
            if (argsBlock != null) {
                val expr = extractStringProperty(argsBlock, "expression")
                    ?: extractStringProperty(argsBlock, "expr")
                    ?: extractStringProperty(argsBlock, "input")
                if (expr != null) {
                    args["expression"] = expr
                }
            }
        }

        if (args.isEmpty()) {
            // If tool is calculator and no key matched, look for any string value
            val firstVal = extractAnyStringValue(content)
            if (firstVal != null && firstVal != name) {
                args["expression"] = firstVal
            }
        }

        return ToolCall(
            toolName = name.lowercase().trim(),
            arguments = args,
            rawCallText = rawCallText
        )
    }

    private fun tryParseTextFormat(content: String, rawCallText: String): ToolCall? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val firstLine = lines.first()

        // Case: "calculator: 12345 * 678"
        if (firstLine.contains(":")) {
            val parts = firstLine.split(":", limit = 2)
            val toolName = parts[0].trim().lowercase()
            val expr = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (toolName.isNotBlank() && expr.isNotBlank()) {
                return ToolCall(
                    toolName = toolName,
                    arguments = mapOf("expression" to expr),
                    rawCallText = rawCallText
                )
            }
        }

        // Case:
        // calculator
        // expression: 12345 * 678
        val toolName = firstLine.lowercase().trim()
        val args = mutableMapOf<String, String>()

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.contains(":")) {
                val p = line.split(":", limit = 2)
                val key = p[0].trim().lowercase()
                val value = p[1].trim().removeSurrounding("\"").removeSurrounding("'")
                args[key] = value
            } else if (line.isNotBlank()) {
                args["expression"] = line.removeSurrounding("\"").removeSurrounding("'")
            }
        }

        if (args.isEmpty() && lines.size == 1 && !firstLine.contains(" ")) {
            // Just tool name without arguments
            return ToolCall(toolName = toolName, arguments = emptyMap(), rawCallText = rawCallText)
        }

        return ToolCall(
            toolName = toolName,
            arguments = args,
            rawCallText = rawCallText
        )
    }

    private fun extractFirstWordBeforeJson(text: String): String? {
        val jsonStart = text.indexOf('{')
        if (jsonStart > 0) {
            val prefix = text.substring(0, jsonStart).trim()
            if (prefix.isNotEmpty() && prefix.all { it.isLetterOrDigit() || it == '_' }) {
                return prefix
            }
        }
        return null
    }

    private fun extractStringProperty(json: String, propName: String): String? {
        val pattern = Regex("\"$propName\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        val m = pattern.find(json)
        if (m != null) return m.groupValues[1]

        // Also check unquoted values or single quotes
        val singlePattern = Regex("'$propName'\\s*:\\s*'([^']*)'", RegexOption.IGNORE_CASE)
        val sm = singlePattern.find(json)
        if (sm != null) return sm.groupValues[1]

        return null
    }

    private fun extractJsonObject(json: String, propName: String): String? {
        val keyIndex = json.indexOf("\"$propName\"")
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex)
        if (colonIndex == -1) return null
        val braceStart = json.indexOf('{', colonIndex)
        if (braceStart == -1) return null

        var depth = 0
        for (i in braceStart until json.length) {
            if (json[i] == '{') depth++
            else if (json[i] == '}') {
                depth--
                if (depth == 0) {
                    return json.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    private fun extractAnyStringValue(json: String): String? {
        val pattern = Regex(":\\s*\"([^\"]+)\"")
        val m = pattern.find(json)
        return m?.groupValues?.get(1)
    }

    fun removeToolCallTags(text: String): String {
        return text.replace(TOOL_CALL_TAG_REGEX, "").trim()
    }
}
