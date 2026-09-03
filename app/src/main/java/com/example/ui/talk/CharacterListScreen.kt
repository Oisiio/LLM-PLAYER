package com.example.ui.talk

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Character
import com.example.data.model.Message
import com.example.data.repository.CharacterViewMode
import com.example.ui.talk.components.AvatarView

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(
    characters: List<Character>,
    lastMessages: Map<String, Message>,
    viewMode: CharacterViewMode,
    onToggleViewMode: () -> Unit,
    onSelectCharacter: (Character) -> Unit,
    onNewCharacter: () -> Unit,
    onImportCharacter: () -> Unit,
    onEditCharacter: (Character) -> Unit,
    onToggleFavorite: (Character) -> Unit,
    onDeleteCharacter: (Character) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showPlusMenu by remember { mutableStateOf(false) }

    var selectedCharacterForMenu by remember { mutableStateOf<Character?>(null) }
    var characterToDelete by remember { mutableStateOf<Character?>(null) }

    // Character filtering and sorting
    val displayedCharacters = remember(characters, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            // Sort: favorites first, then non-favorites, both by lastUsedAt descending
            val favorites = characters.filter { it.isFavorite }.sortedByDescending { it.lastUsedAt }
            val others = characters.filter { !it.isFavorite }.sortedByDescending { it.lastUsedAt }
            favorites + others
        } else {
            // Search active: ignore favorite ordering, rank by match score
            characters
                .filter { it.name.lowercase().contains(query) }
                .sortedWith(
                    compareByDescending<Character> {
                        // Exact match > Starts with > Contains
                        val lower = it.name.lowercase()
                        when {
                            lower == query -> 3
                            lower.startsWith(query) -> 2
                            else -> 1
                        }
                    }.thenByDescending { it.lastUsedAt }
                )
        }
    }

    if (characterToDelete != null) {
        AlertDialog(
            onDismissRequest = { characterToDelete = null },
            title = { Text("Characterの削除") },
            text = { Text("「${characterToDelete!!.name}」と紐づくすべてのチャット履歴を削除しますか？この操作は元に戻せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val c = characterToDelete!!
                        characterToDelete = null
                        onDeleteCharacter(c)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_character")
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { characterToDelete = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Character名を検索…") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                                .testTag("character_search_input"),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, "クリア")
                                    }
                                }
                            }
                        )
                    } else {
                        Text("Talk", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        },
                        modifier = Modifier.testTag("toggle_search_button")
                    ) {
                        Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, "検索")
                    }

                    // ▦ Toggle View Mode (List <-> Card)
                    IconButton(onClick = onToggleViewMode, modifier = Modifier.testTag("toggle_view_mode_button")) {
                        Icon(
                            imageVector = if (viewMode == CharacterViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                            contentDescription = "表示切替"
                        )
                    }

                    // ＋ Button
                    Box {
                        IconButton(onClick = { showPlusMenu = true }, modifier = Modifier.testTag("plus_menu_button")) {
                            Icon(Icons.Default.Add, contentDescription = "作成")
                        }
                        DropdownMenu(
                            expanded = showPlusMenu,
                            onDismissRequest = { showPlusMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New Character") },
                                onClick = {
                                    showPlusMenu = false
                                    onNewCharacter()
                                },
                                leadingIcon = { Icon(Icons.Default.PersonAdd, null) },
                                modifier = Modifier.testTag("menu_new_character")
                            )
                            DropdownMenuItem(
                                text = { Text("Import Character") },
                                onClick = {
                                    showPlusMenu = false
                                    onImportCharacter()
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                modifier = Modifier.testTag("menu_import_character")
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (displayedCharacters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            "Characterが見つかりません",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Characterがいません",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onNewCharacter) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(6.dp))
                                Text("New Character を作成")
                            }
                        }
                    }
                }
            } else if (viewMode == CharacterViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(displayedCharacters, key = { it.id }) { char ->
                        val lastMsg = lastMessages[char.id]
                        CharacterListItem(
                            character = char,
                            lastMessage = lastMsg,
                            onClick = { onSelectCharacter(char) },
                            onLongClick = { selectedCharacterForMenu = char }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedCharacters, key = { it.id }) { char ->
                        val lastMsg = lastMessages[char.id]
                        CharacterCardItem(
                            character = char,
                            lastMessage = lastMsg,
                            onClick = { onSelectCharacter(char) },
                            onLongClick = { selectedCharacterForMenu = char }
                        )
                    }
                }
            }

            // Character Long-press Menu
            if (selectedCharacterForMenu != null) {
                val target = selectedCharacterForMenu!!
                AlertDialog(
                    onDismissRequest = { selectedCharacterForMenu = null },
                    title = { Text(target.name, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            ListItem(
                                headlineContent = { Text("Chatを開く") },
                                leadingContent = { Icon(Icons.Default.Chat, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(onClick = {
                                        selectedCharacterForMenu = null
                                        onSelectCharacter(target)
                                    })
                                    .testTag("char_menu_open_chat")
                            )
                            ListItem(
                                headlineContent = { Text("編集") },
                                leadingContent = { Icon(Icons.Default.Edit, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(onClick = {
                                        selectedCharacterForMenu = null
                                        onEditCharacter(target)
                                    })
                                    .testTag("char_menu_edit")
                            )
                            ListItem(
                                headlineContent = { Text(if (target.isFavorite) "お気に入りを解除" else "お気に入り") },
                                leadingContent = {
                                    Icon(
                                        if (target.isFavorite) Icons.Default.Star else Icons.Default.StarOutline,
                                        null,
                                        tint = if (target.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(onClick = {
                                        selectedCharacterForMenu = null
                                        onToggleFavorite(target)
                                    })
                                    .testTag("char_menu_toggle_favorite")
                            )
                            ListItem(
                                headlineContent = { Text("削除", color = MaterialTheme.colorScheme.error) },
                                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(onClick = {
                                        selectedCharacterForMenu = null
                                        characterToDelete = target
                                    })
                                    .testTag("char_menu_delete")
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedCharacterForMenu = null }) {
                            Text("閉じる")
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterListItem(
    character: Character,
    lastMessage: Message?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("character_item_${character.id}"),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AvatarView(
                    iconUri = character.iconUri,
                    characterName = character.name,
                    size = 52
                )
                if (character.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "お気に入り",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val preview = lastMessage?.displayContent ?: character.firstMessage.ifBlank { "まだメッセージはありません" }
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
    HorizontalDivider(modifier = Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterCardItem(
    character: Character,
    lastMessage: Message?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("character_card_${character.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AvatarView(
                    iconUri = character.iconUri,
                    characterName = character.name,
                    size = 64
                )
                if (character.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "お気に入り",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = character.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(4.dp))

            val preview = lastMessage?.displayContent ?: character.firstMessage.ifBlank { "新規チャット" }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
