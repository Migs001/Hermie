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
    val brainDownloadState by modelManager.downloadState.collectAsState()

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
                    fontFamily = HermieSerif,
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

                // Show download progress if active
                val downloading = brainDownloadState
                if (downloading is ModelManager.DownloadState.Downloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { downloading.progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = HermieTerra,
                            trackColor = HermieTan.copy(alpha = 0.3f)
                        )
                        Text(
                            "${(downloading.progress * 100).toInt()}%",
                            style = TextStyle(fontSize = 12.sp, color = HermieTerra)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = HermieTan.copy(alpha = 0.3f)
                )

                ModelManager.BASE_BRAIN_MODELS.forEach { model ->
                    val isDownloaded = modelManager.isDownloaded(model)
                    val isActive = activeModel?.id == model.id
                    val isCurrentlyDownloading = downloading is ModelManager.DownloadState.Downloading

                    SettingsRow(
                        icon = when {
                            isActive -> Icons.Filled.CheckCircle
                            isDownloaded -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.Download
                        },
                        title = model.displayName,
                        subtitle = buildString {
                            append("${model.paramCount} • ${model.sizeMb}MB")
                            when {
                                isActive -> append(" • Active")
                                isDownloaded -> append(" • Downloaded — tap to switch")
                                else -> append(" • Tap to download")
                            }
                        },
                        onClick = when {
                            isActive -> null  // Already active, no action needed
                            isDownloaded -> {{ onSwitchModel(model) }}
                            isCurrentlyDownloading -> null  // Already downloading something
                            else -> {{ onDownloadModel(model) }}
                        },
                        tintColor = when {
                            isActive -> HermieTerra
                            isDownloaded -> HermieForest
                            else -> HermieGrey
                        },
                        trailingAction = if (isDownloaded && !isActive) {
                            {
                                IconButton(onClick = { onDeleteModel(model) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete model",
                                        tint = HermieGrey,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            // ── Voice section ──
            SettingsSection("VOICE") {
                // TTS model download
                val voiceModel = ModelManager.VOICE_MODELS.firstOrNull()
                val isVoiceDownloaded = voiceModel != null && modelManager.isDownloaded(voiceModel)
                SettingsRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = if (isTtsReady) "Voice model ready" else if (isTtsDownloading) "Downloading..." else "Download voice model",
                    subtitle = if (isTtsReady) "Piper English • ~75 MB" else "Piper English TTS • ~75 MB",
                    onClick = if (!isTtsReady && !isTtsDownloading) onDownloadTtsModel else null,
                    tintColor = if (isTtsReady) HermieTerra else HermieForest,
                    trailingAction = if (isVoiceDownloaded) {
                        {
                            IconButton(onClick = { if (voiceModel != null) onDeleteModel(voiceModel) }) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = HermieGrey, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else null
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

            // ── Mind (SLM + Embeddings) section ──
            SettingsSection("MIND") {
                // SLM classifier
                val slmModel = ModelManager.SLM_MODELS.firstOrNull()
                val isSlmDownloaded = slmModel != null && modelManager.isDownloaded(slmModel)
                val slmDownloadState by modelManager.downloadStateFor(ModelType.SLM).collectAsState()
                val isSlmDownloading = slmDownloadState is ModelManager.DownloadState.Downloading

                SettingsRow(
                    icon = Icons.Outlined.Psychology,
                    title = when {
                        isSlmDownloaded -> "Mind model ready"
                        isSlmDownloading -> "Downloading mind model..."
                        else -> "Download mind model"
                    },
                    subtitle = when {
                        isSlmDownloaded -> "Qwen3 0.6B • Drip atomizer"
                        isSlmDownloading -> {
                            val progress = (slmDownloadState as? ModelManager.DownloadState.Downloading)?.progress ?: 0f
                            "Qwen3 0.6B • ${(progress * 100).toInt()}%"
                        }
                        else -> "Qwen3 0.6B • ~400 MB"
                    },
                    onClick = if (!isSlmDownloaded && !isSlmDownloading && slmModel != null) {
                        { onDownloadModel(slmModel) }
                    } else null,
                    tintColor = if (isSlmDownloaded) HermieTerra else HermieForest,
                    trailingAction = if (isSlmDownloaded && slmModel != null) {
                        {
                            IconButton(onClick = { onDeleteModel(slmModel) }) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = HermieGrey, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else null
                )

                // Embedding model
                val embModel = ModelManager.MIND_MODELS.firstOrNull()
                val isEmbDownloaded = embModel != null && modelManager.isDownloaded(embModel)
                val embDownloadState by modelManager.downloadStateFor(ModelType.MIND).collectAsState()
                val isEmbDownloading = embDownloadState is ModelManager.DownloadState.Downloading

                SettingsRow(
                    icon = Icons.Outlined.Memory,
                    title = when {
                        isEmbDownloaded -> "Embedding model ready"
                        isEmbDownloading -> "Downloading embeddings..."
                        else -> "Download embedding model"
                    },
                    subtitle = when {
                        isEmbDownloaded -> "MiniLM-L6-v2 • Memory retrieval"
                        isEmbDownloading -> {
                            val progress = (embDownloadState as? ModelManager.DownloadState.Downloading)?.progress ?: 0f
                            "MiniLM-L6-v2 • ${(progress * 100).toInt()}%"
                        }
                        else -> "MiniLM-L6-v2 • ~23 MB"
                    },
                    onClick = if (!isEmbDownloaded && !isEmbDownloading && embModel != null) {
                        { onDownloadModel(embModel) }
                    } else null,
                    tintColor = if (isEmbDownloaded) HermieTerra else HermieForest,
                    trailingAction = if (isEmbDownloaded && embModel != null) {
                        {
                            IconButton(onClick = { onDeleteModel(embModel) }) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = HermieGrey, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else null
                )
            }

            // ── Vision section ──
            SettingsSection("VISION") {
                val visionDownloadState by modelManager.downloadStateFor(ModelType.VISION).collectAsState()
                val isVisionDownloading = visionDownloadState is ModelManager.DownloadState.Downloading

                if (isVisionDownloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { (visionDownloadState as? ModelManager.DownloadState.Downloading)?.progress ?: 0f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = HermieTerra,
                            trackColor = HermieTan.copy(alpha = 0.3f)
                        )
                        Text(
                            "${((visionDownloadState as? ModelManager.DownloadState.Downloading)?.progress?.times(100))?.toInt() ?: 0}%",
                            style = TextStyle(fontSize = 12.sp, color = HermieTerra)
                        )
                    }
                }

                ModelManager.VISION_MODELS.forEach { model ->
                    val isDownloaded = modelManager.isDownloaded(model)

                    SettingsRow(
                        icon = when {
                            isDownloaded -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.Download
                        },
                        title = model.displayName,
                        subtitle = buildString {
                            append("${model.paramCount} • ${model.sizeMb}MB")
                            when {
                                isDownloaded -> append(" • Downloaded (loaded during sleep)")
                                else -> append(" • Wardrobe clothing categorization")
                            }
                        },
                        onClick = when {
                            isDownloaded -> {{ onDeleteModel(model) }}
                            isVisionDownloading -> null
                            else -> {{ onDownloadModel(model) }}
                        },
                        tintColor = when {
                            isDownloaded -> HermieForest
                            else -> HermieGrey
                        }
                    )
                }
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
                    title = "Notification bubbles",
                    subtitle = "Screen time warnings pop up as bubbles",
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
                fontFamily = HermieSerif,
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
    tintColor: androidx.compose.ui.graphics.Color = HermieForest,
    trailingAction: (@Composable () -> Unit)? = null
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
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieForest
                )
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 12.sp,
                        color = HermieGrey
                    )
                )
            }
        }
        if (trailingAction != null) {
            trailingAction()
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
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieForest
                )
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 12.sp,
                    color = HermieGrey
                )
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
