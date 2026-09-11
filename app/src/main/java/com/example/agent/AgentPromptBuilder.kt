package com.example.agent

object AgentPromptBuilder {

    const val DEFAULT_SYSTEM_PROMPT = """あなたはLLM-PLAYERのAgentです。

必要に応じて利用可能なToolを使用してユーザーの質問に回答してください。

正確な計算が必要な場合はcalculatorを使用してください。
現在の日付・時刻・曜日が必要な場合はdatetimeを使用してください。

複数のToolが必要な場合は、必要なToolを順番に使用してください。
Toolの実行結果を推測や暗算で置き換えないでください。

必要なToolの処理がすべて完了してから、最終回答を生成してください。"""

    fun buildStepPrompt(
        userMessage: String,
        tools: List<Tool>,
        previousSteps: List<AgentStep>,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    ): String {
        val sb = StringBuilder()

        // 1. System instructions
        sb.append("[指示]\n")
        sb.append(systemPrompt.trim()).append("\n\n")

        sb.append("利用可能なツール一覧:\n")
        for (tool in tools) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n")
            if (tool.parameters.isNotEmpty()) {
                val paramsDesc = tool.parameters.joinToString(", ") { "${it.name} (${it.type}): ${it.description}" }
                sb.append("  引数: ").append(paramsDesc).append("\n")
            }
        }
        sb.append("\n")

        sb.append("ツールを呼び出す場合は、以下の形式のみを出力してください:\n")
        sb.append("<tool_call>\n")
        sb.append("{\"name\": \"ツール名\", \"arguments\": {\"引数名\": \"値\"}}\n")
        sb.append("</tool_call>\n\n")

        sb.append("ツールの呼び出しが不要な場合（挨拶や一般的な会話など）、またはツール実行結果を受け取った後は、通常の文章でユーザーに最終回答を伝えてください。\n\n")

        // 2. User Question
        sb.append("[ユーザーの入力]\n")
        sb.append(userMessage.trim()).append("\n\n")

        // 3. Previous Steps history (Tool Calls & Results)
        if (previousSteps.isNotEmpty()) {
            sb.append("[これまでのステップ]\n")
            for (step in previousSteps) {
                if (step.toolCall != null) {
                    sb.append("アシスタント思考/呼び出し:\n")
                    sb.append(step.toolCall.rawCallText.trim()).append("\n")
                    val result = step.toolResult
                    if (result != null) {
                        sb.append("ツール実行結果:\n")
                        when (result) {
                            is ToolExecutionResult.Success -> {
                                sb.append("<tool_result>\n")
                                sb.append(buildToolResultJson(step.toolCall.toolName, result.output, null)).append("\n")
                                sb.append("</tool_result>\n")
                            }
                            is ToolExecutionResult.Error -> {
                                sb.append("<tool_result>\n")
                                sb.append(buildToolResultJson(step.toolCall.toolName, null, result.errorMessage)).append("\n")
                                sb.append("</tool_result>\n")
                            }
                        }
                    }
                }
            }
            sb.append("\n上記の結果を踏まえて、ユーザーへの最終回答を作成してください。\n")
        }

        sb.append("[アシスタントの回答]\n")
        return sb.toString()
    }

    /**
     * Escapes a string to be safely embedded inside a JSON string literal according to RFC 8259.
     */
    fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Builds a safe JSON string representing a tool result for prompt history.
     */
    fun buildToolResultJson(toolName: String, output: String?, errorMessage: String?): String {
        val escapedTool = escapeJsonString(toolName)
        return if (errorMessage != null) {
            "{\"tool\": \"$escapedTool\", \"error\": \"${escapeJsonString(errorMessage)}\"}"
        } else {
            "{\"tool\": \"$escapedTool\", \"result\": \"${escapeJsonString(output ?: "")}\"}"
        }
    }
}
