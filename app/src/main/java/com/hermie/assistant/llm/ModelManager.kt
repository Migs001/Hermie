package com.hermie.assistant.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.hermie.assistant.data.HermieSettings
import java.net.HttpURLConnection
import java.net.URL

enum class ModelType(val subDir: String, val label: String) {
    BRAIN("brain", "Brain"),
    EARS("ears", "Ears"),
    VOICE("voice", "Voice"),
    MIND("mind", "Mind"),
    SLM("slm", "Small LM"),
    VISION("vision", "Vision")
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    val paramCount: String,
    val type: ModelType = ModelType.BRAIN,
    /** Whether this is a finetuned model (uses simplified system prompt, requires HF token) */
    val finetuned: Boolean = false,
    /** Extra files that must be downloaded alongside the main file (e.g. decoder, tokens) */
    val extraFiles: List<ExtraFile> = emptyList(),
    val useTurboCache: Boolean = false,
    val contextSize: Int = 8192
)

data class ExtraFile(
    val fileName: String,
    val url: String
)

class ModelManager(private val context: Context, private val settings: HermieSettings? = null) {

    companion object {
        private const val TAG = "ModelManager"

        /**
         * Base (non-finetuned) brain models.
         * Now using llama.cpp built from source (latest) — supports both
         * Qwen 2.5 and Qwen 3 architectures with full ARM optimizations.
         */
        val BASE_BRAIN_MODELS = listOf(
            // ── Qwen 3.5 only (hybrid arch, unified vision-language) ──
            ModelInfo(
                id = "qwen3.5-0.8b",
                displayName = "Qwen 3.5 0.8B",
                fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
                url = ModelUrls.BRAIN_QWEN35_08B,
                sizeMb = 533,
                paramCount = "0.8B",
                type = ModelType.BRAIN,
                useTurboCache = false,
                contextSize = 4096
            ),
            ModelInfo(
                id = "qwen3.5-2b",
                displayName = "Qwen 3.5 2B",
                fileName = "Qwen3.5-2B-Q4_K_M.gguf",
                url = ModelUrls.BRAIN_QWEN35_2B,
                sizeMb = 1280,
                paramCount = "2B",
                type = ModelType.BRAIN,
                useTurboCache = true,
                contextSize = 12288
            ),
            ModelInfo(
                id = "qwen3.5-4b",
                displayName = "Qwen 3.5 4B",
                fileName = "Qwen3.5-4B-Q4_K_M.gguf",
                url = ModelUrls.BRAIN_QWEN35_4B,
                sizeMb = 2740,
                paramCount = "4B",
                type = ModelType.BRAIN,
                useTurboCache = true,
                contextSize = 16384
            ),
            ModelInfo(
                id = "qwen3.5-8b",
                displayName = "Qwen 3.5 9B",
                fileName = "Qwen3.5-8B-Q4_K_M.gguf",
                url = ModelUrls.BRAIN_QWEN35_8B,
                sizeMb = 5200,
                paramCount = "9B",
                type = ModelType.BRAIN,
                useTurboCache = true,
                contextSize = 16384
            )
        )
        /** Finetuned brain models (require HF token for download) */
        val FINETUNED_BRAIN_MODELS = listOf(
            ModelInfo(
                id = "qwen2.5-1.5b-ft",
                displayName = "Qwen 2.5 1.5B (Finetuned)",
                fileName = "qwen2.5-1.5b-finetuned.gguf",
                url = ModelUrls.BRAIN_FINETUNED_QWEN_15B,
                sizeMb = 1120,
                paramCount = "1.5B",
                type = ModelType.BRAIN,
                finetuned = true,
                useTurboCache = false,
                contextSize = 8192

            ),
            ModelInfo(
                id = "qwen2.5-3b-ft",
                displayName = "Qwen 2.5 3B (Finetuned)",
                fileName = "bmo-qwen2.5-3b-q4_k_m.gguf",
                url = ModelUrls.BRAIN_FINETUNED_QWEN_3B,
                sizeMb = 2000,
                paramCount = "3B",
                type = ModelType.BRAIN,
                finetuned = true,
                useTurboCache = true,
                contextSize = 12288
            )
        )

        /** All brain models (current + finetuned) */
        val BRAIN_MODELS = BASE_BRAIN_MODELS + FINETUNED_BRAIN_MODELS

        val EARS_MODELS = listOf(
            ModelInfo(
                id = "whisper-tiny-en",
                displayName = "Whisper Tiny English",
                fileName = "tiny.en-encoder.int8.onnx",
                url = ModelUrls.EARS_WHISPER_TINY_ENCODER,
                sizeMb = 117,
                paramCount = "39M",
                type = ModelType.EARS,
                extraFiles = listOf(
                    ExtraFile("tiny.en-decoder.int8.onnx", ModelUrls.EARS_WHISPER_TINY_DECODER),
                    ExtraFile("tiny.en-tokens.txt", ModelUrls.EARS_WHISPER_TINY_TOKENS)
                )
            )
        )

        val VOICE_MODELS = listOf(
            ModelInfo(
                id = "piper-en-medium",
                displayName = "Piper English",
                fileName = "en_US-lessac-medium.onnx",
                url = ModelUrls.VOICE_PIPER_EN_MEDIUM,
                sizeMb = 75,
                paramCount = "~15M",
                type = ModelType.VOICE,
                extraFiles = listOf(
                    ExtraFile("en_US-lessac-medium.onnx.json", ModelUrls.VOICE_PIPER_EN_MEDIUM_JSON),
                    ExtraFile("tokens.txt", ModelUrls.VOICE_PIPER_TOKENS),
                    ExtraFile("espeak-ng-data.tar.bz2", ModelUrls.VOICE_PIPER_ESPEAK_DATA)
                )
            )
        )

        val MIND_MODELS = listOf(
            ModelInfo(
                id = "minilm-l6-v2",
                displayName = "MiniLM-L6-v2",
                fileName = "model.tflite",
                url = ModelUrls.MIND_MINILM_TFLITE,
                sizeMb = 23,
                paramCount = "22M",
                type = ModelType.MIND,
                extraFiles = listOf(
                    ExtraFile("vocab.txt", ModelUrls.MIND_MINILM_VOCAB)
                )
            )
        )

        val SLM_MODELS = listOf(
            ModelInfo(
                id = "qwen3-06b-drip",
                displayName = "Qwen3 0.6B (Drip Atomizer)",
                fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                url = ModelUrls.SLM_QWEN3_06B,
                sizeMb = 400,
                paramCount = "0.6B",
                type = ModelType.SLM,
                useTurboCache = false,
                contextSize = 4096  // Conversation transcript + system prompt + message + response
            )
        )

        val VISION_MODELS = listOf(
            ModelInfo(
                id = "qwen3-vl-2b",
                displayName = "Qwen3 VL 2B",
                fileName = "Qwen3-VL-2B-Instruct-Q4_K_M.gguf",
                url = ModelUrls.VISION_QWEN3VL_2B,
                sizeMb = 1100,
                paramCount = "2B",
                type = ModelType.VISION,
                useTurboCache = false,
                contextSize = 8192,
                extraFiles = listOf(
                    ExtraFile("mmproj.gguf", ModelUrls.VISION_QWEN3VL_2B_MMPROJ)
                )
            ),
            ModelInfo(
                id = "qwen3-vl-4b",
                displayName = "Qwen3 VL 4B",
                fileName = "Qwen3-VL-4B-Instruct-Q4_K_M.gguf",
                url = ModelUrls.VISION_QWEN3VL_4B,
                sizeMb = 2500,
                paramCount = "4B",
                type = ModelType.VISION,
                useTurboCache = true,
                contextSize = 8192,
                extraFiles = listOf(
                    ExtraFile("mmproj.gguf", ModelUrls.VISION_QWEN3VL_4B_MMPROJ)
                )
            )
        )

        /** All models across all types */
        val ALL_MODELS = BRAIN_MODELS + EARS_MODELS + VOICE_MODELS + MIND_MODELS + SLM_MODELS + VISION_MODELS

        /** Backwards-compatible alias */
        val AVAILABLE_MODELS = BRAIN_MODELS

        fun modelsForType(type: ModelType): List<ModelInfo> = when (type) {
            ModelType.BRAIN -> BRAIN_MODELS
            ModelType.EARS -> EARS_MODELS
            ModelType.VOICE -> VOICE_MODELS
            ModelType.MIND -> MIND_MODELS
            ModelType.SLM -> SLM_MODELS
            ModelType.VISION -> VISION_MODELS
        }
    }

