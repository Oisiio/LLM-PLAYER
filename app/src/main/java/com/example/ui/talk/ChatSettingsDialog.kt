package com.example.ui.talk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.Chat

@Composable
fun ChatSettingsDialog(
    chat: Chat,
    onDismiss: () -> Unit,
    onSave: (Chat) -> Unit
) {
    var temperature by remember { mutableStateOf(chat.temperature.toString()) }
    var topK by remember { mutableStateOf(chat.topK.toString()) }
    var topP by remember { mutableStateOf(chat.topP.toString()) }
    var minP by remember { mutableStateOf(chat.minP.toString()) }
    var typicalP by remember { mutableStateOf(chat.typicalP.toString()) }
    var repetitionPenalty by remember { mutableStateOf(chat.repetitionPenalty.toString()) }
    var penaltyLastN by remember { mutableStateOf(chat.penaltyLastN.toString()) }
    var seed by remember { mutableStateOf(chat.seed.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat設定 (${chat.title})", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "このチャット固有のサンプリング設定です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Temperature") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_temperature")
                )
                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Top-K") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_top_k")
                )
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Top-P") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_top_p")
                )
                OutlinedTextField(
                    value = minP,
                    onValueChange = { minP = it },
                    label = { Text("Min-P") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_min_p")
                )
                OutlinedTextField(
                    value = typicalP,
                    onValueChange = { typicalP = it },
                    label = { Text("Typical-P") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_typical_p")
                )
                OutlinedTextField(
                    value = repetitionPenalty,
                    onValueChange = { repetitionPenalty = it },
                    label = { Text("Repetition Penalty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_repetition_penalty")
                )
                OutlinedTextField(
                    value = penaltyLastN,
                    onValueChange = { penaltyLastN = it },
                    label = { Text("Penalty Last N") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_penalty_last_n")
                )
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text("Seed") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("setting_seed")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updatedChat = chat.copy(
                        temperature = temperature.toFloatOrNull() ?: chat.temperature,
                        topK = topK.toIntOrNull() ?: chat.topK,
                        topP = topP.toFloatOrNull() ?: chat.topP,
                        minP = minP.toFloatOrNull() ?: chat.minP,
                        typicalP = typicalP.toFloatOrNull() ?: chat.typicalP,
                        repetitionPenalty = repetitionPenalty.toFloatOrNull() ?: chat.repetitionPenalty,
                        penaltyLastN = penaltyLastN.toIntOrNull() ?: chat.penaltyLastN,
                        seed = seed.toLongOrNull() ?: chat.seed
                    )
                    onSave(updatedChat)
                },
                modifier = Modifier.testTag("save_chat_settings")
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}
