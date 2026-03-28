package com.hermie.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.llm.ModelInfo
import com.hermie.assistant.llm.ModelManager
import com.hermie.assistant.llm.ModelType
import com.hermie.assistant.ui.theme.*

@Composable
fun SettingsScreen(
    settings: HermieSettings,
    modelManager: ModelManager,
    onBack: () -> Unit,
    onDownloadModel: (ModelInfo) -> Unit,
    onSwitchModel: (ModelInfo) -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onClearChat: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    isBackgroundRunning: Boolean,
    onDownloadTtsModel: () -> Unit = {},
    isTtsReady: Boolean = false,
    isTtsDownloading: Boolean = false
) {
    val activeModel by modelManager.activeModel.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
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
            Text(
                text = "Settings",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── User section ──
            SettingsSection("USER") {
                SettingsRow(
                    icon = Icons.Outlined.Person,
                    title = settings.userName.ifBlank { "Set name" },
                    subtitle = "Name"
                )
            }

            // ── Model section ──
            SettingsSection("BRAIN") {
                SettingsRow(
                    icon = Icons.Outlined.Memory,
                    title = activeModel?.displayName ?: "No model loaded",
                    subtitle = "Active model"
                )

                ModelManager.BASE_BRAIN_MODELS.forEach { model ->
                    val isDownloaded = modelManager.isDownloaded(model)
                    val isActive = activeModel?.id == model.id
                    SettingsRow(
                        icon = if (isActive) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        title = model.displayName,
                        subtitle = "${model.paramCount} • ${model.sizeMb}MB" +
                            if (isDownloaded) " • Downloaded" else "",
                        onClick = {
                            if (isDownloaded) onSwitchModel(model)
                            else onDownloadModel(model)
                        },
                        tintColor = if (isActive) HermieTerra else HermieGrey
                    )
                }
            }

            // ── Voice section ──
            SettingsSection("VOICE") {
                // TTS model download
                SettingsRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = if (isTtsReady) "Voice model ready" else if (isTtsDownloading) "Downloading..." else "Download voice model",
                    subtitle = if (isTtsReady) "Piper English • ~75 MB" else "Piper English TTS • ~75 MB",
                    onClick = if (!isTtsReady && !isTtsDownloading) onDownloadTtsModel else null,
                    tintColor = if (isTtsReady) HermieTerra else HermieForest
                )

                var voiceEnabled by remember { mutableStateOf(settings.voiceEnabled) }
                SettingsToggle(
                    icon = Icons.Outlined.VolumeUp,
                    title = "Read responses aloud",
                    subtitle = if (isTtsReady) "Hermie speaks responses" else "Download voice model first",
                    checked = voiceEnabled && isTtsReady,
                    onCheckedChange = {
                        if (isTtsReady) {
                            voiceEnabled = it
                            settings.voiceEnabled = it
                        }
                    }
                )

                var wakeWord by remember { mutableStateOf(settings.wakeWordEnabled) }
                SettingsToggle(
                    icon = Icons.Outlined.Hearing,
                    title = "Wake word",
                    subtitle = "Say \"Hey Hermie\" to activate",
                    checked = wakeWord,
                    onCheckedChange = {
                        wakeWord = it
                        settings.wakeWordEnabled = it
                    }
                )
            }

            // ── Background section ──
            SettingsSection("BACKGROUND") {
                SettingsToggle(
                    icon = Icons.Outlined.PlayCircle,
                    title = "Background service",
                    subtitle = "Keep modules running when app is closed",
                    checked = isBackgroundRunning,
                    onCheckedChange = onToggleBackground
                )
            }

            // ── Modules section ──
            SettingsSection("MODULES") {
                var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled) }
                SettingsToggle(
                    icon = Icons.Outlined.PictureInPicture,
                    title = "Overlay bubbles",
                    subtitle = "Show Hermie over other apps",
                    checked = overlayEnabled,
                    onCheckedChange = {
                        overlayEnabled = it
                        settings.overlayEnabled = it
                    }
                )
            }

            // ── Danger zone ──
            SettingsSection("DATA") {
                SettingsRow(
                    icon = Icons.Outlined.DeleteForever,
                    title = "Clear all chats",
                    subtitle = "This cannot be undone",
                    onClick = onClearChat,
                    tintColor = HermieError
                )
            }

            // Version
            Text(
                text = "Hermie v1.0.0",
                style = TextStyle(fontSize = 12.sp, color = HermieGrey),
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = HermieGrey,
                letterSpacing = 1.5.sp
            ),
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermieOffWhite
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tintColor: androidx.compose.ui.graphics.Color = HermieForest
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = tintColor, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HermieForest)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(fontSize = 12.sp, color = HermieGrey)
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, null, tint = HermieForest, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HermieForest)
            )
            Text(
                text = subtitle,
                style = TextStyle(fontSize = 12.sp, color = HermieGrey)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HermieCream,
                checkedTrackColor = HermieForest,
                uncheckedThumbColor = HermieGrey,
                uncheckedTrackColor = HermieTan.copy(alpha = 0.3f)
            )
        )
    }
}