    private val baseDir = File(context.filesDir, "models")

    private fun typeDir(type: ModelType) = File(baseDir, type.subDir)
    private fun modelFile(model: ModelInfo) = File(typeDir(model.type), model.fileName)

    // ── Per-type active model (restored from settings) ──

    private val _activeModels = ModelType.entries.associateWith { type ->
        val savedId = settings?.getActiveModelId(type.subDir)
        var restoredModel: ModelInfo? = null
        if (savedId != null) {
            // Only restore if the model is in the supported base list AND downloaded
            val baseModels = when (type) {
                ModelType.BRAIN -> BASE_BRAIN_MODELS
                else -> modelsForType(type)
            }
            restoredModel = baseModels.firstOrNull { it.id == savedId }?.takeIf { isDownloaded(it) }
            if (restoredModel == null) {
                // Saved model is unsupported/missing — fall back to any supported downloaded model
                restoredModel = baseModels.firstOrNull { isDownloaded(it) }
                if (restoredModel != null) {
                    Log.d(TAG, "Saved model '$savedId' unavailable, falling back to ${restoredModel.id}")
                    settings?.setActiveModelId(type.subDir, restoredModel.id)
                }
            }
        }
        MutableStateFlow(restoredModel)
    }

    fun activeModelFor(type: ModelType): StateFlow<ModelInfo?> =
        _activeModels[type]!!.asStateFlow()

