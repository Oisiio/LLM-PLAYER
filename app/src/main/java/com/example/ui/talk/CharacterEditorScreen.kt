package com.example.ui.talk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.talk.components.AvatarView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorScreen(
    character: Character?,
    onSave: (Character, Uri?) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(character?.name ?: "") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var currentIconUri by remember { mutableStateOf(character?.iconUri) }

    // Personality presets
    val personalityPresetOptions = listOf("優しい", "クール", "ツンデレ", "元気", "知的", "おっとり", "毒舌", "熱血", "ミステリアス")
    var selectedPersonalityPresets by remember {
        mutableStateOf(character?.personality?.presets?.toSet() ?: emptySet())
    }
    var personalityCustom by remember { mutableStateOf(character?.personality?.custom ?: "") }

    // Style presets
    val stylePresetOptions = listOf("丁寧語（です・ます）", "タメ口", "敬語", "お嬢様言葉", "関西弁", "古風・武士言葉", "少年風")
    var selectedStylePresets by remember {
        mutableStateOf(character?.style?.presets?.toSet() ?: emptySet())
    }
    var styleCustom by remember { mutableStateOf(character?.style?.custom ?: "") }

    // System Prompt
    val systemPromptTemplates = listOf(
        "標準アシスタント" to "あなたは親切で丁寧なAIアシスタントです。質問に対して分かりやすく回答してください。",
        "キャラクター対話" to "あなたは以下のキャラクターとして振る舞い、常に役柄を保って自然に対話してください。",
        "カウンセラー" to "あなたは共感力が高く、ユーザーの気持ちに優しく寄り添って励ますカウンセラーです。"
    )
    var systemPromptCustom by remember {
        mutableStateOf(character?.systemPrompt?.custom ?: character?.systemPrompt?.template ?: "")
    }

    // First Message
    var firstMessage by remember { mutableStateOf(character?.firstMessage ?: "") }

    // Scenario
    val scenarioTemplates = listOf(
        "日常の雑談" to "放課後の教室で、のんびりと雑談をしているシチュエーション。",
        "相談室" to "静かな相談室で、ユーザーの悩みを聞いている場面。",
        "冒険の旅路" to "ファンタジー世界の宿屋で、次の目的地について語り合っている。"
    )
    var scenarioContent by remember {
        mutableStateOf(character?.scenario?.content ?: character?.scenario?.template ?: "")
    }

    // Example Dialogue
    var structuredDialogue by remember {
        mutableStateOf<List<DialoguePair>>(character?.exampleDialogue?.structured ?: emptyList())
    }
    var freeformDialogue by remember {
        mutableStateOf(character?.exampleDialogue?.freeform ?: "")
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (character == null) "New Character" else "Character編集") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val trimmedName = name.trim()
                            if (trimmedName.isEmpty()) {
                                errorMessage = "Character名は必須です。空文字にはできません。"
                                return@TextButton
                            }
                            if (trimmedName.length > 50) {
                                errorMessage = "Character名は最大50文字です。"
                                return@TextButton
                            }
                            val updated = (character ?: Character(name = trimmedName)).copy(
                                name = trimmedName,
                                personality = PersonalityData(selectedPersonalityPresets.toList(), personalityCustom),
                                style = StyleData(selectedStylePresets.toList(), styleCustom),
                                systemPrompt = SystemPromptData(null, systemPromptCustom),
                                firstMessage = firstMessage,
                                scenario = ScenarioData(null, scenarioContent),
                                exampleDialogue = ExampleDialogueData(structuredDialogue.take(10), freeformDialogue.take(1000)),
                                lastUsedAt = System.currentTimeMillis()
                            )
                            onSave(updated, selectedImageUri)
                        },
                        modifier = Modifier.testTag("editor_save_button")
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Avatar & Name Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AvatarView(
                        iconUri = selectedImageUri?.toString() ?: currentIconUri,
                        characterName = name.ifBlank { "?" },
                        size = 72
                    )
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .size(28.dp)
                            .offset(x = 4.dp, y = 4.dp)
                            .testTag("editor_pick_avatar")
                    ) {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primary) {
                            Icon(Icons.Default.Image, "画像選択", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp).padding(2.dp))
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 50) name = it },
                    label = { Text("Character名 (必須・最大50文字)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("editor_name_input")
                )
            }

            HorizontalDivider()

            // 最初の文章
            Text("最初の文章 (新規Chat開始時に表示)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = firstMessage,
                onValueChange = { firstMessage = it },
                placeholder = { Text("例: こんにちは！今日もお疲れ様。何かあった？") },
                modifier = Modifier.fillMaxWidth().testTag("editor_first_message_input"),
                minLines = 2
            )

            HorizontalDivider()

            // 性格 (Presets + Custom)
            Text("性格", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            OptInFlowRow {
                personalityPresetOptions.forEach { preset ->
                    val isSelected = selectedPersonalityPresets.contains(preset)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedPersonalityPresets = if (isSelected) {
                                selectedPersonalityPresets - preset
                            } else {
                                selectedPersonalityPresets + preset
                            }
                        },
                        label = { Text(preset) },
                        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                    )
                }
            }
            OutlinedTextField(
                value = personalityCustom,
                onValueChange = { personalityCustom = it },
                label = { Text("性格（自由入力）") },
                modifier = Modifier.fillMaxWidth().testTag("editor_personality_custom")
            )

            HorizontalDivider()

            // 口調 (Presets + Custom)
            Text("口調", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            OptInFlowRow {
                stylePresetOptions.forEach { preset ->
                    val isSelected = selectedStylePresets.contains(preset)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedStylePresets = if (isSelected) {
                                selectedStylePresets - preset
                            } else {
                                selectedStylePresets + preset
                            }
                        },
                        label = { Text(preset) },
                        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                    )
                }
            }
            OutlinedTextField(
                value = styleCustom,
                onValueChange = { styleCustom = it },
                label = { Text("口調（自由入力）") },
                modifier = Modifier.fillMaxWidth().testTag("editor_style_custom")
            )

            HorizontalDivider()

            // System Prompt
            Text("System Prompt", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                systemPromptTemplates.forEach { (label, content) ->
                    AssistChip(
                        onClick = { systemPromptCustom = content },
                        label = { Text(label) }
                    )
                }
            }
            OutlinedTextField(
                value = systemPromptCustom,
                onValueChange = { systemPromptCustom = it },
                label = { Text("System Prompt（自由編集）") },
                modifier = Modifier.fillMaxWidth().testTag("editor_system_prompt_input"),
                minLines = 3
            )

            HorizontalDivider()

            // シナリオ
            Text("シナリオ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scenarioTemplates.forEach { (label, content) ->
                    AssistChip(
                        onClick = { scenarioContent = content },
                        label = { Text(label) }
                    )
                }
            }
            OutlinedTextField(
                value = scenarioContent,
                onValueChange = { scenarioContent = it },
                label = { Text("シナリオ・状況設定") },
                modifier = Modifier.fillMaxWidth().testTag("editor_scenario_input"),
                minLines = 2
            )

            HorizontalDivider()

            // Example Dialogue
            Text("会話例 (Example Dialogue)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "自由入力を優先し、構造化データは補足としてLLMへ渡されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = freeformDialogue,
                onValueChange = { if (it.length <= 1000) freeformDialogue = it },
                label = { Text("自由入力 (最大1000文字)") },
                modifier = Modifier.fillMaxWidth().testTag("editor_example_freeform"),
                minLines = 3,
                supportingText = { Text("${freeformDialogue.length}/1000") }
            )

            Text("構造化会話例 (最大10セット):", style = MaterialTheme.typography.labelLarge)
            structuredDialogue.forEachIndexed { index, pair ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("セット ${index + 1}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = {
                                val updated = structuredDialogue.toMutableList()
                                updated.removeAt(index)
                                structuredDialogue = updated
                            }) {
                                Icon(Icons.Default.Delete, "削除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                        OutlinedTextField(
                            value = pair.user,
                            onValueChange = { newU ->
                                val updated = structuredDialogue.toMutableList()
                                updated[index] = pair.copy(user = newU)
                                structuredDialogue = updated
                            },
                            label = { Text("User") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = pair.character,
                            onValueChange = { newC ->
                                val updated = structuredDialogue.toMutableList()
                                updated[index] = pair.copy(character = newC)
                                structuredDialogue = updated
                            },
                            label = { Text("Character") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            if (structuredDialogue.size < 10) {
                OutlinedButton(
                    onClick = {
                        val updated = structuredDialogue.toMutableList()
                        updated.add(DialoguePair("", ""))
                        structuredDialogue = updated
                    },
                    modifier = Modifier.fillMaxWidth().testTag("editor_add_dialogue_pair")
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("会話例を追加 (${structuredDialogue.size}/10)")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptInFlowRow(content: @Composable () -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        content()
    }
}
