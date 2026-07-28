package com.hermie.assistant.modules.study

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hermie.assistant.llm.LlamaNativeEngine
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.modules.HermieModule
import com.hermie.assistant.modules.ScreenModule
import com.hermie.assistant.modules.memory.MemoryModule
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Study Module — reads PDFs or Wikipedia articles, chunks the content,
 * and uses the brain LLM (4B) to extract atomic facts into the memory graph.
 *
 * Study mode is similar to sleep mode: the banner expands, progress is shown,
 * and the LLM is dedicated to fact extraction. The SLM (slot 3) stays
 * operational so DND and Screen Time keep working.
 */
class StudyModule : HermieModule, ScreenModule {

    companion object {
        private const val TAG = "StudyModule"

        /**
         * Target chunk size in characters for the LLM context window.
         * ~1500 chars ≈ 400-500 tokens — leaves room for the extraction prompt + output.
         */
        private const val CHUNK_SIZE = 1500
        private const val CHUNK_OVERLAP = 200
        private const val EXTRACTION_MAX_TOKENS = 1024

        /** System prompt for fact extraction */
        const val EXTRACTION_SYSTEM_PROMPT = """You are a knowledge extraction assistant. Your job is to read study material and extract atomic facts from it.

Rules:
- Each fact should be a single, self-contained statement
- Facts should be specific and informative (not vague)
- Include names, dates, numbers, and relationships when present
- Output ONLY a JSON array of strings, one fact per string
- Extract 3-10 facts per chunk depending on density
- Do NOT include opinions, only verifiable facts

Example output:
["The Eiffel Tower was completed in 1889.", "Gustave Eiffel's company designed and built the tower.", "The tower is 330 meters tall including antennas."]"""
    }

    override val id = "study"
    override val displayName = "Study"
    override val description = "Learn from PDFs & Wikipedia articles"
    override val iconName = "auto_stories"

    private var _isActive = false
    override var isActive: Boolean
        get() = _isActive
        set(value) { _isActive = value }

    private lateinit var context: Context

    // Dependencies injected from ViewModel
    private var llamaEngine: LlamaNativeEngine? = null
    private var brainEngine: LlmEngine? = null
    private var memoryModule: MemoryModule? = null

    // ── Study mode state ──

    private val _isStudying = MutableStateFlow(false)
    val isStudying: StateFlow<Boolean> = _isStudying.asStateFlow()

    private val _studyProgress = MutableStateFlow("")
    val studyProgress: StateFlow<String> = _studyProgress.asStateFlow()

    private val _studyLog = MutableStateFlow<List<String>>(emptyList())
    val studyLog: StateFlow<List<String>> = _studyLog.asStateFlow()

    private val _totalFactsExtracted = MutableStateFlow(0)
    val totalFactsExtracted: StateFlow<Int> = _totalFactsExtracted.asStateFlow()

    // Wikipedia search results for UI
    private val _searchResults = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val searchResults: StateFlow<List<Pair<String, String>>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ── Study queue (processed during sleep mode) ──

    private val _queuedItems = MutableStateFlow<List<QueuedStudyItem>>(emptyList())
    val queuedItems: StateFlow<List<QueuedStudyItem>> = _queuedItems.asStateFlow()

    /**
     * Queue a Wikipedia article for study during sleep mode.
     */
    fun queueWikipediaArticle(title: String) {
        val current = _queuedItems.value.toMutableList()
        // Avoid duplicates
        if (current.none { it is QueuedStudyItem.Wikipedia && it.title == title }) {
            current.add(QueuedStudyItem.Wikipedia(title))
            _queuedItems.value = current
            Log.i(TAG, "Queued Wikipedia article: $title (${current.size} in queue)")
        }
    }

    /**
     * Queue a PDF for study during sleep mode.
     */
    fun queuePdf(uri: Uri, fileName: String) {
        val current = _queuedItems.value.toMutableList()
        current.add(QueuedStudyItem.Pdf(uri, fileName))
        _queuedItems.value = current
        Log.i(TAG, "Queued PDF: $fileName (${current.size} in queue)")
    }

    /**
     * Remove a queued item by index.
     */
    fun removeFromQueue(index: Int) {
        val current = _queuedItems.value.toMutableList()
        if (index in current.indices) {
            val removed = current.removeAt(index)
            _queuedItems.value = current
            Log.i(TAG, "Removed from queue: $removed (${current.size} remaining)")
        }
    }

