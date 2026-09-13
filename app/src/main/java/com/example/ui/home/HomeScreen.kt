package com.example.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.example.ui.navigation.AppDestination
import com.example.ui.settings.LlmIntroSection
import com.example.ui.settings.LlmScreenHeader
import com.example.ui.settings.LlmSettingsCard
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    isModelLoaded: Boolean,
    modelName: String,
    modelStatus: String,
    contextSize: Int,
    cpuThreads: Int,
    onOpenDrawer: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
    onPickModel: () -> Unit,
    onOpenGenerationTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Home",
                icon = Icons.Default.Home,
                onOpenDrawer = onOpenDrawer
            )
        }
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
                title = "LLM-PLAYER",
                description = "Local AI Workspace · オンデバイス推論プラットフォーム"
            )

            // Active Model Hero Card
            LlmSettingsCard(
                title = "Active Model",
                badge = if (isModelLoaded) "LOADED" else "NO MODEL"
            ) {
                if (isModelLoaded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = modelName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LlmTextPrimary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = LlmContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LlmBorder)
                            ) {
                                Text(
                                    text = "$contextSize tokens",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LlmPrimaryLight,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = LlmContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LlmBorder)
                            ) {
                                Text(
                                    text = "$cpuThreads threads",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LlmTextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onNavigate(AppDestination.CHAT) },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LlmPrimary, contentColor = Color(0xFF0F101A))
                            ) {
                                Icon(Icons.Default.Forum, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Chat", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Button(
                                onClick = { onNavigate(AppDestination.AGENT) },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LlmContainerHigh, contentColor = LlmTextPrimary)
                            ) {
                                Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(16.dp), tint = LlmPrimaryLight)
                                Spacer(Modifier.width(6.dp))
                                Text("Agent", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "ローカル推論を開始するには、GGUF形式のモデルをインポートしてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LlmTextSecondary,
                            fontSize = 12.5.sp
                        )
                        Button(
                            onClick = onPickModel,
                            modifier = Modifier.fillMaxWidth().height(42.dp).testTag("home_pick_model_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LlmPrimary, contentColor = Color(0xFF0F101A))
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("モデルを選択・ロード", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Quick Access Navigation
            Text(
                text = "ワークスペース機能",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = LlmTextPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = LlmContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, LlmBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HomeNavItem(
                        title = "Chat",
                        description = "キャラクターとの対話・シナリオ進行",
                        icon = Icons.Default.Forum,
                        onClick = { onNavigate(AppDestination.CHAT) },
                        testTag = "home_nav_chat"
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = LlmBorder)
                    HomeNavItem(
                        title = "Agent",
                        description = "Thinking・計算・日時ツールによる自律タスク実行",
                        icon = Icons.Default.SmartToy,
                        onClick = { onNavigate(AppDestination.AGENT) },
                        testTag = "home_nav_agent"
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = LlmBorder)
                    HomeNavItem(
                        title = "Models",
                        description = "GGUFモデルの管理とストレージ状態の確認",
                        icon = Icons.Default.Memory,
                        onClick = { onNavigate(AppDestination.MODELS) },
                        testTag = "home_nav_models"
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = LlmBorder)
                    HomeNavItem(
                        title = "Settings",
                        description = "生成・Agent・パフォーマンス・高度な設定",
                        icon = Icons.Default.Settings,
                        onClick = { onNavigate(AppDestination.SETTINGS) },
                        testTag = "home_nav_settings"
                    )
                }
            }

            // Quick Generation Test
            LlmSettingsCard(
                title = "Inference Playground",
                description = "生のプロンプトでモデルのサンプリング動作を直接テストします"
            ) {
                OutlinedButton(
                    onClick = onOpenGenerationTest,
                    modifier = Modifier.fillMaxWidth().height(42.dp).testTag("home_open_generation_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LlmBorder)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = LlmPrimaryLight, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("テスト生成画面を開く", color = LlmTextPrimary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // Footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LLM-PLAYER • v0.4.2",
                    style = MaterialTheme.typography.bodySmall,
                    color = LlmTextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun HomeNavItem(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LlmPrimaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LlmPrimaryLight,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = LlmTextPrimary,
                fontSize = 15.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = LlmTextSecondary,
                fontSize = 12.sp
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = LlmTextTertiary,
            modifier = Modifier.size(14.dp)
        )
    }
}
