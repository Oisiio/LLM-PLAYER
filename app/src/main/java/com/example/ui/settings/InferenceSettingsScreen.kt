package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun InferenceSettingsScreen(
    currentContextSize: Int,
    currentMaxOutputTokens: Int,
    currentTemperature: Float,
    currentTopK: Int,
    currentTopP: Float,
    currentMinP: Float,
    currentTypicalP: Float,
    currentRepetitionPenalty: Float,
    currentPenaltyLastN: Int,
    modelName: String,
    modelStatus: String,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onApply: (
        contextSize: Int,
        maxOutputTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        typicalP: Float,
        repetitionPenalty: Float,
        penaltyLastN: Int
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var contextSize by remember { mutableIntStateOf(currentContextSize) }
    var maxOutputTokens by remember { mutableIntStateOf(currentMaxOutputTokens) }
    var temperature by remember { mutableFloatStateOf(currentTemperature) }
    var topK by remember { mutableIntStateOf(currentTopK) }
    var topP by remember { mutableFloatStateOf(currentTopP) }
    var minP by remember { mutableFloatStateOf(currentMinP) }
    var typicalP by remember { mutableFloatStateOf(currentTypicalP) }
    var repetitionPenalty by remember { mutableFloatStateOf(currentRepetitionPenalty) }
    var penaltyLastN by remember { mutableIntStateOf(currentPenaltyLastN) }

    var showSavedSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val contextOptions = listOf(
        512 to "512",
        1024 to "1K",
        2048 to "2K",
        4096 to "4K",
        8192 to "8K",
        16384 to "16K",
        24576 to "24K",
        32768 to "32K"
    )
    val disabledContextSizes = setOf(24576, 32768)

    val maxTokensOptions = listOf(
        128 to "128",
        256 to "256",
        512 to "512",
        1024 to "1024",
        2048 to "2048",
        4096 to "4096"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Inference",
                icon = Icons.Default.AutoAwesome,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack
            )
        },
        bottomBar = {
            LlmApplyBottomBar(
                text = "✓ 変更を適用",
                onClick = {
                    onApply(
                        contextSize,
                        maxOutputTokens,
                        temperature,
                        topK,
                        topP,
                        minP,
                        typicalP,
                        repetitionPenalty,
                        penaltyLastN
                    )
                    showSavedSnackbar = true
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LaunchedEffect(showSavedSnackbar) {
            if (showSavedSnackbar) {
                snackbarHostState.showSnackbar("生成設定を保存・適用しました")
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
                title = "生成設定",
                description = "文章生成・コンテキスト・モデルランタイムを管理します"
            )

            // Context Length Card
            val ctxLabel = when {
                contextSize >= 1024 -> "${contextSize / 1024}K tokens"
                else -> "$contextSize tokens"
            }
            LlmSettingsCard(
                title = "コンテキスト長",
                description = "モデルが一度に扱えるコンテキストの長さ",
                badge = ctxLabel
            ) {
                LlmOptionGrid(
                    options = contextOptions,
                    selected = contextSize,
                    onSelect = { contextSize = it },
                    disabledValues = disabledContextSizes,
                    columns = 4
                )

                // Warning Callout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LlmWarningAmberDark.copy(alpha = 0.12f))
                        .border(1.dp, LlmWarningAmberDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = LlmWarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "大きなコンテキストはメモリ使用量と推論時間が増加する場合があります",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmWarningAmber,
                        fontSize = 11.5.sp
                    )
                }
            }

            // Max Output Tokens Card
            LlmSettingsCard(
                title = "Max Output Tokens",
                description = "1回の生成で出力できる最大トークン数",
                badge = "$maxOutputTokens tokens"
            ) {
                LlmOptionGrid(
                    options = maxTokensOptions,
                    selected = maxOutputTokens,
                    onSelect = { maxOutputTokens = it },
                    columns = 3
                )
            }

            // Temperature Card
            LlmSettingsCard(
                title = "Temperature",
                description = "生成結果のランダム性を調整します",
                badge = String.format(Locale.US, "%.2f", temperature)
            ) {
                Slider(
                    value = temperature,
                    onValueChange = {
                        val rounded = (Math.round(it * 20.0f) / 20.0f).coerceIn(0.0f, 2.0f)
                        temperature = rounded
                    },
                    valueRange = 0.0f..2.0f,
                    steps = 39,
                    colors = SliderDefaults.colors(
                        thumbColor = LlmPrimary,
                        activeTrackColor = LlmPrimary,
                        inactiveTrackColor = LlmContainerHigh
                    ),
                    modifier = Modifier.testTag("inference_temperature_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.0 安定", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary, fontSize = 11.sp)
                    Text("2.0 創造的", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary, fontSize = 11.sp)
                }

                Text(
                    text = "低いほど安定した出力 / 高いほど多様な出力",
                    style = MaterialTheme.typography.bodySmall,
                    color = LlmTextTertiary,
                    fontSize = 11.sp
                )
            }

            // Sampling Collapsible Section
            LlmCollapsibleSection(
                title = "Sampling",
                subtitle = "トークン選択に関する詳細設定",
                initiallyExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Top-K
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Top-K", style = MaterialTheme.typography.bodyMedium, color = LlmTextPrimary, fontWeight = FontWeight.Medium)
                            Text("$topK", style = MaterialTheme.typography.bodyMedium, color = LlmPrimaryLight, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = topK.toFloat(),
                            onValueChange = { topK = it.toInt().coerceIn(1, 100) },
                            valueRange = 1f..100f,
                            steps = 98,
                            colors = SliderDefaults.colors(thumbColor = LlmPrimary, activeTrackColor = LlmPrimary)
                        )
                    }

                    // Top-P
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Top-P", style = MaterialTheme.typography.bodyMedium, color = LlmTextPrimary, fontWeight = FontWeight.Medium)
                            Text(String.format(Locale.US, "%.2f", topP), style = MaterialTheme.typography.bodyMedium, color = LlmPrimaryLight, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = topP,
                            onValueChange = {
                                val rounded = (Math.round(it * 20.0f) / 20.0f).coerceIn(0.05f, 1.0f)
                                topP = rounded
                            },
                            valueRange = 0.05f..1.0f,
                            steps = 18,
                            colors = SliderDefaults.colors(thumbColor = LlmPrimary, activeTrackColor = LlmPrimary)
                        )
                    }

                    // Min-P & Typical-P
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = String.format(Locale.US, "%.2f", minP),
                            onValueChange = { str -> str.toFloatOrNull()?.let { minP = it.coerceIn(0.0f, 1.0f) } },
                            label = { Text("Min-P") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LlmPrimary,
                                unfocusedBorderColor = LlmBorder
                            )
                        )
                        OutlinedTextField(
                            value = String.format(Locale.US, "%.2f", typicalP),
                            onValueChange = { str -> str.toFloatOrNull()?.let { typicalP = it.coerceIn(0.0f, 1.0f) } },
                            label = { Text("Typical-P") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LlmPrimary,
                                unfocusedBorderColor = LlmBorder
                            )
                        )
                    }

                    // Repetition Penalty & Penalty Last N
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = String.format(Locale.US, "%.2f", repetitionPenalty),
                            onValueChange = { str -> str.toFloatOrNull()?.let { repetitionPenalty = it.coerceAtLeast(0.0f) } },
                            label = { Text("Repetition Penalty") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LlmPrimary,
                                unfocusedBorderColor = LlmBorder
                            )
                        )
                        OutlinedTextField(
                            value = penaltyLastN.toString(),
                            onValueChange = { str -> str.toIntOrNull()?.let { penaltyLastN = it.coerceAtLeast(0) } },
                            label = { Text("Penalty Last N") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LlmPrimary,
                                unfocusedBorderColor = LlmBorder
                            )
                        )
                    }
                }
            }

            // Model Runtime Collapsible Section
            LlmCollapsibleSection(
                title = "Model Runtime",
                subtitle = "モデルの実行情報と状態",
                initiallyExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Active Model", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text(modelName, style = MaterialTheme.typography.bodySmall, color = LlmTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Format", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text("GGUF", style = MaterialTheme.typography.bodySmall, color = LlmTextPrimary, fontFamily = FontFamily.Monospace)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Status", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text(modelStatus, style = MaterialTheme.typography.bodySmall, color = if (modelStatus.startsWith("SUCCESS:")) LlmSuccess else LlmTextSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "※ CPUスレッド数などのデバイスリソース設定は Performance 画面で管理されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LlmTextTertiary,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
