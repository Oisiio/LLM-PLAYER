package com.example.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.CharacterViewMode
import com.example.ui.theme.*

@Composable
fun GeneralSettingsScreen(
    currentViewMode: CharacterViewMode,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onApply: (viewMode: CharacterViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(currentViewMode) }
    var showSavedSnackbar by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModeOptions = listOf(
        CharacterViewMode.LIST to "リスト表示",
        CharacterViewMode.CARD to "カード表示"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "General",
                icon = Icons.Default.Tune,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack
            )
        },
        bottomBar = {
            LlmApplyBottomBar(
                text = "✓ 変更を適用",
                onClick = {
                    onApply(viewMode)
                    showSavedSnackbar = true
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LaunchedEffect(showSavedSnackbar) {
            if (showSavedSnackbar) {
                snackbarHostState.showSnackbar("General設定を適用しました")
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
                title = "アプリの基本設定",
                description = "全体の動作や表示スタイルを管理します"
            )

            // Display Card
            LlmSettingsCard(
                title = "キャラクター表示形式",
                description = "Talk画面でのキャラクター一覧表示スタイル"
            ) {
                LlmOptionGrid(
                    options = viewModeOptions,
                    selected = viewMode,
                    onSelect = { viewMode = it },
                    columns = 2
                )
            }

            // About Card
            LlmSettingsCard(
                title = "アプリケーション情報"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("App Name", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text("LLM-PLAYER", style = MaterialTheme.typography.bodySmall, color = LlmTextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Type", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text("Local AI Workspace", style = MaterialTheme.typography.bodySmall, color = LlmPrimaryLight)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Version", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
                        Text("0.4.2", style = MaterialTheme.typography.bodySmall, color = LlmTextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
