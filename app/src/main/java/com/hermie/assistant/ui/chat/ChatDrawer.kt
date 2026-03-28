package com.hermie.assistant.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.data.Conversation
import com.hermie.assistant.ui.theme.*

@Composable
fun ChatDrawer(
    isOpen: Boolean,
    conversations: List<Conversation>,
    currentConversationId: String?,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300)),
        exit = slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(200))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Drawer panel
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp),
                color = HermieSurface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Conversations",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = HermieForest
                            )
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "Close", tint = HermieGrey)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // New chat button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = HermieForest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNewChat)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Add, "New", tint = HermieCream, modifier = Modifier.size(20.dp))
                            Text(
                                "New Chat",
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HermieCream)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Conversations list
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(conversations.sortedByDescending { it.updatedAt }) { conv ->
                            val isCurrent = conv.id == currentConversationId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) HermieOffWhite else HermieSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectChat(conv.id)
                                        onClose()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conv.title,
                                            style = TextStyle(
                                                fontSize = 14.sp,
                                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                color = HermieForest
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${conv.messages.size} messages",
                                            style = TextStyle(fontSize = 12.sp, color = HermieGrey)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteChat(conv.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete, "Delete",
                                            tint = HermieGrey.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Scrim (tap to close)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HermieForest.copy(alpha = 0.3f))
                    .clickable(onClick = onClose)
            )
        }
    }
}