    // Backwards-compatible
    private val _activeModel get() = _activeModels[ModelType.BRAIN]!!
    val activeModel: StateFlow<ModelInfo?> = _activeModel.asStateFlow()

    // ── Model path (brain) ──

    val modelPath: String
        get() {
            val model = _activeModel.value ?: findDownloadedModel(ModelType.BRAIN)?.let {
                setActiveModel(it)
                it
            } ?: return ""
            return modelFile(model).absolutePath
        }

    fun modelPathFor(type: ModelType): String {
        val model = _activeModels[type]!!.value ?: findDownloadedModel(type)?.let {
            setActiveModel(it)
            it
        } ?: return ""
        return modelFile(model).absolutePath
    }

    // ── Download state per type ──

    private val _downloadStates = ModelType.entries.associateWith {
        MutableStateFlow<DownloadState>(DownloadState.Idle)
    }

    fun downloadStateFor(type: ModelType): StateFlow<DownloadState> =
        _downloadStates[type]!!.asStateFlow()

    // Backwards-compatible
    val downloadState: StateFlow<DownloadState> = _downloadStates[ModelType.BRAIN]!!.asStateFlow()

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        data object Complete : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    // ── Query methods ──

    val isModelDownloaded: Boolean get() = isAnyDownloaded(ModelType.BRAIN)

    fun isAnyDownloaded(type: ModelType): Boolean =
        modelsForType(type).any { isDownloaded(it) }

    fun isDownloaded(model: ModelInfo): Boolean {
        val file = modelFile(model)
        if (!file.exists()) return false
        // Check file is at least 80% of expected size to catch partial downloads
        // (exact size may vary slightly due to HTTP chunking)
        val expectedBytes = model.sizeMb * 1_000_000L
        val minBytes = (expectedBytes * 0.8).toLong()
        return file.length() >= minBytes
    }

    fun findDownloadedModel(type: ModelType): ModelInfo? {
        // Prefer base (supported) models over legacy/unsupported ones
        val base = when (type) {
            ModelType.BRAIN -> BASE_BRAIN_MODELS
            else -> modelsForType(type)
        }
        return base.firstOrNull { isDownloaded(it) }
            ?: modelsForType(type).firstOrNull { isDownloaded(it) }
    }

    fun findAnyDownloadedModel(): ModelInfo? = findDownloadedModel(ModelType.BRAIN)

    fun setActiveModel(model: ModelInfo) {
        _activeModels[model.type]!!.value = model
        settings?.setActiveModelId(model.type.subDir, model.id)
    }

    // ── Download ──

