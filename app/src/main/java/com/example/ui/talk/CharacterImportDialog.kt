package com.example.ui.talk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Character
import com.example.data.model.CharacterJsonConverter
import com.example.ui.talk.components.AvatarView

@Composable
fun CharacterImportDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onImportConfirmed: (character: Character, overwriteExisting: Boolean, imageUri: Uri?) -> Unit
) {
    val context = LocalContext.current
    var jsonContent by remember { mutableStateOf<String?>(null) }
    var parsedCharacter by remember { mutableStateOf<Character?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    var showConflictDialog by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: ""
                jsonContent = content
                when (val result = CharacterJsonConverter.fromJson(content)) {
                    is CharacterJsonConverter.ValidationResult.Success -> {
                        parsedCharacter = result.character
                        validationError = null
                    }
                    is CharacterJsonConverter.ValidationResult.Error -> {
                        parsedCharacter = null
                        validationError = result.message
                    }
                }
            } catch (e: Exception) {
                validationError = "ファイルの読み込みに失敗しました: ${e.localizedMessage}"
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    if (showConflictDialog && parsedCharacter != null) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("同名のCharacterが存在します") },
            text = { Text("「${parsedCharacter!!.name}」は既に登録されています。どのようにインポートしますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConflictDialog = false
                        onImportConfirmed(parsedCharacter!!, true, selectedImageUri)
                    },
                    modifier = Modifier.testTag("conflict_overwrite")
                ) {
                    Text("上書き")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showConflictDialog = false
                            // 別名で追加
                            val newName = "${parsedCharacter!!.name} (Imported)"
                            val renamed = parsedCharacter!!.copy(name = newName.take(50))
                            onImportConfirmed(renamed, false, selectedImageUri)
                        },
                        modifier = Modifier.testTag("conflict_rename")
                    ) {
                        Text("別名で追加")
                    }
                    TextButton(onClick = { showConflictDialog = false }) {
                        Text("キャンセル")
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Character Import (JSON)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "指定のCharacter JSON形式のファイルを選択してください。",
                    style = MaterialTheme.typography.bodySmall
                )

                // JSON File Picker Button
                OutlinedButton(
                    onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "text/*")) },
                    modifier = Modifier.fillMaxWidth().testTag("pick_json_button")
                ) {
                    Icon(Icons.Default.Description, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (parsedCharacter != null) "JSON選択済み: ${parsedCharacter!!.name}" else "JSONファイルを選択")
                }

                if (validationError != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "【バリデーションエラー】\n$validationError",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (parsedCharacter != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("【解析結果】", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text("名前: ${parsedCharacter!!.name}")
                            if (parsedCharacter!!.personality.presets.isNotEmpty() || parsedCharacter!!.personality.custom.isNotBlank()) {
                                Text("性格: ${parsedCharacter!!.personality.presets.joinToString(", ")} ${parsedCharacter!!.personality.custom}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (parsedCharacter!!.firstMessage.isNotBlank()) {
                                Text("最初の文章: ${parsedCharacter!!.firstMessage}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // Optional Image Picker
                    Text("アイコン画像（任意）:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(
                            iconUri = selectedImageUri?.toString(),
                            characterName = parsedCharacter!!.name,
                            size = 48
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.testTag("pick_avatar_button")
                        ) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (selectedImageUri != null) "画像変更" else "画像を選択")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val char = parsedCharacter ?: return@Button
                    val isDuplicate = existingNames.any { it.equals(char.name, ignoreCase = true) }
                    if (isDuplicate) {
                        showConflictDialog = true
                    } else {
                        onImportConfirmed(char, false, selectedImageUri)
                    }
                },
                enabled = parsedCharacter != null,
                modifier = Modifier.testTag("confirm_import_button")
            ) {
                Text("追加して会話開始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}
