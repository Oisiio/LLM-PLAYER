package com.example.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AdvancedSettingsScreen(
    cpuThreads: Int,
    contextSize: Int,
    maxOutputTokens: Int,
    modelStatus: String,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onResetAllSettings: () -> Unit,
    onApply: (debugLogging: Boolean, logLevel: String, experimental: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var debugLogging by remember { mutableStateOf(false) }
    var logLevel by remember { mutableStateOf("Normal") }
    var experimentalFeatures by remember { mutableStateOf(false) }

    var showResetDialog by remember { mutableStateOf(false) }
    var showSavedSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val logLevels = listOf(
        "Error" to "Error",
        "Warning" to "Warning",
        "Normal" to "Normal",
        "Verbose" to "Verbose"
    )

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = "設定をリセットしますか？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = LlmTextPrimary
                )
            },
            text = {
                Text(
                    text = "すべての設定を初期状態に戻します。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LlmTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetAllSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_reset_settings_button")
                ) {
                    Text("リセット")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("キャンセル", color = LlmTextSecondary)
                }
            },
            containerColor = LlmContainer,
            textContentColor = LlmTextPrimary
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Advanced",
                icon = Icons.Default.Code,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack
            )
        },
        bottomBar = {
            LlmApplyBottomBar(
                text = "✓ 変更を適用",
                onClick = {
                    onApply(debugLogging, logLevel, experimentalFeatures)
                    showSavedSnackbar = true
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LaunchedEffect(showSavedSnackbar) {
            if (showSavedSnackbar) {
                snackbarHostState.showSnackbar("Advanced設定を保存しました")
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
                title = "Advanced設定",
                description = "高度な設定・デバッグ・実験的な機能を管理します"
            )

            // Debug Card
            LlmSettingsCard(
                title = "Debug",
                description = "問題の確認や開発時に使用する設定"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Debug Logging",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LlmTextPrimary
                        )
                        Text(
                            text = "推論やツール実行の詳細ログを記録します",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = debugLogging,
                        onCheckedChange = { debugLogging = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LlmPrimary,
                            checkedTrackColor = LlmPrimaryContainer,
                            uncheckedThumbColor = LlmTextSecondary,
                            uncheckedTrackColor = LlmContainerHigh
                        ),
                        modifier = Modifier.testTag("debug_logging_switch")
                    )
                }

                HorizontalDivider(color = LlmBorder)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Log Level",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LlmTextPrimary
                    )
                    LlmOptionGrid(
                        options = logLevels,
                        selected = logLevel,
                        onSelect = { logLevel = it },
                        columns = 4
                    )
                    Text(
                        text = "※ Verboseは膨大なログを出力するため通常は非推奨です",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmTextTertiary,
                        fontSize = 11.5.sp
                    )
                }
            }

            // Experimental Card
            LlmSettingsCard(
                title = "Experimental",
                description = "安定版ではない機能を管理します"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Experimental Features",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LlmTextPrimary
                        )
                        Text(
                            text = "開発中・実験的な機能を有効にします",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = experimentalFeatures,
                        onCheckedChange = { experimentalFeatures = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LlmPrimary,
                            checkedTrackColor = LlmPrimaryContainer,
                            uncheckedThumbColor = LlmTextSecondary,
                            uncheckedTrackColor = LlmContainerHigh
                        ),
                        modifier = Modifier.testTag("experimental_switch")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LlmWarningAmberDark.copy(alpha = 0.12f))
                        .border(1.dp, LlmWarningAmberDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = LlmWarningAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "※ 動作が不安定になる場合があります",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmWarningAmber,
                        fontSize = 11.5.sp
                    )
                }
            }

            // Runtime Information Card (Read-only, monospace values)
            LlmSettingsCard(
                title = "Runtime Information",
                description = "現在使用している推論環境の情報"
            ) {
                val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Engine", "llama.cpp")
                    InfoRow("Architecture", arch)
                    InfoRow("Backend", "CPU")
                    InfoRow("Threads", "$cpuThreads")
                }
            }

            // Diagnostics Collapsible Section
            LlmCollapsibleSection(
                title = "Diagnostics",
                subtitle = "推論環境の確認とトラブルシューティング",
                initiallyExpanded = false
            ) {
                val totalMemMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
                val maxMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Context Size", "$contextSize tokens")
                    InfoRow("Max Output", "$maxOutputTokens tokens")
                    InfoRow("Model Status", modelStatus)
                    InfoRow("JVM Heap", "$totalMemMb MB / $maxMemMb MB")
                }
            }

            // Reset Card
            LlmSettingsCard(
                title = "Reset",
                description = "設定を初期状態に戻します"
            ) {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("reset_settings_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "設定をリセット",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LlmTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = LlmTextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
