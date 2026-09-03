package com.example.ui.talk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.model.Character

@Composable
fun TalkMainScreen(
    viewModel: TalkViewModel,
    modifier: Modifier = Modifier
) {
    val navDestination by viewModel.navDestination.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val lastMessages by viewModel.lastMessages.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val currentChats by viewModel.currentChats.collectAsState()
    val currentMessages by viewModel.currentMessages.collectAsState()

    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingMessageId by viewModel.streamingMessageId.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var showImportDialog by remember { mutableStateOf(false) }

    if (showImportDialog) {
        CharacterImportDialog(
            existingNames = characters.map { it.name },
            onDismiss = { showImportDialog = false },
            onImportConfirmed = { char, overwrite, imgUri ->
                showImportDialog = false
                viewModel.importCharacter(char, overwrite, imgUri)
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val dest = navDestination) {
            is TalkNavDestination.CharacterList -> {
                CharacterListScreen(
                    characters = characters,
                    lastMessages = lastMessages,
                    viewMode = viewMode,
                    onToggleViewMode = { viewModel.toggleViewMode() },
                    onSelectCharacter = { viewModel.selectCharacter(it) },
                    onNewCharacter = { viewModel.openNewCharacter() },
                    onImportCharacter = { showImportDialog = true },
                    onEditCharacter = { viewModel.openEditCharacter(it) },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onDeleteCharacter = { viewModel.deleteCharacter(it) }
                )
            }

            is TalkNavDestination.CharacterEditor -> {
                BackHandler {
                    viewModel.navigateToCharacterList()
                }
                CharacterEditorScreen(
                    character = dest.character,
                    onSave = { updatedChar, imageUri ->
                        viewModel.saveCharacter(updatedChar, imageUri)
                    },
                    onBack = {
                        viewModel.navigateToCharacterList()
                    }
                )
            }

            is TalkNavDestination.ChatList -> {
                BackHandler {
                    viewModel.navigateToCharacterList()
                }
                ChatListScreen(
                    character = dest.character,
                    chats = currentChats,
                    lastMessages = lastMessages,
                    onSelectChat = { viewModel.openChatRoom(dest.character, it) },
                    onNewChat = { viewModel.createNewChat(dest.character) },
                    onRenameChat = { chat, newName -> viewModel.renameChat(chat, newName) },
                    onDeleteChat = { viewModel.deleteChat(it) },
                    onBack = { viewModel.navigateToCharacterList() }
                )
            }

            is TalkNavDestination.ChatRoom -> {
                BackHandler {
                    // Chatを閉じる際、リストへ戻る
                    val chats = viewModel.currentChats.value
                    if (chats.size > 1) {
                        viewModel.selectCharacter(dest.character)
                    } else {
                        viewModel.navigateToCharacterList()
                    }
                }
                ChatScreen(
                    character = dest.character,
                    chat = dest.chat,
                    messages = currentMessages,
                    isStreaming = isStreaming,
                    streamingMessageId = streamingMessageId,
                    streamingText = streamingText,
                    onSendMessage = { text ->
                        viewModel.sendMessage(dest.character, dest.chat, text)
                    },
                    onRegenerate = { msg ->
                        viewModel.regenerateCharacterMessage(dest.character, dest.chat, msg)
                    },
                    onDeleteCharacterMessage = { msg ->
                        viewModel.deleteCharacterMessageWithUndo(dest.chat, msg)
                    },
                    onEditUserMessageConfirm = { msg, newText ->
                        viewModel.editUserMessageAndRegenerate(dest.character, dest.chat, msg, newText)
                    },
                    onCandidateChange = { msg, newIdx ->
                        viewModel.changeCandidate(msg, newIdx)
                    },
                    onUpdateChatTitle = { newTitle ->
                        viewModel.renameChat(dest.chat, newTitle)
                    },
                    onDeleteChat = {
                        viewModel.deleteChat(dest.chat)
                    },
                    onSaveChatSettings = { updated ->
                        viewModel.saveChatSettings(updated)
                    },
                    onBack = {
                        val chats = viewModel.currentChats.value
                        if (chats.size > 1) {
                            viewModel.selectCharacter(dest.character)
                        } else {
                            viewModel.navigateToCharacterList()
                        }
                    },
                    undoDeletedMessages = {
                        viewModel.undoDeleteMessages(dest.chat.id)
                    }
                )
            }
        }
    }
}
