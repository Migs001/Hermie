package com.hermie.assistant.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    moduleCards: List<ModuleCardData>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
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

        // Greeting card with mascot
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = HermieForest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$userName,",
                        style = TextStyle(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = HermieCream
                        )
                    )
                    Text(
                        text = getGreeting(),
                        style = TextStyle(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Normal,
                            color = HermieCream.copy(alpha = 0.8f)
                        )
                    )
                }
                MascotView(
                    state = mascotState,
                    size = MascotSize.MEDIUM
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Quick actions row
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

        Spacer(Modifier.height(24.dp))

        // Modules section
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

            // Module cards grid (inspired by reference image 2)
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

        Spacer(Modifier.height(32.dp))
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
