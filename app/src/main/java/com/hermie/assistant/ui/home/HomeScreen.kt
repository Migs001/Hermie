package com.hermie.assistant.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotSize
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.ui.mascot.MascotView
import com.hermie.assistant.ui.theme.*

/**
 * Main home screen — inspired by reference image 3 (BRIK dashboard).
 * Shows mascot greeting, quick actions, and module cards.
 *
 * Sleep mode: the orange banner expands to cover most of the screen,
 * hiding Chat/Voice/Tasks and Memory module. A scrollable progress log
 * shows consolidation activity. Only DND and Screen Time remain visible.
 */
@Composable
fun HomeScreen(
    userName: String,
    mascotState: MascotState,
    activeTaskCount: Int,
    onChatClick: () -> Unit,
    onVoiceChatClick: () -> Unit,
    onTasksClick: () -> Unit,
    onModuleClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    moduleCards: List<ModuleCardData>,
    isSleepMode: Boolean = false,
    sleepProgress: String = "",
    sleepLog: List<String> = emptyList(),
    onSleepClick: () -> Unit = {},
    isStudyMode: Boolean = false,
    studyProgress: String = "",
    studyLog: List<String> = emptyList(),
    onStopStudy: () -> Unit = {},
    onMemoryClick: () -> Unit = {}
) {
    // Three-way banner color: green (awake), orange (sleep), orange (study)
    val bannerColor by animateColorAsState(
        targetValue = when {
            isSleepMode -> HermieTerra
            isStudyMode -> HermieTerra
            else -> HermieForest
        },
        animationSpec = tween(600, easing = EaseInOut),
        label = "bannerColor"
    )

    // Banner expands in either sleep or study mode
    val isBannerExpanded = isSleepMode || isStudyMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hermie",
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                )
            )
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = HermieForest
                )
            }
        }

        // ── Banner card ──
        // weight(1f) when expanded fills the screen; no animateContentSize (they fight).
        // The log sits between greeting and button, using weight(1f) inside the Column
        // to fill the banner interior. Button stays anchored at the bottom.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = bannerColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(if (isBannerExpanded) Modifier.weight(1f) else Modifier)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // Greeting / status + mascot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Crossfade(
                            targetState = BannerMode(isSleepMode, isStudyMode),
                            animationSpec = tween(400),
                            label = "greeting_text"
                        ) { mode ->
                            when {
                                mode.sleep -> Column {
                                    Text(
                                        text = "Zzz...",
                                        style = TextStyle(
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermieCream
                                        )
                                    )
                                    Text(
                                        text = sleepProgress.ifBlank { "Consolidating memories" },
                                        style = TextStyle(
                                            fontSize = 13.sp,
                                            color = HermieCream.copy(alpha = 0.7f)
                                        )
                                    )
                                }
                                mode.study -> Column {
                                    Text(
                                        text = "Studying...",
                                        style = TextStyle(
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermieCream
                                        )
                                    )
                                    Text(
                                        text = studyProgress.ifBlank { "Extracting knowledge" },
                                        style = TextStyle(
                                            fontSize = 13.sp,
                                            color = HermieCream.copy(alpha = 0.7f)
                                        )
                                    )
                                }
                                else -> Column {
                                    Text(
                                        text = "$userName,",
                                        style = TextStyle(
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermieCream
                                        )
                                    )
                                    Text(
                                        text = getGreeting(),
                                        style = TextStyle(
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = HermieCream.copy(alpha = 0.8f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    MascotView(
                        state = mascotState,
                        size = MascotSize.MEDIUM
                    )
                }

                // Memory button — visible only in normal mode
                AnimatedVisibility(
                    visible = !isBannerExpanded,
                    enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = HermieCream.copy(alpha = 0.15f),
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(onClick = onMemoryClick)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    contentDescription = "Memory",
                                    tint = HermieCream,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Progress log — fills remaining banner space when expanded
                if (isBannerExpanded) {
                    val activeLog = when {
                        isSleepMode -> sleepLog
                        isStudyMode -> studyLog
                        else -> emptyList()
                    }
                    if (activeLog.isNotEmpty()) {
                        val listState = rememberLazyListState()
                        LaunchedEffect(activeLog.size) {
                            if (activeLog.isNotEmpty()) {
                                listState.animateScrollToItem(activeLog.size - 1)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            items(activeLog) { line ->
                                Text(
                                    text = line,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (line.startsWith("---")) FontWeight.Bold else FontWeight.Normal,
                                        color = if (line.startsWith("  +")) HermieCream
                                               else if (line.startsWith("  ~")) HermieCream.copy(alpha = 0.9f)
                                               else if (line.startsWith("  ->")) HermieCream.copy(alpha = 0.8f)
                                               else if (line.startsWith("Error")) Color(0xFFFFCDD2)
                                               else HermieCream.copy(alpha = 0.85f)
                                    ),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    } else {
                        // Placeholder while log is empty — still fills space
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Starting...",
                                style = TextStyle(fontSize = 14.sp, color = HermieCream.copy(alpha = 0.5f))
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Action button — at bottom of banner, anchored by weight above
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HermieCream.copy(alpha = 0.15f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = when {
                            isStudyMode -> onStopStudy
                            else -> onSleepClick
                        })
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Crossfade(
                            targetState = BannerMode(isSleepMode, isStudyMode),
                            animationSpec = tween(300),
                            label = "action_button"
                        ) { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val (icon, label) = when {
                                    mode.study -> Icons.Outlined.Stop to "Stop Studying"
                                    mode.sleep -> Icons.Outlined.WbSunny to "Wake Hermie"
                                    else -> Icons.Outlined.Bedtime to "Goodnight, Hermie"
                                }
                                Icon(
                                    icon,
                                    contentDescription = label,
                                    tint = HermieCream,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = HermieCream
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Normal content — quick actions + modules ──
        AnimatedVisibility(
            visible = !isBannerExpanded,
            enter = fadeIn(tween(350, delayMillis = 150)),
            exit = fadeOut(tween(250))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionCard(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        label = "Chat",
                        onClick = onChatClick,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.Mic,
                        label = "Voice",
                        onClick = onVoiceChatClick,
                        modifier = Modifier.weight(1f),
                        accentColor = HermieTerra
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.Task,
                        label = "Tasks",
                        badge = if (activeTaskCount > 0) activeTaskCount.toString() else null,
                        onClick = onTasksClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (moduleCards.isNotEmpty()) {
                    Text(
                        text = "MODULES",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieGrey,
                            letterSpacing = 1.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        moduleCards.forEach { card ->
                            ModuleCard(
                                data = card,
                                onClick = { onModuleClick(card.moduleId) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // ── Sleep/study-visible modules (DND + Screen Time) ──
        AnimatedVisibility(
            visible = isBannerExpanded,
            enter = slideInVertically(
                animationSpec = tween(400, delayMillis = 150, easing = EaseInOut),
                initialOffsetY = { it }
            ) + fadeIn(tween(300, delayMillis = 200)),
            exit = slideOutVertically(
                animationSpec = tween(300, easing = EaseInOut),
                targetOffsetY = { it }
            ) + fadeOut(tween(200))
        ) {
            val sleepVisibleModules = moduleCards.filter { card ->
                card.moduleId in setOf("smart_dnd", "screentime")
            }
            if (sleepVisibleModules.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sleepVisibleModules.forEach { card ->
                        ModuleCard(
                            data = card,
                            onClick = { onModuleClick(card.moduleId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    accentColor: Color = HermieForest
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = HermieOffWhite,
        modifier = modifier
            .height(100.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .offset(x = 20.dp, y = (-4).dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(HermieTerra),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badge,
                            style = TextStyle(fontSize = 10.sp, color = HermieCream, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HermieForest
                )
            )
        }
    }
}

@Composable
private fun ModuleCard(
    data: ModuleCardData,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HermieTan.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    data.icon,
                    contentDescription = null,
                    tint = HermieForest,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.title,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HermieForest
                    )
                )
                if (data.subtitle != null) {
                    Text(
                        text = data.subtitle,
                        style = TextStyle(fontSize = 13.sp, color = HermieGrey)
                    )
                }
            }
            if (data.isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(HermieTerra)
                )
            }
        }
    }
}

data class ModuleCardData(
    val moduleId: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val isActive: Boolean = false
)

private data class BannerMode(val sleep: Boolean, val study: Boolean)

private fun getGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "you're up late"
        hour < 12 -> "good morning"
        hour < 17 -> "good afternoon"
        hour < 21 -> "good evening"
        else -> "good night"
    }
}
