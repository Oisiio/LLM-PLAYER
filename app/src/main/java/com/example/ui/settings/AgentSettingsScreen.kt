package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AgentSettingsScreen(
    currentThinkingEnabled: Boolean,
    currentThinkingBudget: Int,
    currentCalculatorEnabled: Boolean,
    currentDateTimeEnabled: Boolean,
    currentMaxSteps: Int,
    currentMaxOutputTokens: Int,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onApply: (
        thinkingEnabled: Boolean,
        thinkingBudget: Int,
        calculatorEnabled: Boolean,
        dateTimeEnabled: Boolean,
        maxSteps: Int
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var isThinkingEnabled by remember { mutableStateOf(currentThinkingEnabled) }
    var thinkingBudget by remember { mutableIntStateOf(currentThinkingBudget) }
    var isCalculatorEnabled by remember { mutableStateOf(currentCalculatorEnabled) }
    var isDateTimeEnabled by remember { mutableStateOf(currentDateTimeEnabled) }
    var maxSteps by remember { mutableIntStateOf(currentMaxSteps) }

    var showSavedSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val budgetOptions = listOf(
        256 to "256",
        512 to "512",
        1024 to "1024",
        2048 to "2048",
        4096 to "4096",
        8192 to "8192"
    )

    val stepOptions = listOf(
        2 to "2",
        4 to "4",
        6 to "6",
        8 to "8",
        12 to "12",
        16 to "16"
    )

    val enabledToolsCount = (if (isCalculatorEnabled) 1 else 0) + (if (isDateTimeEnabled) 1 else 0)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Agent",
                icon = Icons.Default.SmartToy,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack
            )
        },
        bottomBar = {
            LlmApplyBottomBar(
                text = "✓ 変更を適用",
                onClick = {
                    onApply(
                        isThinkingEnabled,
                        thinkingBudget,
                        isCalculatorEnabled,
                        isDateTimeEnabled,
                        maxSteps
                    )
                    showSavedSnackbar = true
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LaunchedEffect(showSavedSnackbar) {
            if (showSavedSnackbar) {
                snackbarHostState.showSnackbar("Agent設定を保存・適用しました")
                showSavedSnackbar = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro
            LlmIntroSection(
                title = "Agent設定",
                description = "Thinking・Tools・タスク実行に関する設定を管理します"
            )

            // Thinking Card
            LlmSettingsCard(
                title = "Thinking",
                description = "タスク実行前の思考処理を有効にします"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isThinkingEnabled) "有効 (Thinking ON)" else "無効 (Thinking OFF)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isThinkingEnabled) LlmPrimaryLight else LlmTextSecondary
                    )
                    Switch(
                        checked = isThinkingEnabled,
                        onCheckedChange = { isThinkingEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LlmPrimary,
                            checkedTrackColor = LlmPrimaryContainer,
                            uncheckedThumbColor = LlmTextSecondary,
                            uncheckedTrackColor = LlmContainerHigh
                        ),
                        modifier = Modifier.testTag("agent_thinking_switch")
                    )
                }

                Text(
                    text = "※ 複雑なタスクでは、処理手順を整理してから実行します",
                    style = MaterialTheme.typography.bodySmall,
                    color = LlmTextTertiary,
                    fontSize = 11.5.sp
                )
            }

            // Thinking Token Budget Card
            LlmSettingsCard(
                title = "Thinking Token Budget",
                description = "Thinkingに使用できる最大トークン数",
                badge = "$thinkingBudget tokens"
            ) {
                LlmOptionGrid(
                    options = budgetOptions,
                    selected = thinkingBudget,
                    onSelect = { thinkingBudget = it },
                    columns = 3
                )

                Text(
                    text = "※ Agentの処理計画に使用できるトークン上限です。最終回答とは別に管理されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LlmTextTertiary,
                    fontSize = 11.5.sp
                )
            }

            // Tools Card
            LlmSettingsCard(
                title = "Tools",
                description = "Agentが使用できるツールを管理します",
                badge = "$enabledToolsCount 有効"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Calculator
                    ToolRow(
                        name = "Calculator",
                        badge = "MATH",
                        description = "四則演算・数値式の評価",
                        icon = Icons.Default.Calculate,
                        enabled = isCalculatorEnabled,
                        onToggle = { isCalculatorEnabled = it },
                        tag = "tool_calculator_switch"
                    )

                    HorizontalDivider(color = LlmBorder)

                    // DateTime
                    ToolRow(
                        name = "DateTime",
                        badge = "SYS",
                        description = "現在時刻・日付・タイムゾーンの取得",
                        icon = Icons.Default.CalendarToday,
                        enabled = isDateTimeEnabled,
                        onToggle = { isDateTimeEnabled = it },
                        tag = "tool_datetime_switch"
                    )
                }
            }

            // Max Agent Steps Card
            LlmSettingsCard(
                title = "Max Agent Steps",
                description = "1回のタスクでAgentが実行できる最大ステップ数",
                badge = "$maxSteps steps"
            ) {
                LlmOptionGrid(
                    options = stepOptions,
                    selected = maxSteps,
                    onSelect = { maxSteps = it },
                    columns = 6
                )
            }

            // Generation (Read-only link) Card
            LlmSettingsCard(
                title = "Generation",
                badge = "Inference Settingsで管理"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max Output Tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = LlmTextPrimary
                        )
                        Text(
                            text = "Agentの最終回答に使用できる最大トークン数",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "$currentMaxOutputTokens tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = LlmTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Read-only",
                            tint = LlmTextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToolRow(
    name: String,
    badge: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LlmContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LlmPrimaryLight,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = LlmTextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = LlmContainerHigh
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = LlmTextTertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = LlmTextSecondary,
                fontSize = 11.5.sp
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LlmPrimary,
                checkedTrackColor = LlmPrimaryContainer,
                uncheckedThumbColor = LlmTextSecondary,
                uncheckedTrackColor = LlmContainerHigh
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
