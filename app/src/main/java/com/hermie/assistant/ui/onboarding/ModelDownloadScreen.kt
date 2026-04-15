package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.llm.ModelManager
import com.hermie.assistant.llm.ModelType
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

    val tiers = listOf(
        ModelTier("small", "Small", "~1.3 GB", "Qwen 3.5 2B — Fast & capable", "qwen3.5-2b"),
        ModelTier("medium", "Medium", "~2.7 GB", "Qwen 3.5 4B — Smart & balanced", "qwen3.5-4b"),
        ModelTier("large", "Large", "~5.2 GB", "Qwen 3.5 8B — Most capable", "qwen3.5-8b")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = HermieForest,
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.Start)
                .clickable(onClick = onBack)
        )

        Spacer(Modifier.height(24.dp))

        HermieSectionLabel("BRAIN DOWNLOAD")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Choose Hermie's\nbrain size",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Bigger brains are smarter but take more space.\nYou can change this later in Settings.",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 15.sp,
                color = HermieGrey,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(32.dp))

        tiers.forEach { tier ->
            val isDownloaded = ModelManager.BASE_BRAIN_MODELS
                .firstOrNull { it.id == tier.modelId }
                ?.let { modelManager.isDownloaded(it) } ?: false

            HermieOptionCard(
                text = "${tier.label}  •  ${tier.size}",
                subtitle = tier.description + if (isDownloaded) "  \u2713 Downloaded" else "",
                selected = selectedTier == tier.id,
                onClick = {
                    if (!isDownloading) {
                        selectedTier = tier.id
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        // Download progress (shows while downloading in background)
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
                                "Downloading in background...",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 14.sp,
                                    color = HermieForest
                                )
                            )
                            Text(
                                "${(state.progress * 100).toInt()}%",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 14.sp,
                                    color = HermieTerra
                                )
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
                is ModelManager.DownloadState.Failed -> {
                    Text(
                        "Download failed: ${state.error}",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 14.sp,
                            color = HermieError
                        )
                    )
                    isDownloading = false
                }
                else -> {}
            }
        }

        Spacer(Modifier.weight(1f))

        // Once downloading has started, let user proceed to home immediately
        if (isDownloading) {
            Text(
                text = "Download will continue in the background",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 13.sp,
                    color = HermieGrey,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        HermieButton(
            text = when {
                isDownloading -> "Let's go!"
                else -> "Download & Proceed"
            },
            onClick = {
                if (isDownloading) {
                    // User wants to proceed — download continues in background
                    // The ViewModel will auto-load the model when download completes
                    onComplete()
                } else {
                    settings.selectedModelTier = selectedTier
                    val model = ModelManager.BASE_BRAIN_MODELS
                        .firstOrNull { it.id == tiers.first { t -> t.id == selectedTier }.modelId }
                    if (model != null) {
                        if (modelManager.isDownloaded(model)) {
                            modelManager.setActiveModel(model)
                            // Also kick off mind + embedding downloads in background
                            downloadSupportModels(scope, modelManager)
                            onComplete()
                        } else {
                            isDownloading = true
                            scope.launch {
                                modelManager.downloadModel(model)
                                // Brain done — now download mind + embedding in background
                                downloadSupportModels(scope, modelManager)
                            }
                        }
                    }
                }
            },
            enabled = true,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

/**
 * Download SLM (memory classifier) and embedding model in background.
 * These are small and essential for the memory module to work.
 */
private fun downloadSupportModels(scope: kotlinx.coroutines.CoroutineScope, modelManager: ModelManager) {
    // Download SLM (mind model) if not already downloaded
    val slmModel = ModelManager.SLM_MODELS.firstOrNull()
    if (slmModel != null && !modelManager.isDownloaded(slmModel)) {
        scope.launch { modelManager.downloadModel(slmModel) }
    }
    // Download embedding model if not already downloaded
    val embModel = ModelManager.MIND_MODELS.firstOrNull()
    if (embModel != null && !modelManager.isDownloaded(embModel)) {
        scope.launch { modelManager.downloadModel(embModel) }
    }
}

private data class ModelTier(
    val id: String,
    val label: String,
    val size: String,
    val description: String,
    val modelId: String
)
