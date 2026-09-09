package com.example.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.AgentPromptBuilder

@Composable
fun AgentSettingsDialog(
    initialSystemPrompt: String,
    initialThinkingEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (systemPrompt: String, thinkingEnabled: Boolean) -> Unit
) {
    var systemPrompt by remember { mutableStateOf(initialSystemPrompt) }
    var thinkingEnabled by remember { mutableStateOf(initialThinkingEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Agent設定",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // System Prompt Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "System Prompt",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                systemPrompt = AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("agent_settings_reset_prompt_button")
                        ) {
                            Icon(
                                Icons.Filled.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("デフォルトに戻す", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(
                        text = "Agentが推論およびTool呼び出しを行う際の基本指示です。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent_settings_system_prompt_input"),
                        minLines = 5,
                        maxLines = 10,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Thinking ON / OFF Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Thinking",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (thinkingEnabled) "ON (思考プロセスを有効化)" else "OFF (思考プロセスを無効化)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (thinkingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = { thinkingEnabled = it },
                        modifier = Modifier.testTag("agent_settings_thinking_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(systemPrompt, thinkingEnabled)
                    onDismiss()
                },
                modifier = Modifier.testTag("agent_settings_save_button")
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("agent_settings_cancel_button")
            ) {
                Text("キャンセル")
            }
        }
    )
}
