package com.example.agent

import com.example.agent.tools.CalculatorTool
import com.example.agent.tools.DateTimeTool
import com.example.ui.talk.LlmStreamRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

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
    val toolRegistry: ToolRegistry = ToolRegistry(listOf(CalculatorTool(), DateTimeTool())),
    private val logger: AgentLogger = AgentLogger.Default
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 5
    }

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val isCancelRequested = AtomicBoolean(false)
    private val hasLoggedStop = AtomicBoolean(false)

    @Volatile
    var currentJob: Job? = null
        private set

    private fun logStopIfNeeded() {
        if (!hasLoggedStop.getAndSet(true)) {
            logger.log("[Agent] stop")
        }
    }

    fun cancel() {
        if (!_state.compareAndSet(AgentState.RUNNING, AgentState.CANCELLING)) {
            return
        }
        logStopIfNeeded()
        isCancelRequested.set(true)
        llmRunner.cancelGeneration()
        currentJob?.cancel()
    }

    suspend fun cancelAndJoin() {
        val job = currentJob
        cancel()
        job?.join()
    }

    suspend fun run(
        userPrompt: String,
        samplingConfig: AgentSamplingConfig = AgentSamplingConfig(),
        maxSteps: Int = DEFAULT_MAX_STEPS,
        systemPrompt: String = AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT,
        enableThinking: Boolean = true,
        onStepUpdate: ((AgentStep) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null
    ): AgentResult {
        if (!_state.compareAndSet(AgentState.IDLE, AgentState.RUNNING)) {
            logger.log("[Agent] error=Already running or cancelling")
            return AgentResult.Error("Agent is already running or cancelling", emptyList())
        }

        isCancelRequested.set(false)
        hasLoggedStop.set(false)
        val steps = mutableListOf<AgentStep>()

        try {
            return coroutineScope {
                val currentCoroutineJob = coroutineContext.job
                currentJob = currentCoroutineJob
                logger.log("[Agent] start")

                if (!llmRunner.isModelLoaded()) {
                    val err = "ERROR: Model not loaded."
                    logger.log("[Agent] error=$err")
                    return@coroutineScope AgentResult.Error(err, emptyList())
                }

                for (stepNum in 1..maxSteps) {
                    // 1. Cooperative check before step
                    ensureActive()
                    if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                        logStopIfNeeded()
                        return@coroutineScope AgentResult.Cancelled(steps = steps)
                    }

                    logger.log("[Agent] step=$stepNum")

                    val prompt = AgentPromptBuilder.buildStepPrompt(
                        userMessage = userPrompt,
                        tools = toolRegistry.getAllTools(),
                        previousSteps = steps,
                        systemPrompt = systemPrompt
                    )

                    // 2. Cooperative check before LLM generation
                    ensureActive()
                    if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                        logStopIfNeeded()
                        return@coroutineScope AgentResult.Cancelled(steps = steps)
                    }

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
                        enableThinking = enableThinking,
                        onToken = { token ->
                            if (!isCancelRequested.get() && _state.value == AgentState.RUNNING && currentCoroutineJob.isActive) {
                                textAccumulator.append(token)
                                onToken?.invoke(token)
                            }
                        }
                    )

                    // 3. Cooperative check after LLM generation
                    if (isCancelRequested.get() || _state.value == AgentState.CANCELLING || !currentCoroutineJob.isActive) {
                        logStopIfNeeded()
                        return@coroutineScope AgentResult.Cancelled(steps = steps)
                    }

                    if (rawOutput.startsWith("ERROR:")) {
                        logger.log("[Agent] error=$rawOutput")
                        return@coroutineScope AgentResult.Error(rawOutput, steps)
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
                        return@coroutineScope AgentResult.Success(cleanAnswer.ifEmpty { rawOutput }, steps)
                    }

                    // Tool call detected
                    logger.log("[Agent] tool=${toolCall.toolName}")
                    val expr = toolCall.arguments["expression"]
                        ?: toolCall.arguments["action"]
                        ?: toolCall.arguments.values.firstOrNull()
                        ?: ""
                    logger.log("[Agent] expression=$expr")

                    val tool = toolRegistry.getTool(toolCall.toolName)
                    val toolResult = if (tool == null) {
                        val notFoundErr = "Tool '${toolCall.toolName}' is not registered."
                        logger.log("[Agent] error=$notFoundErr")
                        ToolExecutionResult.Error(notFoundErr)
                    } else {
                        // 4. Cooperative check before Tool execution
                        ensureActive()
                        if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                            logStopIfNeeded()
                            return@coroutineScope AgentResult.Cancelled(steps = steps)
                        }

                        val res = tool.execute(toolCall.arguments)

                        // 5. Cooperative check after Tool execution
                        ensureActive()
                        if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                            logStopIfNeeded()
                            return@coroutineScope AgentResult.Cancelled(steps = steps)
                        }

                        res
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
                logStopIfNeeded()
                AgentResult.MaxStepsReached(steps = steps)
            }
        } catch (e: CancellationException) {
            logStopIfNeeded()
            return AgentResult.Cancelled(steps = steps)
        } catch (e: Throwable) {
            logger.log("[Agent] error=${e.message ?: e.javaClass.simpleName}")
            return AgentResult.Error("ERROR: ${e.message ?: e.javaClass.simpleName}", steps)
        } finally {
            withContext(NonCancellable) {
                _state.value = AgentState.IDLE
                currentJob = null
            }
        }
    }
}
