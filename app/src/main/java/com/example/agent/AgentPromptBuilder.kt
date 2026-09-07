package com.example.agent

object AgentPromptBuilder {

    fun buildStepPrompt(
        userMessage: String,
        tools: List<Tool>,
        previousSteps: List<AgentStep>
    ): String {
        val sb = StringBuilder()

        // 1. System instructions
        sb.append("[指示]\n")
        sb.append("あなたは役立つアシスタントです。必要に応じて提供されたツールを呼び出してユーザーの質問に回答してください。\n\n")

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
                                sb.append("{\"tool\": \"").append(step.toolCall.toolName).append("\", \"result\": \"").append(result.output).append("\"}\n")
                                sb.append("</tool_result>\n")
                            }
                            is ToolExecutionResult.Error -> {
                                sb.append("<tool_result>\n")
                                sb.append("{\"tool\": \"").append(step.toolCall.toolName).append("\", \"error\": \"").append(result.errorMessage).append("\"}\n")
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
}
