package com.example.ui.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.AgentPreferences
import com.example.agent.AgentResult
import com.example.agent.AgentRunner
import com.example.agent.AgentState
import com.example.agent.AgentStep
import com.example.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    agentRunner: AgentRunner,
    isModelLoaded: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val agentPreferences = remember { AgentPreferences(context) }
    var systemPrompt by remember { mutableStateOf(agentPreferences.systemPrompt) }
    var isThinkingEnabled by remember { mutableStateOf(agentPreferences.isThinkingEnabled) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("12345 * 678 を計算してください") }
    val agentState by agentRunner.state.collectAsState()
    val isRunning = agentState == AgentState.RUNNING
    val isCancelling = agentState == AgentState.CANCELLING
    var steps by remember { mutableStateOf<List<AgentStep>>(emptyList()) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var liveTokens by remember { mutableStateOf("") }

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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                label = { Text("Thinking: ${if (isThinkingEnabled) "ON" else "OFF"}") },
                leadingIcon = {
                    Icon(
                        if (isThinkingEnabled) Icons.Filled.Psychology else Icons.Filled.PsychologyAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("agent_thinking_status_chip")
            )
            AssistChip(
                onClick = { showSettingsDialog = true },
                label = { Text("Agent設定") },
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
                    liveTokens = ""
                    coroutineScope.launch {
                        try {
                            val runResult = agentRunner.run(
                                userPrompt = prompt,
                                systemPrompt = systemPrompt,
                                enableThinking = isThinkingEnabled,
                                onStepUpdate = { newStep ->
                                    steps = steps + newStep
                                    liveTokens = ""
                                },
                                onToken = { token ->
                                    liveTokens += token
                                }
                            )
                            resultText = when (runResult) {
                                is AgentResult.Success -> runResult.finalAnswer
                                is AgentResult.MaxStepsReached -> runResult.finalAnswer
                                is AgentResult.Cancelled -> runResult.message
                                is AgentResult.Error -> runResult.errorMessage
                            }
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

        if ((isRunning || isCancelling) && liveTokens.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
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
                            horizontalArrangement = Arrangement.SpaceBetween
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
            onDismiss = { showSettingsDialog = false },
            onSave = { newPrompt, newThinking ->
                agentPreferences.systemPrompt = newPrompt
                agentPreferences.isThinkingEnabled = newThinking
                systemPrompt = newPrompt
                isThinkingEnabled = newThinking
            }
        )
    }
}
