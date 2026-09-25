package com.example.agent

import com.example.agent.tools.CalculatorTool
import com.example.agent.tools.DateTimeTool
import com.example.ui.talk.LlmStreamRunner
import com.example.ui.talk.TalkDebugMetrics
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
    val seed: Long = 12345L,
    val enablePrefixCache: Boolean = true
)

class AgentRunner(
    private val llmRunner: LlmStreamRunner,
    val toolRegistry: ToolRegistry = ToolRegistry(listOf(CalculatorTool(), DateTimeTool())),
    private val logger: AgentLogger = AgentLogger.Default
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 5

        internal fun extractThoughtInfo(
            rawOutput: String,
            isFirstStep: Boolean,
            enableThinking: Boolean
        ): Triple<String?, Boolean, Boolean> {
            if (isFirstStep && enableThinking) {
                val endTagIdx = rawOutput.indexOf("</think>")
                return if (endTagIdx != -1) {
                    val thought = rawOutput.substring(0, endTagIdx).trim()
                    Triple(thought.ifEmpty { null }, true, true)
                } else {
                    val thought = rawOutput.trim()
                    Triple(thought.ifEmpty { null }, true, false)
                }
            } else {
                val startTagIdx = rawOutput.indexOf("<think>")
                val endTagIdx = rawOutput.indexOf("</think>")
                if (startTagIdx != -1) {
                    return if (endTagIdx != -1 && endTagIdx > startTagIdx) {
                        val thought = rawOutput.substring(startTagIdx + 7, endTagIdx).trim()
                        Triple(thought.ifEmpty { null }, false, true)
                    } else {
                        val thought = rawOutput.substring(startTagIdx + 7).trim()
                        Triple(thought.ifEmpty { null }, false, false)
                    }
                }
                return Triple(null, false, false)
            }
        }
    }

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val isCancelRequested = AtomicBoolean(false)
    private val hasLoggedStop = AtomicBoolean(false)

    fun configureTools(calculatorEnabled: Boolean, dateTimeEnabled: Boolean) {
        val list = mutableListOf<Tool>()
        if (calculatorEnabled) list.add(CalculatorTool())
        if (dateTimeEnabled) list.add(DateTimeTool())
        toolRegistry.setTools(list)
    }

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
        thinkingBudget: Int = 1024,
        disableStep1Thinking: Boolean = false,
        enableDiagnostics: Boolean = false,
        onStepUpdate: ((AgentStep) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null,
        onThoughtUpdate: ((thoughtText: String, isPrefilled: Boolean, isThinking: Boolean) -> Unit)? = null
    ): AgentResult {
        if (!_state.compareAndSet(AgentState.IDLE, AgentState.RUNNING)) {
            logger.log("[Agent] error=Already running or cancelling")
            return AgentResult.Error("Agent is already running or cancelling", emptyList())
        }

        isCancelRequested.set(false)
        hasLoggedStop.set(false)
        val steps = mutableListOf<AgentStep>()
        val sessionId = java.util.UUID.randomUUID().toString()
        llmRunner.clearAgentPrefixCache()

        try {
            return coroutineScope {
                val currentCoroutineJob = coroutineContext.job
                currentJob = currentCoroutineJob
                val agentStartNano = System.nanoTime()
                logger.log("[Agent] start")

                fun buildBenchmarkSummary(totalTimeMs: Double): AgentBenchmarkSummary {
                    val stepMetricsList = steps.mapNotNull { it.metrics }
                    return AgentBenchmarkSummary(
                        totalTimeMs = totalTimeMs,
                        stepCount = steps.size,
                        totalPromptTokens = stepMetricsList.sumOf { it.promptTokens },
                        totalGenTokens = stepMetricsList.sumOf { it.genTokens },
                        totalToolTimeMs = stepMetricsList.sumOf { it.toolExecutionTimeMs },
                        stepMetrics = stepMetricsList,
                        totalCachedTokens = stepMetricsList.sumOf { it.cachedTokens },
                        totalNewPromptTokens = stepMetricsList.sumOf { it.newPromptTokens },
                        diagnosticsList = stepMetricsList.mapNotNull { it.diagnostics }
                    )
                }

                if (!llmRunner.isModelLoaded()) {
                    val err = "ERROR: Model not loaded."
                    logger.log("[Agent] error=$err")
                    return@coroutineScope AgentResult.Error(err, emptyList())
                }

                for (stepNum in 1..maxSteps) {
                    val stepStartNano = System.nanoTime()

                    // 1. Cooperative check before step
                    ensureActive()
                    if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                        logStopIfNeeded()
                        val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                        return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
                    }

                    logger.log("[Agent] step=$stepNum")

                    val isFirstStep = stepNum == 1
                    val hasAvailableTools = toolRegistry.getAllTools().isNotEmpty()
                    val stepEnableThinking = if (isFirstStep && disableStep1Thinking && hasAvailableTools) {
                        false
                    } else {
                        enableThinking
                    }
                    val stepThinkingBudget = if (stepEnableThinking) thinkingBudget else 0

                    var isThinking = isFirstStep && stepEnableThinking
                    val isThoughtPrefilled = isThinking
                    if (isThinking) {
                        onThoughtUpdate?.invoke("", true, true)
                    } else {
                        onThoughtUpdate?.invoke("", false, false)
                    }

                    val prompt: String
                    val textAccumulator = StringBuilder()
                    var stepDebugMetrics: TalkDebugMetrics? = null
                    var stepDiagnostics: StepDiagnostics? = null

                    val handleToken: (String) -> Unit = { token ->
                        if (!isCancelRequested.get() && _state.value == AgentState.RUNNING && currentCoroutineJob.isActive) {
                            textAccumulator.append(token)
                            onToken?.invoke(token)

                            if (onThoughtUpdate != null) {
                                val currentText = textAccumulator.toString()
                                if (isThinking) {
                                    val endIdx = currentText.indexOf("</think>")
                                    if (endIdx != -1) {
                                        isThinking = false
                                        val thought = if (isThoughtPrefilled) {
                                            currentText.substring(0, endIdx)
                                        } else {
                                            val startIdx = currentText.indexOf("<think>")
                                            if (startIdx != -1 && startIdx < endIdx) {
                                                currentText.substring(startIdx + 7, endIdx)
                                            } else {
                                                currentText.substring(0, endIdx)
                                            }
                                        }
                                        onThoughtUpdate.invoke(thought, isThoughtPrefilled, false)
                                    } else {
                                        val thought = if (isThoughtPrefilled) {
                                            currentText
                                        } else {
                                            val startIdx = currentText.indexOf("<think>")
                                            if (startIdx != -1) {
                                                currentText.substring(startIdx + 7)
                                            } else {
                                                currentText
                                            }
                                        }
                                        onThoughtUpdate.invoke(thought, isThoughtPrefilled, true)
                                    }
                                } else if (!isThoughtPrefilled) {
                                    val startIdx = currentText.indexOf("<think>")
                                    val endIdx = currentText.indexOf("</think>")
                                    if (startIdx != -1) {
                                        if (endIdx != -1 && endIdx > startIdx) {
                                            val thought = currentText.substring(startIdx + 7, endIdx)
                                            onThoughtUpdate.invoke(thought, false, false)
                                        } else {
                                            isThinking = true
                                            val thought = currentText.substring(startIdx + 7)
                                            onThoughtUpdate.invoke(thought, false, true)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val rawOutput = if (isFirstStep) {
                        prompt = AgentPromptBuilder.buildInitialPrompt(
                            userMessage = userPrompt,
                            tools = toolRegistry.getAllTools(),
                            systemPrompt = systemPrompt
                        )

                        // 2. Cooperative check before LLM generation
                        ensureActive()
                        if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                            logStopIfNeeded()
                            val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                            return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
                        }

                        llmRunner.runAgentSessionInit(
                            prompt = prompt,
                            temperature = samplingConfig.temperature,
                            topK = samplingConfig.topK,
                            topP = samplingConfig.topP,
                            minP = samplingConfig.minP,
                            typicalP = samplingConfig.typicalP,
                            repetitionPenalty = samplingConfig.repetitionPenalty,
                            penaltyLastN = samplingConfig.penaltyLastN,
                            seed = samplingConfig.seed,
                            enableThinking = stepEnableThinking,
                            thinkingBudget = stepThinkingBudget,
                            sessionId = sessionId,
                            enableDiagnostics = enableDiagnostics,
                            onToken = handleToken,
                            onMetrics = { metrics ->
                                stepDebugMetrics = metrics
                            },
                            onDiagnostics = { diag ->
                                stepDiagnostics = diag
                            }
                        )
                    } else {
                        val lastStep = steps.last()
                        prompt = AgentPromptBuilder.buildToolDeltaPrompt(
                            toolName = lastStep.toolCall?.toolName ?: "unknown",
                            toolResult = lastStep.toolResult ?: ToolExecutionResult.Error("No tool result found from previous step")
                        )

                        // 2. Cooperative check before LLM generation
                        ensureActive()
                        if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                            logStopIfNeeded()
                            val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                            return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
                        }

                        llmRunner.runAgentSessionAppend(
                            deltaPrompt = prompt,
                            temperature = samplingConfig.temperature,
                            topK = samplingConfig.topK,
                            topP = samplingConfig.topP,
                            minP = samplingConfig.minP,
                            typicalP = samplingConfig.typicalP,
                            repetitionPenalty = samplingConfig.repetitionPenalty,
                            penaltyLastN = samplingConfig.penaltyLastN,
                            seed = samplingConfig.seed,
                            enableThinking = stepEnableThinking,
                            thinkingBudget = stepThinkingBudget,
                            sessionId = sessionId,
                            enableDiagnostics = enableDiagnostics,
                            onToken = handleToken,
                            onMetrics = { metrics ->
                                stepDebugMetrics = metrics
                            },
                            onDiagnostics = { diag ->
                                stepDiagnostics = diag
                            }
                        )
                    }

                    // 3. Cooperative check after LLM generation
                    if (isCancelRequested.get() || _state.value == AgentState.CANCELLING || !currentCoroutineJob.isActive) {
                        logStopIfNeeded()
                        val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                        return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
                    }

                    if (rawOutput.startsWith("ERROR:")) {
                        logger.log("[Agent] error=$rawOutput")
                        val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                        return@coroutineScope AgentResult.Error(rawOutput, steps, buildBenchmarkSummary(agentTotalTimeMs))
                    }

                    // Check if a tool call is present in the LLM output
                    val toolCall = ToolCallParser.parse(rawOutput)

                    if (toolCall == null) {
                        // No tool call requested -> Final Answer reached
                        logger.log("[Agent] final")
                        val (thoughtText, thoughtPrefilled, thoughtCompleted) = extractThoughtInfo(
                            rawOutput = rawOutput,
                            isFirstStep = isFirstStep,
                            enableThinking = stepEnableThinking
                        )

                        val rawAnswerWithoutPrefillThink = if (isFirstStep && stepEnableThinking && rawOutput.contains("</think>")) {
                            rawOutput.substring(rawOutput.indexOf("</think>") + 8)
                        } else {
                            rawOutput
                        }
                        val cleanAnswer = ToolCallParser.removeToolCallTags(rawAnswerWithoutPrefillThink).trim()
                        val stepEndNano = System.nanoTime()
                        val stepTotalTimeMs = (stepEndNano - stepStartNano) / 1_000_000.0

                        val pTokens = stepDebugMetrics?.promptTokens ?: 0
                        val cTokens = stepDebugMetrics?.cachedTokens ?: 0
                        val nTokens = stepDebugMetrics?.newPromptTokens ?: (pTokens - cTokens)

                        val finalMetrics = AgentStepMetrics(
                            stepNumber = stepNum,
                            promptTokens = pTokens,
                            promptTimeMs = stepDebugMetrics?.promptTimeMs ?: 0.0,
                            ttftMs = stepDebugMetrics?.ttftMs ?: 0.0,
                            genTokens = stepDebugMetrics?.genTokens ?: 0,
                            genTimeMs = stepDebugMetrics?.genTimeMs ?: 0.0,
                            speedTokPerSec = stepDebugMetrics?.speedTokPerSec ?: 0.0,
                            toolName = null,
                            toolExecutionTimeMs = 0.0,
                            toolStartTime = 0L,
                            toolEndTime = 0L,
                            stepTotalTimeMs = stepTotalTimeMs,
                            cachedTokens = cTokens,
                            newPromptTokens = nTokens,
                            diagnostics = stepDiagnostics,
                            reasoningBudget = stepThinkingBudget,
                            stopReason = "EOG"
                        )

                        val finalStep = AgentStep(
                            stepNumber = stepNum,
                            prompt = prompt,
                            rawLlmOutput = rawOutput,
                            toolCall = null,
                            toolResult = null,
                            isFinal = true,
                            metrics = finalMetrics,
                            thoughtText = thoughtText,
                            isThoughtPrefilled = thoughtPrefilled,
                            isThoughtCompleted = thoughtCompleted
                        )
                        steps.add(finalStep)
                        onStepUpdate?.invoke(finalStep)

                        val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                        return@coroutineScope AgentResult.Success(
                            cleanAnswer.ifEmpty { rawOutput },
                            steps,
                            buildBenchmarkSummary(agentTotalTimeMs)
                        )
                    }

                    // Tool call detected
                    logger.log("[Agent] tool=${toolCall.toolName}")
                    val expr = toolCall.arguments["expression"]
                        ?: toolCall.arguments["action"]
                        ?: toolCall.arguments.values.firstOrNull()
                        ?: ""
                    logger.log("[Agent] expression=$expr")

                    var toolStartTimestamp = 0L
                    var toolEndTimestamp = 0L
                    var toolExecutionTimeMs = 0.0

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
                            val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                            return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
                        }

                        toolStartTimestamp = System.currentTimeMillis()
                        val toolStartNano = System.nanoTime()
                        val res = try {
                            tool.execute(toolCall.arguments)
                        } catch (e: Throwable) {
                            val err = "Tool execution exception: ${e.message ?: e.javaClass.simpleName}"
                            logger.log("[Agent] error=$err")
                            ToolExecutionResult.Error(err)
                        }
                        val toolEndNano = System.nanoTime()
                        toolEndTimestamp = System.currentTimeMillis()
                        toolExecutionTimeMs = (toolEndNano - toolStartNano) / 1_000_000.0

                        // 5. Cooperative check after Tool execution
                        ensureActive()
                        if (isCancelRequested.get() || _state.value == AgentState.CANCELLING) {
                            logStopIfNeeded()
                            val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                            return@coroutineScope AgentResult.Cancelled(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
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

                    val stepEndNano = System.nanoTime()
                    val stepTotalTimeMs = (stepEndNano - stepStartNano) / 1_000_000.0

                    val pTokens = stepDebugMetrics?.promptTokens ?: 0
                    val cTokens = stepDebugMetrics?.cachedTokens ?: 0
                    val nTokens = stepDebugMetrics?.newPromptTokens ?: (pTokens - cTokens)

                    val stepMetrics = AgentStepMetrics(
                        stepNumber = stepNum,
                        promptTokens = pTokens,
                        promptTimeMs = stepDebugMetrics?.promptTimeMs ?: 0.0,
                        ttftMs = stepDebugMetrics?.ttftMs ?: 0.0,
                        genTokens = stepDebugMetrics?.genTokens ?: 0,
                        genTimeMs = stepDebugMetrics?.genTimeMs ?: 0.0,
                        speedTokPerSec = stepDebugMetrics?.speedTokPerSec ?: 0.0,
                        toolName = toolCall.toolName,
                        toolExecutionTimeMs = toolExecutionTimeMs,
                        toolStartTime = toolStartTimestamp,
                        toolEndTime = toolEndTimestamp,
                        stepTotalTimeMs = stepTotalTimeMs,
                        cachedTokens = cTokens,
                        newPromptTokens = nTokens,
                        diagnostics = stepDiagnostics,
                        reasoningBudget = stepThinkingBudget,
                        stopReason = "TOOL_CALL"
                    )

                    val (thoughtText, thoughtPrefilled, thoughtCompleted) = extractThoughtInfo(
                        rawOutput = rawOutput,
                        isFirstStep = isFirstStep,
                        enableThinking = stepEnableThinking
                    )

                    val currentStep = AgentStep(
                        stepNumber = stepNum,
                        prompt = prompt,
                        rawLlmOutput = rawOutput,
                        toolCall = toolCall,
                        toolResult = toolResult,
                        isFinal = false,
                        metrics = stepMetrics,
                        thoughtText = thoughtText,
                        isThoughtPrefilled = thoughtPrefilled,
                        isThoughtCompleted = thoughtCompleted
                    )
                    steps.add(currentStep)
                    onStepUpdate?.invoke(currentStep)
                }

                // Reached max steps
                logStopIfNeeded()
                val agentTotalTimeMs = (System.nanoTime() - agentStartNano) / 1_000_000.0
                AgentResult.MaxStepsReached(steps = steps, benchmarkSummary = buildBenchmarkSummary(agentTotalTimeMs))
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
                llmRunner.clearAgentSession()
                llmRunner.clearAgentPrefixCache()
            }
        }
    }
}
