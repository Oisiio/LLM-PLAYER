package com.example.talk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TalkPage { CHARACTERS, CHARACTER_EDITOR, CHATS }

@Composable
fun TalkScreen(repository: TalkRepository) {
    var page by remember { mutableStateOf(TalkPage.CHARACTERS) }
    var selectedCharacterId by remember { mutableStateOf(0L) }
    var editingCharacter by remember { mutableStateOf<Character?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    when (page) {
        TalkPage.CHARACTERS -> CharacterListScreen(
            repository = repository,
            refreshKey = refreshKey,
            onCreate = { editingCharacter = null; page = TalkPage.CHARACTER_EDITOR },
            onEdit = { editingCharacter = it; page = TalkPage.CHARACTER_EDITOR },
            onOpen = { selectedCharacterId = it.id; repository.markCharacterUsed(it.id); page = TalkPage.CHATS },
            onRefresh = { refreshKey++ }
        )
        TalkPage.CHARACTER_EDITOR -> CharacterEditorScreen(
            character = editingCharacter,
            onBack = { page = TalkPage.CHARACTERS },
            onSaved = { refreshKey++; page = TalkPage.CHARACTERS },
            repository = repository
        )
        TalkPage.CHATS -> ChatListScreen(
            repository = repository,
            characterId = selectedCharacterId,
            onBack = { page = TalkPage.CHARACTERS },
            onChanged = { refreshKey++ }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterListScreen(
    repository: TalkRepository,
    refreshKey: Int,
    onCreate: () -> Unit,
    onEdit: (Character) -> Unit,
    onOpen: (Character) -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var characters by remember { mutableStateOf(emptyList<Character>()) }
    var grid by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }
    var menuTarget by remember { mutableStateOf<Character?>(null) }

    LaunchedEffect(search, refreshKey) {
        characters = withContext(Dispatchers.IO) { repository.listCharacters(search) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk") },
                actions = {
                    IconButton(onClick = { grid = !grid }) { Icon(Icons.Filled.GridView, "表示切替") }
                    IconButton(onClick = onCreate) { Icon(Icons.Filled.Add, "Character追加") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Characterを検索") }
            )
            if (characters.isEmpty()) {
                Text("Characterが見つかりません", modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(characters, key = { it.id }) { character ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                                .clickable { onOpen(character) }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("●", modifier = Modifier.size(40.dp), fontWeight = FontWeight.Bold)
                                Column(Modifier.weight(1f)) {
                                    Text(character.name, fontWeight = FontWeight.Bold)
                                    Text("Chatを開く", maxLines = 1)
                                }
                                IconButton(onClick = { menuTarget = character }) { Icon(Icons.Filled.MoreVert, null) }
                            }
                        }
                        DropdownMenu(expanded = menuTarget?.id == character.id, onDismissRequest = { menuTarget = null }) {
                            DropdownMenuItem(text = { Text("編集") }, onClick = { menuTarget = null; onEdit(character) })
                            DropdownMenuItem(text = { Text(if (character.favorite) "お気に入り解除" else "お気に入り") }, onClick = {
                                menuTarget = null
                                scope.launch(Dispatchers.IO) { repository.setCharacterFavorite(character.id, !character.favorite); withContext(Dispatchers.Main) { onRefresh() } }
                            })
                            DropdownMenuItem(text = { Text("削除") }, onClick = { menuTarget = null; deleteTarget = character })
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { character ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Characterを削除") },
            text = { Text("「${character.name}」と紐づくChatをすべて削除します。") },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                scope.launch(Dispatchers.IO) { repository.deleteCharacter(character.id); withContext(Dispatchers.Main) { onRefresh() } }
            }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterEditorScreen(
    repository: TalkRepository,
    character: Character?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember(character?.id) { mutableStateOf(character?.name ?: "") }
    var personality by remember(character?.id) { mutableStateOf(character?.personality ?: "") }
    var style by remember(character?.id) { mutableStateOf(character?.style ?: "") }
    var systemPrompt by remember(character?.id) { mutableStateOf(character?.systemPrompt ?: "") }
    var scenario by remember(character?.id) { mutableStateOf(character?.scenario ?: "") }
    var firstMessage by remember(character?.id) { mutableStateOf(character?.firstMessage ?: "") }
    var exampleDialogue by remember(character?.id) { mutableStateOf(character?.exampleDialogue ?: "") }
    var iconUri by remember(character?.id) { mutableStateOf(character?.iconUri) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) iconUri = uri.toString()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (character == null) "New Character" else "Edit Character") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "戻る") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(name, { name = it.take(50) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("名前") }, singleLine = true) }
            item { OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(if (iconUri == null) "アイコンを選択" else "アイコンを変更") } }
            item { OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("性格") }, minLines = 2) }
            item { OutlinedTextField(style, { style = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("口調") }, minLines = 2) }
            item { OutlinedTextField(systemPrompt, { systemPrompt = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("System Prompt") }, minLines = 4) }
            item { OutlinedTextField(scenario, { scenario = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("シナリオ") }, minLines = 3) }
            item { OutlinedTextField(firstMessage, { firstMessage = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("最初の文章") }, minLines = 3) }
            item { OutlinedTextField(exampleDialogue, { exampleDialogue = it.take(1000) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Example Dialogue") }, minLines = 4) }
            item {
                if (error != null) Text(error!!, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) { error = "名前を入力してください"; return@TextButton }
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            repository.saveCharacter(Character(
                                id = character?.id ?: 0L, name = trimmed, iconUri = iconUri,
                                personality = personality, style = style, systemPrompt = systemPrompt,
                                firstMessage = firstMessage, scenario = scenario, exampleDialogue = exampleDialogue,
                                favorite = character?.favorite ?: false, lastUsedAt = character?.lastUsedAt ?: 0L
                            ))
                        }.onSuccess { withContext(Dispatchers.Main) { onSaved() } }
                         .onFailure { error = it.message ?: "保存に失敗しました" }
                    }
                }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(repository: TalkRepository, characterId: Long, onBack: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var character by remember(characterId) { mutableStateOf<Character?>(null) }
    var chats by remember(characterId) { mutableStateOf(emptyList<Chat>()) }
    var deleteTarget by remember { mutableStateOf<Chat?>(null) }
    var renameTarget by remember { mutableStateOf<Chat?>(null) }
    var rename by remember { mutableStateOf("") }

    fun reload() { scope.launch(Dispatchers.IO) { val c = repository.getCharacter(characterId); val list = repository.listChats(characterId); withContext(Dispatchers.Main) { character = c; chats = list } } }
    LaunchedEffect(characterId) { reload() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(character?.name ?: "Talk") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "戻る") }
        }, actions = {
            IconButton(onClick = {
                scope.launch(Dispatchers.IO) { repository.createChat(characterId); reload() }
            }) { Icon(Icons.Filled.Add, "New Chat") }
        })
    }) { padding ->
        if (chats.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Chat 0件")
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { repository.createChat(characterId); reload() } }) { Text("New Chat") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(chats, key = { it.id }) { chat ->
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(chat.name, fontWeight = FontWeight.Bold); Text(chat.lastMessage.ifBlank { "まだメッセージはありません" }, maxLines = 1) }
                            IconButton(onClick = { renameTarget = chat; rename = chat.name }) { Icon(Icons.Filled.MoreVert, "Chat操作") }
                        }
                    }
                    DropdownMenu(expanded = renameTarget?.id == chat.id, onDismissRequest = { renameTarget = null }) {
                        DropdownMenuItem(text = { Text("名前変更") }, onClick = { renameTarget = chat; rename = chat.name })
                        DropdownMenuItem(text = { Text("削除") }, onClick = { renameTarget = null; deleteTarget = chat })
                    }
                }
            }
        }
    }

    renameTarget?.let { chat ->
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Chat名前変更") }, text = {
            OutlinedTextField(rename, { rename = it.take(30) }, label = { Text("Chat名") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { val id = chat.id; val newName = rename; renameTarget = null; scope.launch(Dispatchers.IO) { repository.renameChat(id, newName); reload() } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("キャンセル") } })
    }
    deleteTarget?.let { chat ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Chatを削除") }, text = { Text("「${chat.name}」を削除します。") }, confirmButton = { TextButton(onClick = { deleteTarget = null; scope.launch(Dispatchers.IO) { repository.deleteChat(chat.id); reload() } }) { Text("削除") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } })
    }
}