    suspend fun downloadModel(
        model: ModelInfo,
        hfToken: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val stateFlow = _downloadStates[model.type]!!
        try {
            stateFlow.value = DownloadState.Downloading(0f)
            val dir = typeDir(model.type)
            dir.mkdirs()

            // Download main file
            val mainSuccess = downloadFile(
                url = model.url,
                targetFile = modelFile(model),
                hfToken = hfToken,
                onProgress = { progress ->
                    stateFlow.value = DownloadState.Downloading(progress)
                }
            )
            if (!mainSuccess) {
                stateFlow.value = DownloadState.Failed("Download failed")
                return@withContext false
            }

            // Download extra files
            for (extra in model.extraFiles) {
                val extraFile = File(dir, extra.fileName)
                val extraSuccess = downloadFile(
                    url = extra.url,
                    targetFile = extraFile,
                    hfToken = hfToken,
                    onProgress = { /* extra files don't update main progress */ }
                )
                if (!extraSuccess) {
                    stateFlow.value = DownloadState.Failed("Failed to download ${extra.fileName}")
                    return@withContext false
                }
                // Extract tar.bz2 archives (e.g. espeak-ng-data)
                if (extra.fileName.endsWith(".tar.bz2")) {
                    try {
                        extractTarBz2(extraFile, dir)
                        extraFile.delete() // Remove archive after extraction
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract ${extra.fileName}", e)
                        stateFlow.value = DownloadState.Failed("Failed to extract ${extra.fileName}")
                        return@withContext false
                    }
                }
            }

            _activeModels[model.type]!!.value = model
            stateFlow.value = DownloadState.Complete
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.id}", e)
            // Clean up temp files
            File(typeDir(model.type), "${model.fileName}.tmp").delete()
            stateFlow.value = DownloadState.Failed(e.message ?: "Download failed")
            false
        }
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        hfToken: String?,
        onProgress: (Float) -> Unit
    ): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (!hfToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $hfToken")
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${connection.responseCode} for $url")
                return false
            }

            val totalSize = connection.contentLengthLong
            var downloadedSize = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead
                        if (totalSize > 0) {
                            onProgress(downloadedSize.toFloat() / totalSize)
                        }
                    }
                }
            }

            tempFile.renameTo(targetFile)
            return true
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun extractTarBz2(archive: File, targetDir: File) {
        Log.d(TAG, "Extracting ${archive.name} to ${targetDir.absolutePath}")
        val fileInput = FileInputStream(archive)
        val buffered = BufferedInputStream(fileInput)
        val bzip2 = BZip2CompressorInputStream(buffered)
        val tar = TarArchiveInputStream(bzip2)

        var entry = tar.nextEntry
        while (entry != null) {
            val outFile = File(targetDir, entry.name)
            // Security: prevent path traversal
            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                throw SecurityException("Path traversal detected: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    tar.copyTo(out)
                }
            }
            entry = tar.nextEntry
        }
        tar.close()
        Log.d(TAG, "Extraction complete")
    }

    fun deleteModel(model: ModelInfo) {
        modelFile(model).delete()
        // Also delete extra files
        val dir = typeDir(model.type)
        model.extraFiles.forEach { extra ->
            val extraFile = File(dir, extra.fileName)
            extraFile.delete()
            // If it was a .tar.bz2 archive, delete the extracted directory too
            if (extra.fileName.endsWith(".tar.bz2")) {
                val extractedName = extra.fileName.removeSuffix(".tar.bz2")
                val extractedDir = File(dir, extractedName)
                if (extractedDir.isDirectory) {
                    extractedDir.deleteRecursively()
                }
            }
        }
        if (_activeModels[model.type]!!.value?.id == model.id) {
            _activeModels[model.type]!!.value = findDownloadedModel(model.type)
        }
        Log.d(TAG, "Deleted model: ${model.id} (${model.type.label})")
    }

    // ── Migration ──

    init {
        migrateOldModels()
    }

    /**
     * Move models from old flat models/ directory to models/brain/ subdirectory.
     */
    private fun migrateOldModels() {
        if (!baseDir.exists()) return
        val brainDir = typeDir(ModelType.BRAIN)

        BRAIN_MODELS.forEach { model ->
            val oldFile = File(baseDir, model.fileName)
            if (oldFile.exists() && oldFile.isFile) {
                brainDir.mkdirs()
                val newFile = File(brainDir, model.fileName)
                if (!newFile.exists()) {
                    val moved = oldFile.renameTo(newFile)
                    if (moved) {
                        Log.d(TAG, "Migrated ${model.fileName} to brain/")
                    } else {
                        // renameTo can fail across mount points; copy+delete fallback
                        try {
                            oldFile.copyTo(newFile, overwrite = true)
                            oldFile.delete()
                            Log.d(TAG, "Migrated ${model.fileName} to brain/ (copy+delete)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to migrate ${model.fileName}", e)
                        }
                    }
                }
            }
        }
    }
}
