package com.example.agent

import com.example.agent.tools.CalculatorTool
import com.example.ui.talk.LlmStreamRunner
import java.util.concurrent.atomic.AtomicBoolean

data class AgentSamplingConfig(
    val temperature: Float = 0.2f, // Agent works best with lower temperature for reliable tool formatting
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.0f,
    val typicalP: Float = 1.0f,
    val repetitionPenalty: Float = 1.1f,
    val penaltyLastN: Int = 64,
    val seed: Long = 12345L
)

class AgentRunner(
    private val llmRunner: LlmStreamRunner,
    val toolRegistry: ToolRegistry = ToolRegistry(listOf(CalculatorTool())),
    private val logger: AgentLogger = AgentLogger.Default
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 5
    }

    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        if (!isCancelled.getAndSet(true)) {
            llmRunner.cancelGeneration()
            logger.log("[Agent] stop")
        }
    }

    suspend fun run(
        userPrompt: String,
        samplingConfig: AgentSamplingConfig = AgentSamplingConfig(),
        maxSteps: Int = DEFAULT_MAX_STEPS,
        onStepUpdate: ((AgentStep) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null
    ): AgentResult {
        isCancelled.set(false)
        logger.log("[Agent] start")

        if (!llmRunner.isModelLoaded()) {
            val err = "ERROR: Model not loaded."
            logger.log("[Agent] error=$err")
            return AgentResult.Error(err, emptyList())
        }

        val steps = mutableListOf<AgentStep>()

        for (stepNum in 1..maxSteps) {
            if (isCancelled.get()) {
                logger.log("[Agent] stop")
                return AgentResult.Cancelled(steps = steps)
            }

            logger.log("[Agent] step=$stepNum")

            val prompt = AgentPromptBuilder.buildStepPrompt(
                userMessage = userPrompt,
                tools = toolRegistry.getAllTools(),
                previousSteps = steps
            )

            val textAccumulator = StringBuilder()
            val rawOutput = llmRunner.runStreamingInference(
                prompt = prompt,
                temperature = samplingConfig.temperature,
                topK = samplingConfig.topK,
                topP = samplingConfig.topP,
                minP = samplingConfig.minP,
                typicalP = samplingConfig.typicalP,
                repetitionPenalty = samplingConfig.repetitionPenalty,
                penaltyLastN = samplingConfig.penaltyLastN,
                seed = samplingConfig.seed,
                enableThinking = false,
                onToken = { token ->
                    if (!isCancelled.get()) {
                        textAccumulator.append(token)
                        onToken?.invoke(token)
                    }
                }
            )

            if (isCancelled.get()) {
                logger.log("[Agent] stop")
                return AgentResult.Cancelled(steps = steps)
            }

            if (rawOutput.startsWith("ERROR:")) {
                logger.log("[Agent] error=$rawOutput")
                return AgentResult.Error(rawOutput, steps)
            }

            // Check if a tool call is present in the LLM output
            val toolCall = ToolCallParser.parse(rawOutput)

            if (toolCall == null) {
                // No tool call requested -> Final Answer reached
                logger.log("[Agent] final")
                val cleanAnswer = ToolCallParser.removeToolCallTags(rawOutput).trim()
                val finalStep = AgentStep(
                    stepNumber = stepNum,
                    prompt = prompt,
                    rawLlmOutput = rawOutput,
                    toolCall = null,
                    toolResult = null,
                    isFinal = true
                )
                steps.add(finalStep)
                onStepUpdate?.invoke(finalStep)
                return AgentResult.Success(cleanAnswer.ifEmpty { rawOutput }, steps)
            }

            // Tool call was detected
            logger.log("[Agent] tool=${toolCall.toolName}")
            val expr = toolCall.arguments["expression"]
                ?: toolCall.arguments.values.firstOrNull()
                ?: ""
            logger.log("[Agent] expression=$expr")

            val tool = toolRegistry.getTool(toolCall.toolName)
            val toolResult = if (tool == null) {
                val notFoundErr = "Tool '${toolCall.toolName}' is not registered."
                logger.log("[Agent] error=$notFoundErr")
                ToolExecutionResult.Error(notFoundErr)
            } else {
                if (isCancelled.get()) {
                    logger.log("[Agent] stop")
                    return AgentResult.Cancelled(steps = steps)
                }
                tool.execute(toolCall.arguments)
            }

            val resultLog = when (toolResult) {
                is ToolExecutionResult.Success -> toolResult.output
                is ToolExecutionResult.Error -> {
                    logger.log("[Agent] error=${toolResult.errorMessage}")
                    toolResult.errorMessage
                }
            }
            logger.log("[Agent] result=$resultLog")

            val currentStep = AgentStep(
                stepNumber = stepNum,
                prompt = prompt,
                rawLlmOutput = rawOutput,
                toolCall = toolCall,
                toolResult = toolResult,
                isFinal = false
            )
            steps.add(currentStep)
            onStepUpdate?.invoke(currentStep)
        }

        // Reached max steps
        logger.log("[Agent] stop")
        return AgentResult.MaxStepsReached(steps = steps)
    }
}
