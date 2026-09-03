package com.example.ui.talk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Character
import com.example.data.model.Chat
import com.example.data.model.Message
import com.example.ui.talk.components.AvatarView

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    character: Character,
    chats: List<Chat>,
    lastMessages: Map<String, Message>,
    onSelectChat: (Chat) -> Unit,
    onNewChat: () -> Unit,
    onRenameChat: (Chat, String) -> Unit,
    onDeleteChat: (Chat) -> Unit,
    onBack: () -> Unit
) {
    var selectedChatForMenu by remember { mutableStateOf<Chat?>(null) }
    var chatToDelete by remember { mutableStateOf<Chat?>(null) }
    var chatToRename by remember { mutableStateOf<Chat?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // Rename Dialog
    if (chatToRename != null) {
        AlertDialog(
            onDismissRequest = { chatToRename = null },
            title = { Text("Chat名変更") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { if (it.length <= 30) renameInput = it },
                    label = { Text("Chat名 (最大30文字)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_chat_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = chatToRename!!
                        chatToRename = null
                        if (renameInput.isNotBlank()) {
                            onRenameChat(target, renameInput.trim())
                        }
                    },
                    modifier = Modifier.testTag("confirm_rename_chat")
                ) {
                    Text("変更")
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToRename = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Delete Dialog
    if (chatToDelete != null) {
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Chatの削除") },
            text = { Text("チャット「${chatToDelete!!.title}」を削除しますか？メッセージ履歴も削除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = chatToDelete!!
                        chatToDelete = null
                        onDeleteChat(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_chat")
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(iconUri = character.iconUri, characterName = character.name, size = 32)
                        Spacer(Modifier.width(10.dp))
                        Text(character.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_list_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat, modifier = Modifier.testTag("new_chat_button")) {
                        Icon(Icons.Default.Add, contentDescription = "新規チャット")
                    }
                }
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Chat履歴がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNewChat) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("New Chat を開始")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(chats, key = { it.id }) { chat ->
                    val lastMsg = lastMessages[chat.id]
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelectChat(chat) },
                                onLongClick = { selectedChatForMenu = chat }
                            )
                            .testTag("chat_item_${chat.id}"),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarView(
                                iconUri = character.iconUri,
                                characterName = character.name,
                                size = 44
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                val preview = lastMsg?.displayContent ?: "メッセージなし"
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }

        // Long-press Context Menu for Chat (名前変更, 削除)
        if (selectedChatForMenu != null) {
            val target = selectedChatForMenu!!
            AlertDialog(
                onDismissRequest = { selectedChatForMenu = null },
                title = { Text(target.title, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        ListItem(
                            headlineContent = { Text("名前変更") },
                            leadingContent = { Icon(Icons.Default.Edit, null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = {
                                    selectedChatForMenu = null
                                    renameInput = target.title
                                    chatToRename = target
                                })
                                .testTag("menu_rename_chat")
                        )
                        ListItem(
                            headlineContent = { Text("削除", color = MaterialTheme.colorScheme.error) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = {
                                    selectedChatForMenu = null
                                    chatToDelete = target
                                })
                                .testTag("menu_delete_chat")
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedChatForMenu = null }) {
                        Text("閉じる")
                    }
                }
            )
        }
    }
}
