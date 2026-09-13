package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

enum class SettingsSubpage {
    ROOT,
    GENERAL,
    INFERENCE,
    AGENT,
    PERFORMANCE,
    ADVANCED
}

private data class SettingsCategoryItem(
    val id: SettingsSubpage,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    onNavigateSubpage: (SettingsSubpage) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = listOf(
        SettingsCategoryItem(
            id = SettingsSubpage.GENERAL,
            title = "General",
            description = "アプリの基本設定",
            icon = Icons.Default.Tune,
            testTag = "settings_cat_general"
        ),
        SettingsCategoryItem(
            id = SettingsSubpage.INFERENCE,
            title = "Inference",
            description = "生成・コンテキスト・ランタイム設定",
            icon = Icons.Default.AutoAwesome,
            testTag = "settings_cat_inference"
        ),
        SettingsCategoryItem(
            id = SettingsSubpage.AGENT,
            title = "Agent",
            description = "Thinking・Tools・実行設定",
            icon = Icons.Default.SmartToy,
            testTag = "settings_cat_agent"
        ),
        SettingsCategoryItem(
            id = SettingsSubpage.PERFORMANCE,
            title = "Performance",
            description = "推論速度・スレッド・メモリ設定",
            icon = Icons.Default.Speed,
            testTag = "settings_cat_performance"
        ),
        SettingsCategoryItem(
            id = SettingsSubpage.ADVANCED,
            title = "Advanced",
            description = "高度な設定・デバッグ",
            icon = Icons.Default.Code,
            testTag = "settings_cat_advanced"
        )
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LlmBackground,
        topBar = {
            LlmScreenHeader(
                title = "Settings",
                icon = Icons.Default.Settings,
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Intro
            LlmIntroSection(
                title = "設定",
                description = "LLM-PLAYERの動作や生成設定を管理します"
            )

            // Single Container with 5 categories
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = LlmContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, LlmBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    categories.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateSubpage(item.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag(item.testTag),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Icon Box
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LlmPrimaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = LlmPrimaryLight,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Texts
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LlmTextPrimary,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LlmTextSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            // Right Chevron
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = LlmTextTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (index < categories.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = LlmBorder
                            )
                        }
                    }
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
