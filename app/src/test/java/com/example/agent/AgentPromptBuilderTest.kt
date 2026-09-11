package com.example.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {

    /**
     * A strict minimal RFC 8259 JSON Object parser for test verification.
     * Validates that the input JSON is well-formed and unescapes string values.
     */
    private fun parseStrictJsonObject(json: String): Map<String, String> {
        val trimmed = json.trim()
        assertTrue("JSON must start with { and end with }: $trimmed", trimmed.startsWith("{") && trimmed.endsWith("}"))

        val content = trimmed.substring(1, trimmed.length - 1).trim()
        val result = mutableMapOf<String, String>()
        var i = 0

        fun skipWhitespace() {
            while (i < content.length && content[i].isWhitespace()) i++
        }

        fun parseString(): String {
            skipWhitespace()
            assertTrue("Expected opening quote at position $i in '$content'", i < content.length && content[i] == '"')
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < content.length) {
                val c = content[i++]
                if (c == '"') {
                    return sb.toString()
                } else if (c == '\\') {
                    assertTrue("Unexpected EOF after escape backslash", i < content.length)
                    when (val esc = content[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            assertTrue("Invalid unicode escape sequence", i + 4 <= content.length)
                            val hex = content.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("Unknown escape character: \\$esc")
                    }
                } else {
                    // Control characters < 0x20 must be escaped in RFC 8259
                    assertTrue("Unescaped control character in JSON string: ${c.code}", c.code >= 0x20)
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unclosed string in JSON")
        }

        while (i < content.length) {
            skipWhitespace()
            if (i >= content.length) break
            val key = parseString()
            skipWhitespace()
            assertTrue("Expected colon after key '$key'", i < content.length && content[i] == ':')
            i++ // skip colon
            skipWhitespace()
            val value = parseString()
            result[key] = value

            skipWhitespace()
            if (i < content.length) {
                assertTrue("Expected comma or end at position $i, found '${content[i]}'", content[i] == ',')
                i++ // skip comma
            }
        }
        return result
    }

    @Test
    fun test1_normalString() {
        val json = AgentPromptBuilder.buildToolResultJson("calculator", "hello", null)
        val parsed = parseStrictJsonObject(json)
        assertEquals("calculator", parsed["tool"])
        assertEquals("hello", parsed["result"])
    }

    @Test
    fun test2_doubleQuote() {
        val input = "hello \"world\""
        val json = AgentPromptBuilder.buildToolResultJson("calculator", input, null)
        assertTrue("JSON must contain escaped quote: $json", json.contains("\\\"world\\\""))
        val parsed = parseStrictJsonObject(json)
        assertEquals("calculator", parsed["tool"])
        assertEquals(input, parsed["result"])
    }

    @Test
    fun test3_backslash() {
        val input = "C:\\test\\file.txt"
        val json = AgentPromptBuilder.buildToolResultJson("file_tool", input, null)
        assertTrue("JSON must contain double backslashes: $json", json.contains("C:\\\\test\\\\file.txt"))
        val parsed = parseStrictJsonObject(json)
        assertEquals("file_tool", parsed["tool"])
        assertEquals(input, parsed["result"])
    }

    @Test
    fun test4_newline() {
        val input = "line1\nline2\r\nline3"
        val json = AgentPromptBuilder.buildToolResultJson("memo_tool", input, null)
        assertTrue("Raw newlines must not appear in JSON literal", !json.contains("\n") && !json.contains("\r"))
        assertTrue("JSON must contain \\n escape", json.contains("\\n"))
        val parsed = parseStrictJsonObject(json)
        assertEquals("memo_tool", parsed["tool"])
        assertEquals(input, parsed["result"])
    }

    @Test
    fun test5_tab() {
        val input = "hello\tworld"
        val json = AgentPromptBuilder.buildToolResultJson("calculator", input, null)
        assertTrue("Raw tab must not appear in JSON literal", !json.contains("\t"))
        assertTrue("JSON must contain \\t escape", json.contains("\\t"))
        val parsed = parseStrictJsonObject(json)
        assertEquals("calculator", parsed["tool"])
        assertEquals(input, parsed["result"])
    }

    @Test
    fun test6_errorMessage_withQuotesAndBackslashesAndNewlines() {
        val inputError = "error: \"invalid syntax\"\npath=C:\\test\\dir\tcode=400"
        val json = AgentPromptBuilder.buildToolResultJson("calculator", null, inputError)
        val parsed = parseStrictJsonObject(json)
        assertEquals("calculator", parsed["tool"])
        assertEquals(inputError, parsed["error"])
    }

    @Test
    fun test7_toolName_withSpecialCharsIsEscaped() {
        val inputTool = "tool\"_name\\test"
        val json = AgentPromptBuilder.buildToolResultJson(inputTool, "success", null)
        val parsed = parseStrictJsonObject(json)
        assertEquals(inputTool, parsed["tool"])
        assertEquals("success", parsed["result"])
    }

    @Test
    fun test8_buildStepPrompt_integrationWithEscapedResults() {
        val step1 = AgentStep(
            stepNumber = 1,
            prompt = "dummy",
            rawLlmOutput = "<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"2+2\"}}</tool_call>",
            toolCall = ToolCall(toolName = "calculator", arguments = mapOf("expression" to "2+2"), rawCallText = "<tool_call>...</tool_call>"),
            toolResult = ToolExecutionResult.Success("4 \"units\" (exact)\nline2: C:\\results\\out.txt"),
            isFinal = false
        )
        val step2 = AgentStep(
            stepNumber = 2,
            prompt = "dummy",
            rawLlmOutput = "<tool_call>{\"name\": \"datetime\", \"arguments\": {\"action\": \"today\"}}</tool_call>",
            toolCall = ToolCall(toolName = "datetime", arguments = mapOf("action" to "today"), rawCallText = "<tool_call>...</tool_call>"),
            toolResult = ToolExecutionResult.Error("Error: \"Date failed\"\nretry=true"),
            isFinal = false
        )

        val prompt = AgentPromptBuilder.buildStepPrompt(
            userMessage = "テストプロンプト",
            tools = emptyList(),
            previousSteps = listOf(step1, step2)
        )

        assertTrue(prompt.contains("<tool_result>"))
        assertTrue(prompt.contains("</tool_result>"))
        assertTrue(prompt.contains("4 \\\"units\\\" (exact)\\nline2: C:\\\\results\\\\out.txt"))
        assertTrue(prompt.contains("Error: \\\"Date failed\\\"\\nretry=true"))

        // Extract JSON inside <tool_result> and verify it parses cleanly
        val toolResultRegex = Regex("<tool_result>\\s*([\\s\\S]*?)\\s*</tool_result>")
        val matches = toolResultRegex.findAll(prompt).toList()
        assertEquals(2, matches.size)

        val parsed1 = parseStrictJsonObject(matches[0].groupValues[1])
        assertEquals("calculator", parsed1["tool"])
        assertEquals("4 \"units\" (exact)\nline2: C:\\results\\out.txt", parsed1["result"])

        val parsed2 = parseStrictJsonObject(matches[1].groupValues[1])
        assertEquals("datetime", parsed2["tool"])
        assertEquals("Error: \"Date failed\"\nretry=true", parsed2["error"])
    }
}