    /**
     * Process all queued study items. Called during sleep mode.
     * Returns total facts extracted across all items.
     *
     * @param onThermalCheck called between items to allow cooling if device is hot
     */
    suspend fun processQueue(
        onProgress: (String) -> Unit,
        onThermalCheck: suspend () -> Unit = {}
    ): Int {
        val items = _queuedItems.value.toList()
        if (items.isEmpty()) return 0

        var totalFacts = 0
        onProgress("--- Study Queue: ${items.size} items ---")

        for ((index, item) in items.withIndex()) {
            // Thermal check between items
            if (index > 0) onThermalCheck()

            onProgress("Item ${index + 1}/${items.size}: ${item.displayName}")
            val facts = when (item) {
                is QueuedStudyItem.Wikipedia -> studyWikipediaArticle(item.title, onProgress, onThermalCheck)
                is QueuedStudyItem.Pdf -> studyPdf(item.uri, item.fileName, onProgress, onThermalCheck)
            }
            totalFacts += facts
        }

        // Clear queue after processing
        _queuedItems.value = emptyList()
        onProgress("--- Queue complete: $totalFacts facts from ${items.size} items ---")
        return totalFacts
    }

    override suspend fun initialize(context: Context) {
        this.context = context
        // Initialize PdfBox-Android
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            Log.i(TAG, "PdfBox initialized")
        } catch (e: Exception) {
            Log.w(TAG, "PdfBox init failed (PDF extraction may not work)", e)
        }
        _isActive = true
    }

    override suspend fun start() {}
    override suspend fun stop() {
        _isStudying.value = false
    }
    override fun release() {
        _isActive = false
    }

    @Composable
    override fun Screen(onBack: () -> Unit) {
        // Placeholder — actual screen is in ui/study/StudyScreen.kt, wired in MainActivity
    }

    /**
     * Inject dependencies from ViewModel.
     */
    fun setEngines(
        llamaEngine: LlamaNativeEngine,
        brainEngine: LlmEngine,
        memoryModule: MemoryModule
    ) {
        this.llamaEngine = llamaEngine
        this.brainEngine = brainEngine
        this.memoryModule = memoryModule
    }

    // ── Wikipedia search ──

    /**
     * Search Wikipedia and populate search results.
     */
    suspend fun searchWikipedia(query: String) {
        _isSearching.value = true
        try {
            val results = WikipediaApi.search(query)
            _searchResults.value = results
            Log.i(TAG, "Wikipedia search for '$query': ${results.size} results")
        } finally {
            _isSearching.value = false
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    // ── Text chunking ──

    /**
     * Split raw text into overlapping chunks for LLM processing.
     */
    fun chunkText(text: String, sourceTitle: String): List<StudyChunk> {
        val cleaned = text
            .replace(Regex("\\n{3,}"), "\n\n")  // Collapse excessive newlines
            .trim()

        if (cleaned.length <= CHUNK_SIZE) {
            return listOf(StudyChunk(cleaned, sourceTitle, 0, 1))
        }

        val chunks = mutableListOf<StudyChunk>()
        var start = 0
        while (start < cleaned.length) {
            var end = minOf(start + CHUNK_SIZE, cleaned.length)

            // Try to break at a paragraph or sentence boundary
            if (end < cleaned.length) {
                val paragraphBreak = cleaned.lastIndexOf("\n\n", end)
                if (paragraphBreak > start + CHUNK_SIZE / 2) {
                    end = paragraphBreak + 2
                } else {
                    val sentenceBreak = cleaned.lastIndexOf(". ", end)
                    if (sentenceBreak > start + CHUNK_SIZE / 2) {
                        end = sentenceBreak + 2
                    }
                }
            }

            chunks.add(StudyChunk(
                text = cleaned.substring(start, end).trim(),
                sourceTitle = sourceTitle,
                chunkIndex = chunks.size,
                totalChunks = 0 // Will be fixed after
            ))

            start = if (end >= cleaned.length) cleaned.length
                    else maxOf(end - CHUNK_OVERLAP, start + 1)
        }

        // Fix totalChunks
        return chunks.map { it.copy(totalChunks = chunks.size) }
    }

    // ── Main study pipeline ──

    /**
     * Study a Wikipedia article by title.
     * Call from ViewModel on Dispatchers.IO inside study mode.
     *
     * @param onProgress callback for logging to the study log
     * @return number of facts extracted
     */
    suspend fun studyWikipediaArticle(
        articleTitle: String,
        onProgress: (String) -> Unit,
        onThermalCheck: suspend () -> Unit = {}
    ): Int {
        onProgress("Fetching Wikipedia article: $articleTitle")
        val text = WikipediaApi.getArticleText(articleTitle)
        if (text.isNullOrBlank()) {
            onProgress("Error: Could not fetch article text")
            return 0
        }
        onProgress("Article fetched (${text.length} chars)")
        return studyText(text, "Wikipedia: $articleTitle", onProgress, onThermalCheck)
    }

    /**
     * Study a PDF from a content URI.
     */
    suspend fun studyPdf(
        uri: Uri,
        fileName: String,
        onProgress: (String) -> Unit,
        onThermalCheck: suspend () -> Unit = {}
    ): Int {
        onProgress("Extracting text from PDF: $fileName")
        val text = PdfTextExtractor.extractText(context, uri)
        if (text.isNullOrBlank()) {
            onProgress("Error: Could not extract text from PDF")
            return 0
        }
        onProgress("PDF text extracted (${text.length} chars)")
        return studyText(text, "PDF: $fileName", onProgress, onThermalCheck)
    }

    /**
     * Core study pipeline: chunk text → extract facts → store in memory buffer.
     *
     * @param onThermalCheck called between chunks to allow cooling
     */
    suspend fun studyText(
        text: String,
        sourceTitle: String,
        onProgress: (String) -> Unit,
        onThermalCheck: suspend () -> Unit = {}
    ): Int {
        val engine = brainEngine ?: run {
            onProgress("Error: Brain engine not available")
            return 0
        }
        val memory = memoryModule ?: run {
            onProgress("Error: Memory module not available")
            return 0
        }

        val chunks = chunkText(text, sourceTitle)
        onProgress("Split into ${chunks.size} chunks")

        // Create one anchor node for this document before writing any buffer entries.
        // If the anchor fails (DB not ready, insert error), abort — without a valid
        // anchor id every buffer row would be orphaned and skipped by Phase B consolidation.
        val anchorId = memory.createStudyAnchor(sourceTitle)
        if (anchorId <= 0) {
            onProgress("ERROR: Failed to create study anchor for \"$sourceTitle\" (id=$anchorId) — aborting study")
            Log.e(TAG, "createStudyAnchor returned invalid id=$anchorId for \"$sourceTitle\" — study aborted")
            return 0
        }
        onProgress("Anchor node: \"$sourceTitle\" (id=$anchorId)")

        var totalFacts = 0

        for (chunk in chunks) {
            // Thermal check between chunks
            if (chunk.chunkIndex > 0) onThermalCheck()

            onProgress("Processing chunk ${chunk.chunkIndex + 1}/${chunks.size}...")

            try {
                // Build extraction prompt
                val userPrompt = buildExtractionPrompt(chunk)

                // Use the brain LLM to extract facts
                val messages = listOf(
                    LlmEngine.Message(role = "user", content = userPrompt)
                )
                val response = StringBuilder()
                engine.generate(messages, maxTokens = EXTRACTION_MAX_TOKENS).collect { token ->
                    response.append(token)
                }

                // Parse facts from JSON array response
                val facts = parseFacts(response.toString(), chunk)
                if (facts.isNotEmpty()) {
                    for (fact in facts) {
                        memory.storeStudyFact(fact.text, sourceTitle, anchorId)
                    }
                    totalFacts += facts.size
                    _totalFactsExtracted.value += facts.size
                    onProgress("  + ${facts.size} facts extracted")
                } else {
                    onProgress("  ~ No facts extracted from this chunk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing chunk ${chunk.chunkIndex}", e)
                onProgress("  Error: ${e.message}")
            }
        }

        onProgress("--- $sourceTitle: $totalFacts facts total ---")
        return totalFacts
    }

    private fun buildExtractionPrompt(chunk: StudyChunk): String {
        return """Extract atomic facts from the following study material. Output ONLY a JSON array of fact strings.

Source: ${chunk.sourceTitle} (chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks})

---
${chunk.text}
---

Extract the key facts as a JSON array:"""
    }

    /**
     * Parse the LLM's response into individual facts.
     * Handles both clean JSON arrays and messy outputs.
     */
    private fun parseFacts(response: String, chunk: StudyChunk): List<StudyFact> {
        val facts = mutableListOf<StudyFact>()
        try {
            // Try to find a JSON array in the response
            val trimmed = response.trim()
            val arrayStart = trimmed.indexOf('[')
            val arrayEnd = trimmed.lastIndexOf(']')
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                val jsonStr = trimmed.substring(arrayStart, arrayEnd + 1)
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val factText = array.getString(i).trim()
                    if (factText.length > 10) { // Skip trivially short "facts"
                        facts.add(StudyFact(factText, chunk.sourceTitle, chunk.chunkIndex))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse facts JSON, trying line-by-line", e)
            // Fallback: treat each line as a fact
            response.lines()
                .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("\"").removeSuffix("\"").trim() }
                .filter { it.length > 15 && !it.startsWith("[") && !it.startsWith("]") }
                .take(10)
                .forEach { line ->
                    facts.add(StudyFact(line, chunk.sourceTitle, chunk.chunkIndex))
                }
        }
        return facts
    }

    // ── Study mode lifecycle (called from ViewModel) ──

    fun startStudyMode() {
        _isStudying.value = true
        _studyLog.value = emptyList()
        _totalFactsExtracted.value = 0
        _studyProgress.value = "Ready to study"
    }

    fun stopStudyMode() {
        _isStudying.value = false
        _studyProgress.value = ""
        _studyLog.value = emptyList()
    }

    fun appendStudyLog(message: String) {
        val current = _studyLog.value.toMutableList()
        current.add(message)
        _studyLog.value = if (current.size > 200) current.takeLast(200) else current
        Log.d(TAG, "Study: $message")
    }

    fun setProgress(message: String) {
        _studyProgress.value = message
    }
}
