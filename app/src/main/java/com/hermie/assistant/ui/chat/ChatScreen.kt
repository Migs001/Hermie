package com.hermie.assistant.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.data.ChatMessage
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotSize
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.ui.mascot.MascotView
import com.hermie.assistant.ui.theme.*

/**
 * Full chat screen — mascot area is prominent (~60% in voice mode),
 * collapses to a small header when user scrolls chat up or in text mode.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isListening: Boolean,
    isVoiceMode: Boolean,
    isDeskCaddyMode: Boolean = false,
    isReplayingContext: Boolean = false,
    partialTranscript: String,
    mascotMood: MascotMood,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit,
    onStopGeneration: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onToggleDeskCaddy: () -> Unit = {},
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Mascot area is expanded by default in voice mode, collapsed in text mode
    // User can drag or scroll to collapse/expand
    var isMascotExpanded by remember(isVoiceMode) { mutableStateOf(isVoiceMode) }

    // Collapse mascot when user scrolls chat
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex > 0 && messages.size > 2) {
            isMascotExpanded = false
        }
    }

    // Auto-expand mascot when switching to voice mode with few messages
    LaunchedEffect(isVoiceMode) {
        if (isVoiceMode) isMascotExpanded = true
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Animated mascot height: ~60% expanded, small strip when collapsed
    val expandedHeight = screenHeight * 0.55f
    val collapsedHeight = 0.dp  // fully hidden when collapsed in text mode
    val voiceCollapsedHeight = 80.dp  // small mascot strip in voice mode when collapsed

    val targetHeight = when {
        isMascotExpanded -> expandedHeight
        isVoiceMode -> voiceCollapsedHeight
        else -> collapsedHeight
    }

    val mascotHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "mascot_height"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .imePadding()
    ) {
        // Top bar
        ChatTopBar(
            isVoiceMode = isVoiceMode,
            isDeskCaddyMode = isDeskCaddyMode,
            onBack = onBack,
            onToggleVoiceMode = onToggleVoiceMode,
            onToggleDeskCaddy = onToggleDeskCaddy,
            onOpenDrawer = onOpenDrawer
        )

        // Mascot hero area (collapsible)
        if (mascotHeight > 0.dp) {
            MascotHeroArea(
                height = mascotHeight,
                isExpanded = isMascotExpanded,
                isListening = isListening,
                isGenerating = isGenerating,
                mascotMood = mascotMood,
                partialTranscript = partialTranscript,
                onMicClick = onMicClick,
                onStopGeneration = onStopGeneration,
                onTapToExpand = { isMascotExpanded = !isMascotExpanded },
                onDragDown = { isMascotExpanded = true },
                onDragUp = { isMascotExpanded = false }
            )
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
            // If mascot is collapsed in voice mode, show a tap hint
            if (!isMascotExpanded && isVoiceMode && messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Swipe down to show Hermie",
                            style = TextStyle(fontSize = 14.sp, color = HermieGrey)
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                val msgIdx = messages.indexOf(message)

                // If this is an assistant message, check if the previous user message
                // had mind debug — render the mind panel above the assistant bubble
                if (message.role == "assistant" && msgIdx > 0) {
                    val prevUser = messages[msgIdx - 1]
                    if (prevUser.role == "user" && prevUser.mindDebug != null) {
                        MindDebugPanel(prevUser.mindDebug)
                    }
                }

                MessageBubble(message)

                // Show thinking dropdown below assistant messages that have thinking content
                if (message.role == "assistant" && message.thinkingContent != null) {
                    ThinkingPanel(message.thinkingContent)
                }

                // If this is the last message, it's a user message with mind debug,
                // and there's no following assistant message yet — show panel below
                if (message.role == "user" && message.mindDebug != null && msgIdx == messages.lastIndex) {
                    MindDebugPanel(message.mindDebug)
                }
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

            // Typing indicator — shown while generating OR while replaying chat context
            if (isGenerating || isReplayingContext) {
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
                // In voice mode, the mic button is in the mascot area.
                // Show a compact bottom bar with status only.
                VoiceBottomBar(
                    isListening = isListening,
                    isGenerating = isGenerating,
                    isDeskCaddyMode = isDeskCaddyMode,
                    onMicClick = onMicClick,
                    onStopGeneration = onStopGeneration,
                    isMascotExpanded = isMascotExpanded
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
                    isGenerating = isGenerating || isReplayingContext,
                    onStopGeneration = onStopGeneration
                )
            }
        }
    }
}

// ── Mascot Hero Area ──────────────────────────────────────────────

@Composable
private fun MascotHeroArea(
    height: Dp,
    isExpanded: Boolean,
    isListening: Boolean,
    isGenerating: Boolean,
    mascotMood: MascotMood,
    partialTranscript: String,
    onMicClick: () -> Unit,
    onStopGeneration: () -> Unit,
    onTapToExpand: () -> Unit,
    onDragDown: () -> Unit,
    onDragUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(HermieOffWhite)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 20) onDragDown()
                    else if (dragAmount < -20) onDragUp()
                }
            }
            .clickable(onClick = onTapToExpand),
        contentAlignment = Alignment.Center
    ) {
        if (isExpanded) {
            // Full expanded mascot with mic button and status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                MascotView(
                    state = MascotState(
                        mood = when {
                            isListening -> MascotMood.LISTENING
                            isGenerating -> MascotMood.TALKING
                            else -> mascotMood
                        }
                    ),
                    size = MascotSize.LARGE
                )

                Spacer(Modifier.height(12.dp))

                // Status text
                Text(
                    text = when {
                        isListening && partialTranscript.isNotBlank() -> partialTranscript
                        isListening -> "Listening..."
                        isGenerating -> "Thinking..."
                        else -> "Tap mic to speak"
                    },
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isListening && partialTranscript.isNotBlank()) HermieForest else HermieGrey
                    ),
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 32.dp)
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
                            else -> Icons.Outlined.Mic
                        },
                        contentDescription = "Mic",
                        tint = HermieCream,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        } else {
            // Collapsed: small mascot in a strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                MascotView(
                    state = MascotState(
                        mood = when {
                            isListening -> MascotMood.LISTENING
                            isGenerating -> MascotMood.TALKING
                            else -> mascotMood
                        }
                    ),
                    size = MascotSize.SMALL
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when {
                        isListening -> "Listening..."
                        isGenerating -> "Thinking..."
                        else -> "Hermie"
                    },
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = HermieGrey
                    )
                )
            }
        }

        // Drag handle at bottom
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HermieGrey.copy(alpha = 0.3f))
            )
        }
    }
}

// ── Top Bar ──────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    isVoiceMode: Boolean,
    isDeskCaddyMode: Boolean,
    onBack: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onToggleDeskCaddy: () -> Unit,
    onOpenDrawer: () -> Unit
) {
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

        // Desk Caddy toggle — only visible in voice mode
        if (isVoiceMode) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDeskCaddyMode) HermieForest.copy(alpha = 0.15f) else HermieOffWhite,
                modifier = Modifier.clickable(onClick = onToggleDeskCaddy)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        Icons.Filled.Desk,
                        contentDescription = "Desk Caddy",
                        tint = if (isDeskCaddyMode) HermieForest else HermieGrey,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Caddy",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDeskCaddyMode) HermieForest else HermieGrey
                        )
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }

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
}

// ── Voice Bottom Bar (compact, shown when mascot is expanded) ──────

@Composable
private fun VoiceBottomBar(
    isListening: Boolean,
    isGenerating: Boolean,
    isDeskCaddyMode: Boolean,
    onMicClick: () -> Unit,
    onStopGeneration: () -> Unit,
    isMascotExpanded: Boolean
) {
    // When mascot is expanded, mic button is already in the hero area — just show status
    // When collapsed, show a prominent mic button here
    Surface(
        color = HermieOffWhite,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        if (isMascotExpanded) {
            // Minimal bar — just status text
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        isDeskCaddyMode && isListening -> "Desk Caddy \u00b7 Always listening..."
                        isDeskCaddyMode -> "Desk Caddy \u00b7 Ready"
                        isListening -> "Listening..."
                        isGenerating -> "Generating response..."
                        else -> "Tap the mic above to speak"
                    },
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = if (isDeskCaddyMode) HermieForest else HermieGrey
                    )
                )
            }
        } else {
            // Collapsed mascot — put mic button here
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
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
                            else -> Icons.Outlined.Mic
                        },
                        contentDescription = "Mic",
                        tint = HermieCream,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ── Message Bubble ──────────────────────────────────────────────

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
                modifier = Modifier.padding(end = 8.dp, top = 4.dp)
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
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Image attachment indicator
                if (message.imageUri != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (message.content != "[Image]") 6.dp else 0.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = "Image",
                            tint = if (isUser) HermieCream.copy(alpha = 0.7f) else HermieGrey,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Photo attached",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = if (isUser) HermieCream.copy(alpha = 0.7f) else HermieGrey
                            )
                        )
                    }
                }

                // Strip emotion tags from display
                val displayText = message.content
                    .replace(Regex("<emotion>.*?</emotion>\\s*"), "")
                    .replace(Regex("<tool>.*?</tool>"), "")
                    .trim()

                if (displayText.isNotEmpty() && displayText != "[Image]") {
                    Text(
                        text = displayText,
                        style = TextStyle(
                            fontSize = 15.sp,
                            color = if (isUser) HermieCream else HermieForest,
                            lineHeight = 22.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * Mind debug panel — renders between user message and assistant response.
 * Small "mind" chip that expands to show SLM classification, retrieved memory
 * with graph-walked nodes indented under their anchor matches.
 */
@Composable
private fun MindDebugPanel(debugText: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Toggle chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(HermieTan.copy(alpha = 0.12f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = HermieForest.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "mind",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieForest.copy(alpha = 0.5f)
                )
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = HermieForest.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }

        // Expandable panel
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieTan.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = debugText,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = HermieForest.copy(alpha = 0.65f),
                        lineHeight = 15.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

/**
 * Thinking panel — shows below assistant messages when the model generated
 * <think> content. Collapsible dropdown similar to MindDebugPanel.
 * Lets the user see the model's reasoning instead of hiding it.
 */
@Composable
private fun ThinkingPanel(thinkingText: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Toggle chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(HermieGrey.copy(alpha = 0.15f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = HermieForest.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "thinking",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieForest.copy(alpha = 0.5f)
                )
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = HermieForest.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }

        // Expandable panel
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieGrey.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = thinkingText,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = HermieForest.copy(alpha = 0.6f),
                        lineHeight = 15.sp
                    ),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

// ── Text Input Bar ──────────────────────────────────────────────

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Mic shortcut
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.Mic, "Mic", tint = HermieGrey, modifier = Modifier.size(22.dp))
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
}

// ── Typing Indicator ──────────────────────────────────────────────

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
