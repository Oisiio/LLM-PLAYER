package com.example.ui.talk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Character
import com.example.data.model.Chat
import com.example.data.model.Message
import com.example.ui.talk.components.AvatarView
import com.example.ui.talk.components.ChatInputBar
import com.example.ui.talk.components.MessageBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    character: Character,
    chat: Chat,
    messages: List<Message>,
    isStreaming: Boolean,
    streamingMessageId: String?,
    streamingText: String,
    onSendMessage: (String) -> Unit,
    onRegenerate: (Message) -> Unit,
    onDeleteCharacterMessage: (Message) -> Unit,
    onEditUserMessageConfirm: (Message, String) -> Unit,
    onCandidateChange: (Message, Int) -> Unit,
    onUpdateChatTitle: (String) -> Unit,
    onDeleteChat: () -> Unit,
    onSaveChatSettings: (Chat) -> Unit,
    onBack: () -> Unit,
    undoDeletedMessages: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var inputText by remember { mutableStateOf("") }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(chat.title) }
    var showDeleteChatConfirm by remember { mutableStateOf(false) }

    // User Inline Edit State
    var editingUserMessage by remember { mutableStateOf<Message?>(null) }
    var editingContent by remember { mutableStateOf("") }

    // Scroll & Input visibility control
    var isInputVisible by remember { mutableStateOf(true) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var hasNewMessageWhileScrolledUp by remember { mutableStateOf(false) }
    var isGenerationFinishedWhileScrolledUp by remember { mutableStateOf(false) }

    // Initial scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (autoScrollEnabled) {
                listState.scrollToItem(messages.size - 1)
            } else {
                hasNewMessageWhileScrolledUp = true
            }
        }
    }

    // Auto-scroll when streaming if enabled
    LaunchedEffect(streamingText) {
        if (isStreaming && autoScrollEnabled && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Generation finished while scrolled up
    LaunchedEffect(isStreaming) {
        if (!isStreaming && hasNewMessageWhileScrolledUp) {
            isGenerationFinishedWhileScrolledUp = true
        }
    }

    // Detect scroll direction for hiding/showing input
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset

        val isScrollingDown = if (currentIndex != previousIndex) {
            currentIndex > previousIndex
        } else {
            currentOffset > previousScrollOffset
        }

        // Close keyboard on scroll
        if (currentOffset != previousScrollOffset || currentIndex != previousIndex) {
            focusManager.clearFocus()
        }

        if (isScrollingDown) {
            // Downward scroll
            val isNearBottom = currentIndex + (listState.layoutInfo.visibleItemsInfo.size) >= messages.size - 1
            if (!isNearBottom) {
                isInputVisible = false
            }
        } else {
            // Upward scroll
            isInputVisible = true
        }

        // Check if user is at the bottom
        val totalItems = messages.size
        if (totalItems > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val atBottom = lastVisible >= totalItems - 1
            if (atBottom) {
                autoScrollEnabled = true
                hasNewMessageWhileScrolledUp = false
                isGenerationFinishedWhileScrolledUp = false
            } else {
                autoScrollEnabled = false
            }
        }

        previousIndex = currentIndex
        previousScrollOffset = currentOffset
    }

    // Rename Chat Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Chat名変更") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { if (it.length <= 30) renameInput = it },
                    label = { Text("Chat名 (最大30文字)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chat_rename_dialog_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        if (renameInput.isNotBlank()) {
                            onUpdateChatTitle(renameInput.trim())
                        }
                    },
                    modifier = Modifier.testTag("chat_rename_dialog_confirm")
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Delete Chat Dialog
    if (showDeleteChatConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteChatConfirm = false },
            title = { Text("Chatの削除") },
            text = { Text("チャット「${chat.title}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteChatConfirm = false
                        onDeleteChat()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("chat_delete_dialog_confirm")
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatConfirm = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Chat Settings Dialog
    if (showSettingsDialog) {
        ChatSettingsDialog(
            chat = chat,
            onDismiss = { showSettingsDialog = false },
            onSave = { updated ->
                showSettingsDialog = false
                onSaveChatSettings(updated)
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(iconUri = character.iconUri, characterName = character.name, size = 32)
                        Spacer(Modifier.width(8.dp))
                        Text(character.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showTopMenu = true }, modifier = Modifier.testTag("chat_top_menu_button")) {
                            Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Chat名変更") },
                                onClick = {
                                    showTopMenu = false
                                    renameInput = chat.title
                                    showRenameDialog = true
                                },
                                modifier = Modifier.testTag("menu_chat_rename")
                            )
                            DropdownMenuItem(
                                text = { Text("Chat削除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showTopMenu = false
                                    showDeleteChatConfirm = true
                                },
                                modifier = Modifier.testTag("menu_chat_delete")
                            )
                            DropdownMenuItem(
                                text = { Text("Chat設定") },
                                onClick = {
                                    showTopMenu = false
                                    showSettingsDialog = true
                                },
                                modifier = Modifier.testTag("menu_chat_settings")
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isInputVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                ChatInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        val text = inputText.trim()
                        if (text.isNotEmpty() && !isStreaming) {
                            inputText = ""
                            onSendMessage(text)
                            coroutineScope.launch {
                                autoScrollEnabled = true
                                delay(50)
                                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
                            }
                        }
                    },
                    isStreaming = isStreaming
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isInputVisible = true
                            if (editingUserMessage != null) {
                                // キャンセル
                                editingUserMessage = null
                            }
                        }
                    )
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (editingUserMessage?.id == msg.id) {
                        // Inline User Edit
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("メッセージを編集", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(
                                    value = editingContent,
                                    onValueChange = { editingContent = it },
                                    modifier = Modifier.fillMaxWidth().testTag("inline_edit_field")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { editingUserMessage = null }) {
                                        Text("キャンセル")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val newText = editingContent.trim()
                                            editingUserMessage = null
                                            if (newText.isNotEmpty()) {
                                                onEditUserMessageConfirm(msg, newText)
                                            }
                                        },
                                        modifier = Modifier.testTag("inline_edit_confirm")
                                    ) {
                                        Text("再生成")
                                    }
                                }
                            }
                        }
                    } else {
                        val isStreamingThis = isStreaming && streamingMessageId == msg.id
                        MessageBubble(
                            message = msg,
                            character = character,
                            isStreamingThis = isStreamingThis,
                            streamingText = streamingText,
                            anyStreaming = isStreaming,
                            onCopy = { text ->
                                clipboardManager.setText(AnnotatedString(text))
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("メッセージをコピーしました", duration = SnackbarDuration.Short)
                                }
                            },
                            onRegenerate = { target ->
                                onRegenerate(target)
                            },
                            onDelete = { target ->
                                onDeleteCharacterMessage(target)
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "メッセージを削除しました",
                                        actionLabel = "元に戻す",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        undoDeletedMessages?.invoke()
                                    }
                                }
                            },
                            onEditUser = { target ->
                                editingUserMessage = target
                                editingContent = target.content
                            },
                            onCandidateChange = { target, newIndex ->
                                onCandidateChange(target, newIndex)
                            }
                        )
                    }
                }
            }

            // Scroll to bottom button / new message indicator
            if (!autoScrollEnabled && (hasNewMessageWhileScrolledUp || isGenerationFinishedWhileScrolledUp)) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            autoScrollEnabled = true
                            hasNewMessageWhileScrolledUp = false
                            isGenerationFinishedWhileScrolledUp = false
                            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .testTag("scroll_to_bottom_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                ) {
                    Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isGenerationFinishedWhileScrolledUp) "↓ 生成完了" else "↓ 新しいメッセージ")
                }
            }
        }
    }
}
