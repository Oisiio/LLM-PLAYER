package com.example.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
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
import com.example.agent.StepDiagnostics
import com.example.agent.ToolExecutionResult
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    agentRunner: AgentRunner,
    isModelLoaded: Boolean,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val agentPreferences = remember { AgentPreferences(context) }
    var systemPrompt by remember { mutableStateOf(agentPreferences.systemPrompt) }
    var isThinkingEnabled by remember { mutableStateOf(agentPreferences.isThinkingEnabled) }
    var thinkingBudget by remember { mutableIntStateOf(agentPreferences.thinkingBudget) }
    var isBenchmarkMode by remember { mutableStateOf(agentPreferences.isBenchmarkMode) }
    var isPrefixCacheEnabled by remember { mutableStateOf(agentPreferences.isPrefixCacheEnabled) }
    var isDiagnosticsEnabled by remember { mutableStateOf(agentPreferences.isDiagnosticsEnabled) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("12345 * 678 を計算してください") }
    val agentState by agentRunner.state.collectAsState()
    val isRunning = agentState == AgentState.RUNNING
    val isCancelling = agentState == AgentState.CANCELLING
    var steps by remember { mutableStateOf<List<AgentStep>>(emptyList()) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var benchmarkSummary by remember { mutableStateOf<AgentBenchmarkSummary?>(null) }
    var liveTokens by remember { mutableStateOf("") }
    var liveThoughtText by remember { mutableStateOf("") }
    var isLiveThoughtPrefilled by remember { mutableStateOf(false) }
    var isLiveThinking by remember { mutableStateOf(false) }
    var isCopied by remember { mutableStateOf(false) }

    val presets = listOf(
        "12345 * 678 を計算して",
        "今日は何日ですか？",
        "今何時？",
        "2026-12-25 は何曜日？",
        "今日からクリスマス（2026-12-25）まで何日？",
        "今日からクリスマスまでの日数を計算して、1日500円貯金したらいくらになる？",
        "100 / 4",
        "こんにちは"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenDrawer != null) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(40.dp).testTag("agent_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu"
                        )
                    }
                }
                Column {
                    Text(
                        text = "Agent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Calculator & DateTime Tool 検証環境",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (isModelLoaded) "Model Loaded" else "No Model") },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (isModelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                )
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.testTag("agent_settings_button")
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Agent設定"
                    )
                }
            }
        }

        // Settings Status Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = { showSettingsDialog = true },
                label = { Text("Thinking: ${if (isThinkingEnabled) "ON (${thinkingBudget})" else "OFF"}") },
                leadingIcon = {
                    Icon(
                        if (isThinkingEnabled) Icons.Filled.Psychology else Icons.Filled.PsychologyAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("agent_thinking_status_chip")
            )
            FilterChip(
                selected = isBenchmarkMode,
                onClick = {
                    isBenchmarkMode = !isBenchmarkMode
                    agentPreferences.isBenchmarkMode = isBenchmarkMode
                },
                label = { Text("Benchmark: ${if (isBenchmarkMode) "ON" else "OFF"}") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("agent_benchmark_status_chip")
            )
            AssistChip(
                onClick = { showSettingsDialog = true },
                label = { Text("設定") },
                leadingIcon = {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("agent_settings_chip")
            )
        }

        // Quick test chips
        Text(
            text = "テストケース (タップで入力):",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                SuggestionChip(
                    onClick = { if (agentState == AgentState.IDLE) prompt = preset },
                    label = { Text(preset) },
                    enabled = agentState == AgentState.IDLE
                )
            }
        }

        // Input TextField
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("agent_prompt_input"),
            label = { Text("プロンプト (計算や質問)") },
            minLines = 2,
            enabled = agentState == AgentState.IDLE
        )

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (agentState != AgentState.IDLE) return@Button
                    resultText = null
                    steps = emptyList()
                    benchmarkSummary = null
                    liveTokens = ""
                    liveThoughtText = ""
                    isLiveThoughtPrefilled = false
                    isLiveThinking = false
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
                                enableDiagnostics = isDiagnosticsEnabled,
                                onStepUpdate = { newStep ->
                                    steps = steps + newStep
                                    liveTokens = ""
                                    liveThoughtText = ""
                                    isLiveThoughtPrefilled = false
                                    isLiveThinking = false
                                },
                                onToken = { token ->
                                    liveTokens += token
                                },
                                onThoughtUpdate = { thought, isPrefilled, isThinking ->
                                    liveThoughtText = thought
                                    isLiveThoughtPrefilled = isPrefilled
                                    isLiveThinking = isThinking
                                }
                            )
                            resultText = when (runResult) {
                                is AgentResult.Success -> runResult.finalAnswer
                                is AgentResult.MaxStepsReached -> runResult.finalAnswer
                                is AgentResult.Cancelled -> runResult.message
                                is AgentResult.Error -> runResult.errorMessage
                            }
                            benchmarkSummary = runResult.benchmarkSummary
                        } catch (e: CancellationException) {
                            resultText = "Agent stopped by user"
                        }
                    }
                },
                enabled = agentState == AgentState.IDLE && isModelLoaded && prompt.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("run_agent_button")
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Run Agent")
            }

            OutlinedButton(
                onClick = {
                    agentRunner.cancel()
                },
                enabled = isRunning,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.testTag("stop_agent_button")
            ) {
                if (isCancelling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Stopping...")
                } else {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        if ((isRunning || isCancelling) && (liveTokens.isNotBlank() || isLiveThinking || liveThoughtText.isNotBlank())) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (isLiveThinking || liveThoughtText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "🧠 Thinking",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (isLiveThoughtPrefilled) {
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
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = if (isLiveThinking) "思考中..." else "思考完了",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isLiveThinking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }

                                if (liveThoughtText.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = liveThoughtText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        val postThinkTokens = if (liveTokens.contains("</think>")) {
                            liveTokens.substring(liveTokens.indexOf("</think>") + 8).trimStart('\n')
                        } else ""

                        if (postThinkTokens.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (isCancelling) "停止処理中..." else "アクション / 回答生成中...",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = postThinkTokens,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(
                            text = if (isCancelling) "停止処理中..." else "推論中...",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = liveTokens,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Benchmark Summary Card (Visible only when Benchmark Mode is ON and summary is available)
        if (isBenchmarkMode && benchmarkSummary != null) {
            val summary = benchmarkSummary!!
            Card(
                modifier = Modifier
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

                        IconButton(
                            onClick = {
                                val text = formatBenchmarkText(
                                    summary = summary,
                                    steps = steps,
                                    finalAnswer = resultText
                                )
                                clipboardManager.setText(AnnotatedString(text))
                                isCopied = true
                                coroutineScope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("agent_benchmark_copy_button")
                        ) {
                            Icon(
                                imageVector = if (isCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = "Benchmark結果をコピー",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
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
                }
            }
        }

        // Steps timeline
        if (steps.isNotEmpty()) {
            Text(
                text = "実行ステップ履歴 (${steps.size} ステップ):",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            steps.forEach { step ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (step.isFinal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Step ${step.stepNumber} ${if (step.isFinal) "(Final)" else ""}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (step.toolCall != null) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val icon = when (step.toolCall.toolName.lowercase()) {
                                            "datetime" -> Icons.Filled.CalendarToday
                                            else -> Icons.Filled.Calculate
                                        }
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = step.toolCall.toolName,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        // Thought block (collapsible)
                        if (!step.thoughtText.isNullOrBlank()) {
                            StepThoughtDisplay(
                                thoughtText = step.thoughtText,
                                isPrefilled = step.isThoughtPrefilled,
                                isCompleted = step.isThoughtCompleted
                            )
                        }

                        if (step.toolCall != null) {
                            Text(
                                text = "引数: ${step.toolCall.arguments}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (step.toolResult != null) {
                            val isSuccess = step.toolResult.isSuccess
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSuccess) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "結果: ${step.toolResult.text}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        // Per-Step Benchmark Metrics (Displayed only when Benchmark Mode is ON)
                        if (isBenchmarkMode && step.metrics != null) {
                            val m = step.metrics
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            StepMetricsDisplay(metrics = m)
                        }
                    }
                }
            }
        }

        // Final Answer Card
        if (resultText != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "最終回答",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = resultText ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
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
            onDismiss = { showSettingsDialog = false },
            onSave = { newPrompt, newThinking, newBudget, newBenchmarkMode, newPrefixCache, newDiagnostics ->
                agentPreferences.systemPrompt = newPrompt
                agentPreferences.isThinkingEnabled = newThinking
                agentPreferences.thinkingBudget = newBudget
                agentPreferences.isBenchmarkMode = newBenchmarkMode
                agentPreferences.isPrefixCacheEnabled = newPrefixCache
                agentPreferences.isDiagnosticsEnabled = newDiagnostics
                systemPrompt = newPrompt
                isThinkingEnabled = newThinking
                thinkingBudget = newBudget
                isBenchmarkMode = newBenchmarkMode
                isPrefixCacheEnabled = newPrefixCache
                isDiagnosticsEnabled = newDiagnostics
            }
        )
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
    finalAnswer: String?
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
        sb.appendLine("• Generated: ${step.genTokens} tok (${String.format(Locale.US, "%.1f", step.genTimeMs)} ms)")
        sb.appendLine("• Speed: ${String.format(Locale.US, "%.2f", step.speedTokPerSec)} tok/s")
        sb.appendLine("• TTFT: ${String.format(Locale.US, "%.1f", step.ttftMs)} ms")
        if (step.toolName != null) {
            sb.appendLine("• Tool: ${String.format(Locale.US, "%.2f", step.toolExecutionTimeMs)} ms (${step.toolName})")
        } else {
            sb.appendLine("• Tool: -")
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
