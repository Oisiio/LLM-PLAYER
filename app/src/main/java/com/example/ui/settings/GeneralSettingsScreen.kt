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
import androidx.compose.ui.platform.testTag
import com.example.data.model.UserPersona
import com.example.data.repository.CharacterViewMode
import com.example.ui.theme.*

@Composable
fun GeneralSettingsScreen(
    currentViewMode: CharacterViewMode,
    currentUserPersona: UserPersona = UserPersona(),
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onApply: (viewMode: CharacterViewMode) -> Unit,
    onSaveUserPersona: ((UserPersona) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(currentViewMode) }
    var userName by remember(currentUserPersona) { mutableStateOf(currentUserPersona.name) }
    var userDescription by remember(currentUserPersona) { mutableStateOf(currentUserPersona.description) }
    var userPersonaText by remember(currentUserPersona) { mutableStateOf(currentUserPersona.persona) }
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
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
                    onSelect = {
                        viewMode = it
                        onApply(it)
                    },
                    columns = 2
                )
            }

            // User Persona Card
            LlmSettingsCard(
                title = "ユーザーペルソナ (User Persona)",
                description = "キャラクターとの会話時に伝達される、あなた自身の名前や設定"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = userName,
                        onValueChange = {
                            userName = it
                            onSaveUserPersona?.invoke(UserPersona(it, userDescription, userPersonaText))
                        },
                        label = { Text("名前 (User Name)") },
                        placeholder = { Text("例: 太郎") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_user_persona_name")
                    )

                    OutlinedTextField(
                        value = userDescription,
                        onValueChange = {
                            userDescription = it
                            onSaveUserPersona?.invoke(UserPersona(userName, it, userPersonaText))
                        },
                        label = { Text("説明・属性 (Description)") },
                        placeholder = { Text("例: 高校生。放課後によく図書館に通っている。") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("settings_user_persona_description")
                    )

                    OutlinedTextField(
                        value = userPersonaText,
                        onValueChange = {
                            userPersonaText = it
                            onSaveUserPersona?.invoke(UserPersona(userName, userDescription, it))
                        },
                        label = { Text("性格・口調・詳細設定 (Persona)") },
                        placeholder = { Text("例: 好奇心旺盛で素直。丁寧な敬語で話しかける。") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("settings_user_persona_persona")
                    )
                }
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
