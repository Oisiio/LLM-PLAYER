package com.example.ui.talk.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Character
import com.example.data.model.Message
import com.example.data.model.MessageRole
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    character: Character?,
    isStreamingThis: Boolean,
    streamingText: String,
    anyStreaming: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onEditUser: (Message) -> Unit,
    onCandidateChange: (Message, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        if (isUser) {
            // User message bubble (Right aligned, no avatar, no name)
            Box {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                        .combinedClickable(
                            enabled = !anyStreaming,
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                        .testTag("user_message_${message.id}")
                ) {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu && !anyStreaming,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("編集") },
                        onClick = {
                            showMenu = false
                            onEditUser(message)
                        },
                        modifier = Modifier.testTag("user_menu_edit")
                    )
                }
            }
        } else {
            // Character message bubble (Left aligned, with avatar and name)
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar
                AvatarView(
                    iconUri = character?.iconUri,
                    characterName = character?.name ?: "Character",
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp)
                )

                Column {
                    // Character Name
                    Text(
                        text = character?.name ?: "Character",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
                    )

                    Box {
                        Surface(
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                                .combinedClickable(
                                    enabled = !anyStreaming && !isStreamingThis,
                                    onClick = {},
                                    onLongClick = { showMenu = true }
                                )
                                .testTag("character_message_${message.id}")
                        ) {
                            val textToDisplay = if (isStreamingThis) {
                                "$streamingText▌"
                            } else {
                                message.displayContent
                            }

                            Text(
                                text = textToDisplay,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu && !anyStreaming && !isStreamingThis,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("コピー") },
                                onClick = {
                                    showMenu = false
                                    onCopy(message.displayContent)
                                },
                                modifier = Modifier.testTag("char_menu_copy")
                            )
                            DropdownMenuItem(
                                text = { Text("再生成") },
                                onClick = {
                                    showMenu = false
                                    onRegenerate(message)
                                },
                                modifier = Modifier.testTag("char_menu_regenerate")
                            )
                            DropdownMenuItem(
                                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete(message)
                                },
                                modifier = Modifier.testTag("char_menu_delete")
                            )
                        }
                    }

                    // Candidate navigation (‹ 1 / 3 ›) when multiple candidates exist and not streaming
                    if (!isStreamingThis && message.candidates.size > 1) {
                        val total = message.candidates.size
                        val current = message.selectedCandidateIndex + 1
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (message.selectedCandidateIndex > 0) {
                                        onCandidateChange(message, message.selectedCandidateIndex - 1)
                                    }
                                },
                                enabled = message.selectedCandidateIndex > 0 && !anyStreaming,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "前の回答",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "$current / $total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = {
                                    if (message.selectedCandidateIndex < total - 1) {
                                        onCandidateChange(message, message.selectedCandidateIndex + 1)
                                    }
                                },
                                enabled = message.selectedCandidateIndex < total - 1 && !anyStreaming,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "次の回答",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarView(
    iconUri: String?,
    characterName: String,
    modifier: Modifier = Modifier,
    size: Int = 36
) {
    val bitmap = remember(iconUri) {
        if (!iconUri.isNullOrBlank()) {
            val f = File(iconUri)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.absolutePath)
            } else null
        } else null
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = characterName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val initial = characterName.firstOrNull()?.toString() ?: "?"
            Text(
                text = initial,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size * 0.45).sp
            )
        }
    }
}
