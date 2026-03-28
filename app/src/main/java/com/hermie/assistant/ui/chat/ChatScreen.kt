package com.hermie.assistant.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.data.ChatMessage
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotSize
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.ui.mascot.MascotView
import com.hermie.assistant.ui.theme.*

/**
 * Full chat screen — inspired by reference image 4.
 * Seamlessly transitions between text and voice modes.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isListening: Boolean,
    isVoiceMode: Boolean,
    partialTranscript: String,
    mascotMood: MascotMood,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit,
    onStopGeneration: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .imePadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HermieForest)
            }

            Spacer(Modifier.weight(1f))

            // Voice mode toggle
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isVoiceMode) HermieTerra.copy(alpha = 0.15f) else HermieOffWhite,
                modifier = Modifier.clickable(onClick = onToggleVoiceMode)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (isVoiceMode) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                        contentDescription = "Voice mode",
                        tint = if (isVoiceMode) HermieTerra else HermieGrey,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isVoiceMode) "Voice" else "Text",
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isVoiceMode) HermieTerra else HermieGrey
                        )
                    )
                }
            }

            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, "Conversations", tint = HermieForest)
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }

            // Listening indicator
            if (isListening && partialTranscript.isNotBlank()) {
                item {
                    MessageBubble(
                        ChatMessage(
                            id = "partial",
                            role = "user",
                            content = "$partialTranscript..."
                        )
                    )
                }
            }

            // Typing indicator
            if (isGenerating) {
                item {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MascotView(
                            state = MascotState(MascotMood.THINKING),
                            size = MascotSize.SMALL
                        )
                        TypingIndicator()
                    }
                }
            }
        }

        // Input area
        AnimatedContent(
            targetState = isVoiceMode,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "input_mode"
        ) { voiceMode ->
            if (voiceMode) {
                VoiceInputBar(
                    isListening = isListening,
                    isGenerating = isGenerating,
                    onMicClick = onMicClick,
                    onStopGeneration = onStopGeneration,
                    mascotMood = mascotMood
                )
            } else {
                TextInputBar(
                    text = inputText,
                    onTextChanged = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    onMicClick = onMicClick,
                    isGenerating = isGenerating,
                    onStopGeneration = onStopGeneration
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            MascotView(
                state = MascotState(
                    mood = when (message.emotion) {
                        "happy" -> MascotMood.HAPPY
                        "excited" -> MascotMood.EXCITED
                        "concerned" -> MascotMood.CONCERNED
                        "surprised" -> MascotMood.SURPRISED
                        "goofy" -> MascotMood.HAPPY
                        else -> MascotMood.IDLE
                    }
                ),
                size = MascotSize.SMALL,
                modifier = Modifier
                    .padding(end = 8.dp, top = 4.dp)
                    .align(Alignment.Top)
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) HermieForest else HermieOffWhite,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            // Strip emotion tags from display
            val displayText = message.content
                .replace(Regex("<emotion>.*?</emotion>\\s*"), "")
                .replace(Regex("<tool>.*?</tool>"), "")
                .trim()

            Text(
                text = displayText,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = if (isUser) HermieCream else HermieForest,
                    lineHeight = 22.sp
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun TextInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    isGenerating: Boolean,
    onStopGeneration: () -> Unit
) {
    Surface(
        color = HermieOffWhite,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mic shortcut
            IconButton(
                onClick = onMicClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Outlined.Mic, "Mic", tint = HermieGrey)
            }

            // Text field
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                textStyle = TextStyle(fontSize = 16.sp, color = HermieForest),
                cursorBrush = SolidColor(HermieForest),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .background(HermieCream, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                "Message Hermie...",
                                style = TextStyle(fontSize = 16.sp, color = HermieGrey.copy(alpha = 0.5f))
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Send / Stop button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGenerating) HermieError
                        else if (text.isNotBlank()) HermieForest
                        else HermieGrey.copy(alpha = 0.3f)
                    )
                    .clickable {
                        if (isGenerating) onStopGeneration()
                        else onSend()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isGenerating) Icons.Outlined.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = HermieCream,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceInputBar(
    isListening: Boolean,
    isGenerating: Boolean,
    onMicClick: () -> Unit,
    onStopGeneration: () -> Unit,
    mascotMood: MascotMood
) {
    Surface(
        color = HermieOffWhite,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MascotView(
                state = MascotState(
                    mood = when {
                        isListening -> MascotMood.LISTENING
                        isGenerating -> MascotMood.TALKING
                        else -> mascotMood
                    }
                ),
                size = MascotSize.MEDIUM
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = when {
                    isListening -> "Listening..."
                    isGenerating -> "Thinking..."
                    else -> "Tap to speak"
                },
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieGrey
                )
            )

            Spacer(Modifier.height(16.dp))

            // Big mic button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isGenerating -> HermieError
                            isListening -> HermieTerra
                            else -> HermieForest
                        }
                    )
                    .clickable {
                        if (isGenerating) onStopGeneration()
                        else onMicClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when {
                        isGenerating -> Icons.Outlined.Stop
                        isListening -> Icons.Outlined.Mic
                        else -> Icons.Outlined.Mic
                    },
                    contentDescription = "Mic",
                    tint = HermieCream,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val dot1 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(0)),
        label = "d1"
    )
    val dot2 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(200)),
        label = "d2"
    )
    val dot3 by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = StartOffset(400)),
        label = "d3"
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(dot1, dot2, dot3).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HermieGrey.copy(alpha = 0.3f + alpha * 0.7f))
            )
        }
    }
}
