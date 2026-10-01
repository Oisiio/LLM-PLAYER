package com.example.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentBenchmarkSummary
import com.example.agent.AgentPreferences
import com.example.agent.AgentResult
import com.example.agent.AgentRunner
import com.example.agent.AgentSamplingConfig
import com.example.agent.AgentState
import com.example.agent.AgentStep
import com.example.agent.AgentStepMetrics
import com.example.agent.ToolCallParser
import com.example.ui.agent.components.AgentChatInputBar
import com.example.ui.agent.components.AgentEmptyState
import com.example.ui.agent.components.AgentMessageBubble
import com.example.ui.agent.model.AgentMessageRole
import com.example.ui.agent.model.AgentUiMessage
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    agentRunner: AgentRunner,
    isModelLoaded: Boolean,
    modelName: String = "",
    onOpenDrawer: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val agentPreferences = remember { AgentPreferences(context) }
    var systemPrompt by remember { mutableStateOf(agentPreferences.systemPrompt) }
    var isThinkingEnabled by remember { mutableStateOf(agentPreferences.isThinkingEnabled) }
    var thinkingBudget by remember { mutableIntStateOf(agentPreferences.thinkingBudget) }
    var isBenchmarkMode by remember { mutableStateOf(agentPreferences.isBenchmarkMode) }
    var isPrefixCacheEnabled by remember { mutableStateOf(agentPreferences.isPrefixCacheEnabled) }
    var isDiagnosticsEnabled by remember { mutableStateOf(agentPreferences.isDiagnosticsEnabled) }
    var isStep1ThinkingDisabled by remember { mutableStateOf(agentPreferences.isStep1ThinkingDisabled) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<AgentUiMessage>>(emptyList()) }

    val agentState by agentRunner.state.collectAsState()
    val isRunning = agentState == AgentState.RUNNING
    val isCancelling = agentState == AgentState.CANCELLING

    if (onBack != null) {
        BackHandler { onBack() }
    }

    // Auto-scroll to bottom on new message or update
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val handleSendMessage: (String) -> Unit = { userPromptText ->
        val prompt = userPromptText.trim()
        if (prompt.isNotEmpty() && !isRunning && isModelLoaded) {
            inputText = ""
            val userMsg = AgentUiMessage(
                role = AgentMessageRole.USER,
                text = prompt
            )
            val assistantMsgId = java.util.UUID.randomUUID().toString()
            val assistantMsg = AgentUiMessage(
                id = assistantMsgId,
                role = AgentMessageRole.ASSISTANT,
                text = "",
                isRunning = true,
                currentStatus = "考え中…"
            )
            messages = messages + userMsg + assistantMsg

            coroutineScope.launch {
                try {
                    agentRunner.configureTools(
                        agentPreferences.isCalculatorEnabled,
                        agentPreferences.isDateTimeEnabled
                    )
                    val runResult = agentRunner.run(
                        userPrompt = prompt,
                        samplingConfig = AgentSamplingConfig(enablePrefixCache = isPrefixCacheEnabled),
                        maxSteps = agentPreferences.maxSteps,
                        systemPrompt = systemPrompt,
                        enableThinking = isThinkingEnabled,
                        thinkingBudget = thinkingBudget,
                        disableStep1Thinking = isStep1ThinkingDisabled,
                        enableDiagnostics = isDiagnosticsEnabled,
                        onStepUpdate = { newStep ->
                            val toolName = newStep.toolCall?.toolName ?: ""
                            val statusText = when (toolName.lowercase()) {
                                "datetime" -> "日時を確認中…"
                                "calculator" -> "計算中…"
                                else -> "ツール実行中…"
                            }
                            messages = messages.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        steps = msg.steps + newStep,
                                        currentStatus = statusText
                                    )
                                } else msg
                            }
                        },
                        onToken = { token ->
                            messages = messages.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        text = msg.text + token,
                                        currentStatus = null
                                    )
                                } else msg
                            }
                        },
                        onThoughtUpdate = { _, _, isThinking ->
                            if (isThinking) {
                                messages = messages.map { msg ->
                                    if (msg.id == assistantMsgId && msg.text.isBlank()) {
                                        msg.copy(currentStatus = "考え中…")
                                    } else msg
                                }
                            }
                        }
                    )

                    val (finalText, isError) = when (runResult) {
                        is AgentResult.Success -> runResult.finalAnswer to false
                        is AgentResult.MaxStepsReached -> runResult.finalAnswer to false
                        is AgentResult.ToolRetryLimitExceeded -> runResult.finalAnswer to true
                        is AgentResult.ToolDuplicateDetected -> runResult.finalAnswer to true
                        is AgentResult.Cancelled -> (if (runResult.message.isNotBlank()) runResult.message else "停止しました") to false
                        is AgentResult.Error -> runResult.errorMessage to true
                    }

                    messages = messages.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(
                                text = if (finalText.isNotBlank()) finalText else msg.text,
                                steps = if (runResult.steps.isNotEmpty()) runResult.steps else msg.steps,
                                isRunning = false,
                                currentStatus = null,
                                isError = isError,
                                benchmarkSummary = runResult.benchmarkSummary
                            )
                        } else msg
                    }
                } catch (e: CancellationException) {
                    messages = messages.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(
                                text = if (msg.text.isNotBlank()) msg.text else "停止しました",
                                isRunning = false,
                                currentStatus = null
                            )
                        } else msg
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Agent Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val displayModelName = when {
                            modelName.isNotBlank() -> modelName
                            isModelLoaded -> "Model Loaded"
                            else -> "No Model"
                        }
                        Text(
                            text = displayModelName,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = if (isModelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("agent_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る"
                            )
                        }
                    } else if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("agent_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.testTag("agent_more_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More"
                        )
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Agent設定") },
                            leadingIcon = {
                                Icon(Icons.Default.Tune, contentDescription = null)
                            },
                            onClick = {
                                showMoreMenu = false
                                showSettingsDialog = true
                            },
                            modifier = Modifier.testTag("agent_menu_settings")
                        )
                        if (messages.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("チャットをクリア") },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    messages = emptyList()
                                },
                                modifier = Modifier.testTag("agent_menu_clear")
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            AgentChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = { handleSendMessage(inputText) },
                isRunning = isRunning,
                isCancelling = isCancelling,
                onStop = { agentRunner.cancel() },
                enabled = isModelLoaded
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                AgentEmptyState(
                    onSelectSuggestion = { suggestion ->
                        inputText = suggestion
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("agent_chat_timeline"),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        AgentMessageBubble(message = message)

                        // If Benchmark Mode is enabled and this message has benchmark metrics, display summary card
                        if (isBenchmarkMode && message.benchmarkSummary != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                AgentBenchmarkCard(
                                    summary = message.benchmarkSummary,
                                    steps = message.steps,
                                    resultText = message.text,
                                    thinkingBudget = thinkingBudget
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AgentSettingsDialog(
            initialSystemPrompt = systemPrompt,
            initialThinkingEnabled = isThinkingEnabled,
            initialThinkingBudget = thinkingBudget,
            initialBenchmarkMode = isBenchmarkMode,
            initialPrefixCacheEnabled = isPrefixCacheEnabled,
            initialDiagnosticsEnabled = isDiagnosticsEnabled,
            initialStep1ThinkingDisabled = isStep1ThinkingDisabled,
            onDismiss = { showSettingsDialog = false },
            onSave = { newPrompt, thinking, budget, bench, prefixCache, diag, step1Disabled ->
                systemPrompt = newPrompt
                isThinkingEnabled = thinking
                thinkingBudget = budget
                isBenchmarkMode = bench
                isPrefixCacheEnabled = prefixCache
                isDiagnosticsEnabled = diag
                isStep1ThinkingDisabled = step1Disabled

                agentPreferences.systemPrompt = newPrompt
                agentPreferences.isThinkingEnabled = thinking
                agentPreferences.thinkingBudget = budget
                agentPreferences.isBenchmarkMode = bench
                agentPreferences.isPrefixCacheEnabled = prefixCache
                agentPreferences.isDiagnosticsEnabled = diag
                agentPreferences.isStep1ThinkingDisabled = step1Disabled

                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun AgentBenchmarkCard(
    summary: AgentBenchmarkSummary,
    steps: List<AgentStep>,
    resultText: String?,
    thinkingBudget: Int,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copiedSection by remember { mutableStateOf<String?>(null) }
    var showStepDetails by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent_benchmark_summary_card"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Agent Benchmark Metrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BenchmarkCopyButton("全部", copiedSection == "all", "agent_benchmark_copy_button") {
                        clipboardManager.setText(AnnotatedString(formatBenchmarkText(summary, steps, resultText, thinkingBudget)))
                        copiedSection = "all"
                        coroutineScope.launch { delay(2000); copiedSection = null }
                    }
                    BenchmarkCopyButton("推論", copiedSection == "thinking", "agent_benchmark_copy_thinking_button") {
                        clipboardManager.setText(AnnotatedString(formatThinkingCopyText(steps)))
                        copiedSection = "thinking"
                        coroutineScope.launch { delay(2000); copiedSection = null }
                    }
                    BenchmarkCopyButton("本文", copiedSection == "answer", "agent_benchmark_copy_answer_button") {
                        clipboardManager.setText(AnnotatedString(formatAnswerCopyText(steps, resultText)))
                        copiedSection = "answer"
                        coroutineScope.launch { delay(2000); copiedSection = null }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BenchmarkStatItem(
                    label = "Total Time",
                    value = "${formatMs(summary.totalTimeMs)} ms",
                    subValue = String.format(Locale.US, "%.2f s", summary.totalTimeMs / 1000.0)
                )
                BenchmarkStatItem(
                    label = "Steps",
                    value = "${summary.stepCount} steps"
                )
                BenchmarkStatItem(
                    label = "Prompt",
                    value = "${summary.totalPromptTokens} tok"
                )
                BenchmarkStatItem(
                    label = "Generated",
                    value = "${summary.totalGenTokens} tok"
                )
            }

            if (summary.totalToolTimeMs > 0.0) {
                Text(
                    text = "Tool合計実行時間: ${formatMs(summary.totalToolTimeMs)} ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }

            // Step & Thinking details toggle if steps exist
            if (steps.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStepDetails = !showStepDetails },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showStepDetails) "Step / Thinking詳細を閉じる" else "Step / Thinking詳細を表示 (${steps.size} steps)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Icon(
                        imageVector = if (showStepDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = showStepDetails) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        steps.forEach { step ->
                            step.thoughtText?.takeIf { it.isNotBlank() }?.let { thought ->
                                StepThoughtDisplay(
                                    thoughtText = thought,
                                    isPrefilled = step.isThoughtPrefilled,
                                    isCompleted = step.isThoughtCompleted
                                )
                            }
                            step.metrics?.let { metrics ->
                                StepMetricsDisplay(metrics = metrics)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkStatItem(
    label: String,
    value: String,
    subValue: String? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        if (subValue != null) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun StepMetricsDisplay(metrics: AgentStepMetrics) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📊 Step ${metrics.stepNumber} Metrics",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Total: ${formatMs(metrics.stepTotalTimeMs)} ms",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val promptDetail = if (metrics.cachedTokens > 0) {
                "• Prompt: ${metrics.promptTokens} tok (Cached: ${metrics.cachedTokens}, New: ${metrics.newPromptTokens}) (${formatMs(metrics.promptTimeMs)} ms)"
            } else {
                "• Prompt: ${metrics.promptTokens} tok (${formatMs(metrics.promptTimeMs)} ms)"
            }

            Text(
                text = promptDetail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Generated: ${metrics.genTokens} tok (${formatMs(metrics.genTimeMs)} ms)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Speed: ${String.format(Locale.US, "%.2f", metrics.speedTokPerSec)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• TTFT: ${formatMs(metrics.ttftMs)} ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Reasoning Budget: ${if (metrics.reasoningBudget == 0) "OFF" else "${metrics.reasoningBudget} tok"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            val toolInfo = if (metrics.toolName != null) {
                "${formatMs(metrics.toolExecutionTimeMs)} ms (${metrics.toolName})"
            } else {
                "-"
            }
            Text(
                text = "• Tool: $toolInfo",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Stop Reason: ${metrics.stopReason}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            if (metrics.retryCount > 0) {
                Text(
                    text = "• Retry Count: ${metrics.retryCount}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error
                )
            }

            metrics.diagnostics?.let { diag ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "🔬 Generation Diagnostics",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = diag.formatReport(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

private fun formatMs(ms: Double): String {
    return if (ms >= 10.0) {
        String.format(Locale.US, "%.0f", ms)
    } else {
        String.format(Locale.US, "%.1f", ms)
    }
}

private fun formatBenchmarkText(
    summary: AgentBenchmarkSummary,
    steps: List<AgentStep>,
    finalAnswer: String?,
    reasoningBudget: Int
): String {
    val sb = StringBuilder()
    sb.appendLine("=== Agent Benchmark Results ===")
    sb.appendLine("Total Time: ${String.format(Locale.US, "%.1f", summary.totalTimeMs)} ms (${String.format(Locale.US, "%.2f", summary.totalTimeMs / 1000.0)} s)")
    sb.appendLine("Steps: ${summary.stepCount}")
    if (summary.totalCachedTokens > 0) {
        sb.appendLine("Total Prompt Tokens: ${summary.totalPromptTokens} (Cached: ${summary.totalCachedTokens}, New: ${summary.totalNewPromptTokens})")
    } else {
        sb.appendLine("Total Prompt Tokens: ${summary.totalPromptTokens}")
    }
    sb.appendLine("Total Generated Tokens: ${summary.totalGenTokens}")
    sb.appendLine("Total Tool Time: ${String.format(Locale.US, "%.2f", summary.totalToolTimeMs)} ms")
    sb.appendLine()

    summary.stepMetrics.forEach { step ->
        sb.appendLine("--- Step ${step.stepNumber} Metrics ---")
        if (step.cachedTokens > 0) {
            sb.appendLine("• Prompt: ${step.promptTokens} tok (Cached: ${step.cachedTokens}, New: ${step.newPromptTokens}) (${String.format(Locale.US, "%.1f", step.promptTimeMs)} ms)")
        } else {
            sb.appendLine("• Prompt: ${step.promptTokens} tok (${String.format(Locale.US, "%.1f", step.promptTimeMs)} ms)")
        }

        val stepLog = steps.firstOrNull { it.stepNumber == step.stepNumber }
        val analysis = stepLog?.let { analyzeGeneration(it) } ?: GenerationAnalysis(
            "UNKNOWN",
            "算出不可",
            "算出不可"
        )
        sb.appendLine("• Generated: ${step.genTokens} tok (${String.format(Locale.US, "%.1f", step.genTimeMs)} ms)")
        sb.appendLine("• Stop Reason: ${analysis.stopReason}")
        sb.appendLine("• Thinking Tokens: ${analysis.thinkingTokens}")
        sb.appendLine("• Answer Tokens: ${analysis.answerTokens}")
        sb.appendLine("• Reasoning Budget: ${if (reasoningBudget > 0) reasoningBudget else "OFF"}")
        sb.appendLine("• Speed: ${String.format(Locale.US, "%.2f", step.speedTokPerSec)} tok/s")
        sb.appendLine("• TTFT: ${String.format(Locale.US, "%.1f", step.ttftMs)} ms")
        sb.appendLine("• Reasoning Budget: ${if (step.reasoningBudget == 0) "OFF" else "${step.reasoningBudget} tok"}")
        if (step.toolName != null) {
            sb.appendLine("• Tool: ${String.format(Locale.US, "%.2f", step.toolExecutionTimeMs)} ms (${step.toolName})")
        } else {
            sb.appendLine("• Tool: -")
        }
        sb.appendLine("• Stop Reason: ${step.stopReason}")
        if (step.retryCount > 0) {
            sb.appendLine("• Retry Count: ${step.retryCount}")
        }
        if (step.errorMessage != null) {
            sb.appendLine("• Tool Error: ${step.errorMessage}")
        }
        sb.appendLine("• Step Total: ${String.format(Locale.US, "%.1f", step.stepTotalTimeMs)} ms")
        step.diagnostics?.let { diag ->
            sb.appendLine()
            sb.appendLine(diag.formatReport())
        }
        sb.appendLine()
    }

    sb.appendLine("=== Agent Response Logs ===")
    if (steps.isEmpty()) {
        sb.appendLine("(No step logs)")
    } else {
        steps.forEach { step ->
            sb.appendLine()
            sb.appendLine("--- Step ${step.stepNumber} ---")
            sb.appendLine("[Prompt]")
            sb.appendLine(step.prompt.ifBlank { "(empty)" })
            sb.appendLine()
            sb.appendLine("[Raw LLM Output]")
            sb.appendLine(step.rawLlmOutput.ifBlank { "(empty)" })
            step.thoughtText?.takeIf { it.isNotBlank() }?.let { thought ->
                sb.appendLine()
                sb.appendLine("[Parsed Thought]")
                sb.appendLine(thought)
                sb.appendLine("[Thought Prefilled: ${step.isThoughtPrefilled}]")
                sb.appendLine("[Thought Completed: ${step.isThoughtCompleted}]")
            }
            step.toolCall?.let { toolCall ->
                sb.appendLine()
                sb.appendLine("[Tool Call]")
                sb.appendLine("Tool: ${toolCall.toolName}")
                sb.appendLine("Arguments: ${toolCall.arguments}")
            }
            step.toolResult?.let { toolResult ->
                sb.appendLine()
                sb.appendLine("[Tool Result]")
                sb.appendLine("Success: ${toolResult.isSuccess}")
                sb.appendLine(toolResult.text)
            }
        }
    }
    sb.appendLine()
    sb.appendLine("=== Final Answer ===")
    sb.appendLine(finalAnswer?.ifBlank { "(empty)" } ?: "(none)")
    return sb.toString().trimEnd()
}

private data class GenerationAnalysis(
    val stopReason: String,
    val thinkingTokens: String,
    val answerTokens: String
)

private fun analyzeGeneration(step: AgentStep): GenerationAnalysis {
    if (step.metrics?.stopReason == "MAX_STEPS") {
        return GenerationAnalysis("MAX_STEPS", "算出不可", "算出不可")
    }
    if (step.metrics?.stopReason == "tool_retry_limit_exceeded") {
        return GenerationAnalysis("tool_retry_limit_exceeded", "算出不可", "算出不可")
    }
    if (step.metrics?.stopReason == "tool_duplicate_detected") {
        return GenerationAnalysis("tool_duplicate_detected", "算出不可", "算出不可")
    }
    val raw = step.rawLlmOutput
    val endIdx = raw.indexOf("</think>")
    val completed = step.isThoughtCompleted && endIdx >= 0

    return when {
        !completed && !step.thoughtText.isNullOrBlank() -> GenerationAnalysis(
            if ((step.metrics?.genTokens ?: 0) > 0) "MAX_TOKENS (推定)" else "NO_GENERATION",
            step.metrics?.genTokens?.toString() ?: "-",
            "0"
        )
        step.toolCall != null -> GenerationAnalysis("TOOL_CALL", "算出不可", "算出不可")
        completed -> GenerationAnalysis("FINAL", "算出不可", "算出不可")
        else -> GenerationAnalysis("FINAL", "0", step.metrics?.genTokens?.toString() ?: "-")
    }
}

private fun formatThinkingCopyText(steps: List<AgentStep>): String = buildString {
    appendLine("=== Agent Thinking Logs ===")
    steps.forEach {
        appendLine()
        appendLine("--- Step ${it.stepNumber} ---")
        appendLine(it.thoughtText?.ifBlank { "(empty)" } ?: "(no thinking)")
    }
}.trimEnd()

private fun formatAnswerCopyText(steps: List<AgentStep>, finalAnswer: String?): String = buildString {
    appendLine("=== Agent Answer Logs ===")
    steps.forEach {
        val raw = it.rawLlmOutput
        val end = raw.indexOf("</think>")
        val answer = if (end >= 0) {
            ToolCallParser.removeToolCallTags(raw.substring(end + 8)).trim()
        } else if (it.thoughtText.isNullOrBlank()) {
            ToolCallParser.removeToolCallTags(raw).trim()
        } else ""
        if (answer.isNotBlank()) {
            appendLine()
            appendLine("--- Step ${it.stepNumber} ---")
            appendLine(answer)
        }
    }
    sbEndLine(finalAnswer)
}.trimEnd()

private fun StringBuilder.sbEndLine(finalAnswer: String?) {
    appendLine()
    appendLine("=== Final Answer ===")
    appendLine(finalAnswer?.ifBlank { "(empty)" } ?: "(none)")
}

@Composable
private fun BenchmarkCopyButton(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(if (selected) "✓ $label" else label, fontSize = 10.sp) },
        leadingIcon = {
            Icon(
                if (selected) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        modifier = Modifier
            .height(30.dp)
            .testTag(testTag)
    )
}

@Composable
private fun StepThoughtDisplay(
    thoughtText: String,
    isPrefilled: Boolean,
    isCompleted: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "🧠 思考プロセス",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isPrefilled) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                        ) {
                            Text(
                                text = "[Prefill] <think>",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "<think>",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (expanded) "折りたたむ" else "表示する",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "思考プロセスを折りたたむ" else "思考プロセスを展開する",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = buildString {
                            if (isPrefilled) {
                                append("<think>\n")
                            }
                            append(thoughtText)
                            if (isCompleted) {
                                append("\n</think>")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
