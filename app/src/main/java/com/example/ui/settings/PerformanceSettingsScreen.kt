package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun PerformanceSettingsScreen(
    currentCpuThreads: Int,
    currentCpuThreadsBatch: Int,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onApply: (cpuThreads: Int, cpuThreadsBatch: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var cpuThreads by remember { mutableIntStateOf(currentCpuThreads) }
    var cpuThreadsBatch by remember { mutableIntStateOf(currentCpuThreadsBatch) }
    var kvCacheType by remember { mutableStateOf("Auto") }
    var memoryOptimization by remember { mutableStateOf(true) }
    var backend by remember { mutableStateOf("Auto") }
    var threadAffinity by remember { mutableStateOf("Auto") }

    var showSavedSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val threadOptions = listOf(
        1 to "1",
        2 to "2",
        3 to "3",
        4 to "4",
        5 to "5",
        6 to "6",
        7 to "7",
        8 to "8"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Performance",
                icon = Icons.Default.Speed,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack
            )
        },
        bottomBar = {
            LlmApplyBottomBar(
                text = "✓ 変更を適用",
                onClick = {
                    onApply(cpuThreads, cpuThreadsBatch)
                    showSavedSnackbar = true
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LaunchedEffect(showSavedSnackbar) {
            if (showSavedSnackbar) {
                snackbarHostState.showSnackbar("Performance設定を適用しました")
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
                title = "Performance設定",
                description = "推論速度・スレッド・メモリに関する設定を管理します"
            )

            // CPU Threads Card
            LlmSettingsCard(
                title = "CPU Threads",
                description = "推論処理に使用するCPUスレッド数を設定します",
                badge = "$cpuThreads threads"
            ) {
                LlmOptionGrid(
                    options = threadOptions,
                    selected = cpuThreads,
                    onSelect = { cpuThreads = it },
                    columns = 4
                )

                Text(
                    text = "※ スレッド数を増やすと処理速度が向上する場合がありますが、消費電力や発熱も増加する可能性があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LlmTextTertiary,
                    fontSize = 11.5.sp
                )
            }

            // Memory Card
            LlmSettingsCard(
                title = "Memory",
                description = "推論時のメモリ使用量を管理します"
            ) {
                // KV Cache
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "KV Cache",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LlmTextPrimary
                        )
                        Text(
                            text = "Auto",
                            style = MaterialTheme.typography.labelSmall,
                            color = LlmPrimaryLight,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "会話履歴などを保持するためのキャッシュ形式",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmTextSecondary,
                        fontSize = 12.sp
                    )

                    LlmOptionGrid(
                        options = listOf("Auto" to "Auto", "Q8_0" to "Q8_0", "Q4_0" to "Q4_0"),
                        selected = kvCacheType,
                        onSelect = { kvCacheType = it },
                        disabledValues = setOf("Q8_0", "Q4_0"), // Q8_0 and Q4_0 disabled as per llama.cpp current JNI
                        columns = 3
                    )
                }

                HorizontalDivider(color = LlmBorder)

                // Memory Optimization Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Memory Optimization",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LlmTextPrimary
                        )
                        Text(
                            text = "メモリ使用量を抑えるための最適化を有効にします",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = memoryOptimization,
                        onCheckedChange = { memoryOptimization = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LlmPrimary,
                            checkedTrackColor = LlmPrimaryContainer,
                            uncheckedThumbColor = LlmTextSecondary,
                            uncheckedTrackColor = LlmContainerHigh
                        ),
                        modifier = Modifier.testTag("memory_opt_switch")
                    )
                }
            }

            // Runtime Card
            LlmSettingsCard(
                title = "Runtime",
                description = "モデル推論に使用するランタイム設定"
            ) {
                // Backend
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Backend",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LlmTextPrimary
                    )
                    LlmOptionGrid(
                        options = listOf("Auto" to "Auto", "CPU" to "CPU", "GPU" to "GPU (N/A)"),
                        selected = backend,
                        onSelect = { backend = it },
                        disabledValues = setOf("GPU"),
                        columns = 3
                    )
                }

                HorizontalDivider(color = LlmBorder)

                // Thread Affinity
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Thread Affinity",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LlmTextPrimary
                    )
                    Text(
                        text = "CPUコアの使用方法を調整します",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmTextSecondary,
                        fontSize = 12.sp
                    )
                    LlmOptionGrid(
                        options = listOf(
                            "Auto" to "Auto",
                            "Performance" to "Performance",
                            "Balanced" to "Balanced"
                        ),
                        selected = threadAffinity,
                        onSelect = { threadAffinity = it },
                        columns = 3
                    )
                }
            }

            // Advanced Optimization Collapsible Section
            LlmCollapsibleSection(
                title = "Advanced Optimization",
                subtitle = "高度な推論最適化設定",
                initiallyExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Prompt Batch Threads",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = LlmTextPrimary
                            )
                            Text(
                                text = "$cpuThreadsBatch",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LlmPrimaryLight,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "プロンプト処理時に使用するスレッド数です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 11.5.sp
                        )
                        Slider(
                            value = cpuThreadsBatch.toFloat(),
                            onValueChange = { cpuThreadsBatch = it.toInt().coerceIn(1, 8) },
                            valueRange = 1f..8f,
                            steps = 6,
                            colors = SliderDefaults.colors(
                                thumbColor = LlmPrimary,
                                activeTrackColor = LlmPrimary
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Batch Size",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary
                        )
                        Text(
                            text = "512 tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
