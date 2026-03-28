package com.hermie.assistant.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.llm.ModelManager
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieOptionCard
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ModelDownloadScreen(
    modelManager: ModelManager,
    settings: HermieSettings,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val downloadState by modelManager.downloadState.collectAsState()
    var selectedTier by remember { mutableStateOf(settings.selectedModelTier) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadComplete by remember { mutableStateOf(modelManager.isModelDownloaded) }

    val tiers = listOf(
        ModelTier("small", "Small", "~400 MB", "Qwen 2.5 0.5B — Tiny & fast", "qwen2.5-0.5b"),
        ModelTier("medium", "Medium", "~1 GB", "Qwen 2.5 1.5B — Balanced", "qwen2.5-1b"),
        ModelTier("large", "Large", "~2 GB", "Qwen 2.5 3B — Most capable", "qwen2.5-3b")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = HermieForest,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack)
        )

        Spacer(Modifier.height(24.dp))

        HermieSectionLabel("BRAIN DOWNLOAD")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Choose Hermie's\nbrain size",
            style = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Bigger brains are smarter but take more space.\nYou can change this later in Settings.",
            style = TextStyle(fontSize = 15.sp, color = HermieGrey, lineHeight = 22.sp)
        )

        Spacer(Modifier.height(32.dp))

        tiers.forEach { tier ->
            val isDownloaded = ModelManager.BASE_BRAIN_MODELS
                .firstOrNull { it.id == tier.modelId }
                ?.let { modelManager.isDownloaded(it) } ?: false

            HermieOptionCard(
                text = "${tier.label}  •  ${tier.size}",
                subtitle = tier.description + if (isDownloaded) "  ✓ Downloaded" else "",
                selected = selectedTier == tier.id,
                onClick = {
                    if (!isDownloading) {
                        selectedTier = tier.id
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        // Download progress
        if (isDownloading) {
            Spacer(Modifier.height(16.dp))
            when (val state = downloadState) {
                is ModelManager.DownloadState.Downloading -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Downloading...",
                                style = TextStyle(fontSize = 14.sp, color = HermieForest)
                            )
                            Text(
                                "${(state.progress * 100).toInt()}%",
                                style = TextStyle(fontSize = 14.sp, color = HermieTerra)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = HermieTerra,
                            trackColor = HermieTan.copy(alpha = 0.3f)
                        )
                    }
                }
                is ModelManager.DownloadState.Complete -> {
                    downloadComplete = true
                    isDownloading = false
                }
                is ModelManager.DownloadState.Failed -> {
                    Text(
                        "Download failed: ${state.error}",
                        style = TextStyle(fontSize = 14.sp, color = HermieError)
                    )
                    isDownloading = false
                }
                else -> {}
            }
        }

        Spacer(Modifier.weight(1f))

        if (downloadComplete) {
            HermieButton(
                text = "Let's go!",
                onClick = onComplete,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        } else {
            HermieButton(
                text = if (isDownloading) "Downloading..." else "Download & Continue",
                onClick = {
                    if (!isDownloading) {
                        settings.selectedModelTier = selectedTier
                        val model = ModelManager.BASE_BRAIN_MODELS
                            .firstOrNull { it.id == tiers.first { t -> t.id == selectedTier }.modelId }
                        if (model != null) {
                            if (modelManager.isDownloaded(model)) {
                                modelManager.setActiveModel(model)
                                downloadComplete = true
                            } else {
                                isDownloading = true
                                scope.launch {
                                    val success = modelManager.downloadModel(model)
                                    if (success) downloadComplete = true
                                    isDownloading = false
                                }
                            }
                        }
                    }
                },
                enabled = !isDownloading,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}

private data class ModelTier(
    val id: String,
    val label: String,
    val size: String,
    val description: String,
    val modelId: String
)
