package com.hermie.assistant.modules.wardrobe

import android.content.Context
import android.util.Log
import com.hermie.assistant.data.PromptLoader
import com.hermie.assistant.llm.ImageUtils
import com.hermie.assistant.llm.LlamaNativeEngine
import com.hermie.assistant.llm.LlmEngine
import com.hermie.assistant.llm.ModelManager
import com.hermie.assistant.llm.ModelType
import com.hermie.llamacpp.InferenceEngine
import com.hermie.assistant.modules.HermieModule
import com.hermie.assistant.modules.ScreenModule
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WardrobeModule : HermieModule, ScreenModule {

    companion object {
        private const val TAG = "WardrobeModule"
        private const val MAX_CATEGORIZE_RETRIES = 5
        private const val CATEGORIZE_MAX_TOKENS = 512
        private const val OUTFIT_MAX_TOKENS = 2048
        private const val MAX_ITEMS_COLD = 5   // per type when cold (many layers)
        private const val MAX_ITEMS_HOT = 10   // per type when hot (fewer layers)
        private const val FORMALITY_RANGE = 0.5f

        // Style profile update frequency
        private const val STYLE_UPDATE_INTERVAL = 5

        // Sentinel ClothingItem returned when VLM says "not clothing"
        private val REJECTION_MARKER = ClothingItem(
            imagePath = "", color = "", pattern = "", fabric = "",
            type = "not_clothing", formality = 0, description = "Not a clothing item",
            createdAt = 0
        )
    }

    override val id = "wardrobe"
    override val displayName = "Wardrobe"
    override val description = "Outfit recommendations from your wardrobe"
    override val iconName = "checkroom"
    override val requiredPermissions = listOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var _isActive = false
    override var isActive: Boolean
        get() = _isActive
        set(value) { _isActive = value }

    private lateinit var context: Context
    private lateinit var db: WardrobeDatabase
    private lateinit var weatherService: WeatherService

    private var llamaEngine: LlamaNativeEngine? = null
    private var brainEngine: LlmEngine? = null
    private var modelManager: ModelManager? = null
    private var inferenceEngine: InferenceEngine? = null

    private val _itemCount = MutableStateFlow(0)
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _unprocessedCount = MutableStateFlow(0)
    val unprocessedCount: StateFlow<Int> = _unprocessedCount.asStateFlow()

    // Cached pre-filtered items for "Try Again" (skip weather+formality filtering)
    private var cachedPreFiltered: List<ClothingItem>? = null
    private var cachedOccasion: String? = null
    private var cachedFormality: Int? = null
    private var cachedUserRequest: String? = null
    private var cachedGender: String? = null
    private var cachedUseFahrenheit: Boolean = false
    private var cachedIsHot: Boolean = true

    override suspend fun initialize(context: Context) {
        this.context = context
        db = WardrobeDatabase(context)
        weatherService = WeatherService(context)
        _isActive = true
        refreshCounts()
    }

    /** Fetch current weather for UI display. */
    suspend fun getWeather(): WeatherData? = weatherService.getCurrentWeather()

    override suspend fun start() { _isActive = true }
    override suspend fun stop() { _isActive = false }

    fun setEngines(brain: LlmEngine, llamaEngine: LlamaNativeEngine, modelManager: ModelManager, inferenceEngine: InferenceEngine) {
        this.brainEngine = brain
        this.llamaEngine = llamaEngine
        this.modelManager = modelManager
        this.inferenceEngine = inferenceEngine
    }

    override fun release() {
        _isActive = false
    }

    private fun refreshCounts() {
        try {
            _itemCount.value = db.getItemCount()
            _unprocessedCount.value = db.getUnprocessedCount()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh counts", e)
        }
    }

    // ── Photo management ────────────────────────────────────

    /**
     * Add photos to the unprocessed queue.
     * Photos are copied to a stable location (wardrobe_photos/) and the original URI is discarded.
     */
    fun addPhotos(uris: List<String>) {
        val photosDir = File(context.filesDir, "wardrobe_photos")
        photosDir.mkdirs()

        for (uri in uris) {
            try {
                val targetFile = File(photosDir, "cloth_${System.currentTimeMillis()}.jpg")
                val savedPath = ImageUtils.saveImageToFile(uri, targetFile, context)
                db.addUnprocessedPhoto(savedPath)
                Log.d(TAG, "Added photo: $savedPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save photo from $uri", e)
            }
        }
        refreshCounts()
    }

    fun getUnprocessedCount(): Int = db.getUnprocessedCount()

    fun getAllItems(): List<ClothingItem> = db.getAllItems()

    fun getItemsByType(type: String): List<ClothingItem> = db.getItemsByType(type)

    fun updateItem(item: ClothingItem) {
        db.updateClothingItem(item)
        refreshCounts()
    }

    fun deactivateItem(itemId: Long) {
        // Delete the photo file when deactivating
        val item = db.getItemById(itemId)
        if (item != null) {
            try { File(item.imagePath).delete() } catch (_: Exception) {}
        }
        db.deactivateItem(itemId)
        refreshCounts()
    }

    // ── Categorization (runs during sleep mode) ─────────────

    /**
     * Categorize all unprocessed photos using a vision LLM.
     * This method handles the full lifecycle: unload brain → load vision → process → unload vision.
     * The CALLER is responsible for reloading the brain model afterward.
     *
     * @return Number of successfully categorized items
     */
    suspend fun categorizePhotos(
        onProgress: (String) -> Unit
    ): Int {
        val photos = db.getUnprocessedPhotos()
        if (photos.isEmpty()) return 0

        val mm = modelManager ?: run {
            onProgress("ERROR: ModelManager not available")
            return 0
        }

        // Find downloaded vision model
        val visionModel = ModelManager.VISION_MODELS.firstOrNull { mm.isDownloaded(it) }
        if (visionModel == null) {
            onProgress("No vision model downloaded — skipping wardrobe categorization")
            return 0
        }

        val engine = llamaEngine ?: run {
            onProgress("ERROR: LLM engine not available")
            return 0
        }

        val visionPath = mm.modelPathFor(ModelType.VISION)
        val visionDir = File(context.filesDir, "models/vision")
        val mmprojFile = File(visionDir, "mmproj.gguf")

        if (visionPath.isBlank() || !mmprojFile.exists()) {
            onProgress("Vision model files missing — skipping")
            return 0
        }

        // Load the categorization system prompt
        val systemPrompt = PromptLoader.load(context, "wardrobe_categorize.txt")
        if (systemPrompt == null) {
            onProgress("ERROR: Missing wardrobe_categorize.txt prompt")
            return 0
        }

        val rawEngine = inferenceEngine
        if (rawEngine == null) {
            onProgress("ERROR: InferenceEngine not available")
            return 0
        }

        onProgress("Unloading brain model for wardrobe processing...")

        // Ensure we're on slot 0 before any operations — prevents race with MindEngine on slot 1
        try {
            rawEngine.setActiveSlot(0)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting active slot to 0", e)
        }

        // Unload brain to free memory
        try {
            engine.unloadModel()
        } catch (e: Exception) {
            Log.w(TAG, "Error unloading brain", e)
        }

        // Load vision model — explicitly on slot 0
        onProgress("Loading vision model: ${visionModel.displayName}...")
        try {
            rawEngine.setActiveSlot(0)  // re-assert slot 0 in case MindEngine switched
            engine.setSystemPrompt(systemPrompt)
            engine.loadModel(visionPath, visionModel.useTurboCache, visionModel.contextSize)
            engine.loadMmproj(mmprojFile.absolutePath)
        } catch (e: Exception) {
            onProgress("ERROR: Failed to load vision model: ${e.message}")
            Log.e(TAG, "Failed to load vision model", e)
            return 0
        }

        onProgress("Vision model ready. Processing ${photos.size} photos...")

        var categorized = 0
        for ((index, photo) in photos.withIndex()) {
            onProgress("[${index + 1}/${photos.size}] Categorizing: ${File(photo.imagePath).name}")

            val item = categorizeWithRetries(engine, photo, systemPrompt, onProgress)
            if (item != null && item.type == "not_clothing") {
                // VLM intentionally rejected this as not a clothing item
                onProgress("  x Rejected: not a clothing item — ${item.description}")
                db.removeUnprocessedPhoto(photo.id)
                try { File(photo.imagePath).delete() } catch (_: Exception) {}
            } else if (item != null) {
                db.addClothingItem(item)
                db.removeUnprocessedPhoto(photo.id)
                // Delete the photo file after successful categorization
                try { File(photo.imagePath).delete() } catch (_: Exception) {}
                categorized++
                onProgress("  + ${item.type}: ${item.color} ${item.pattern} ${item.fabric} (formality ${item.formality})")
            } else {
                onProgress("  ! Failed to categorize after $MAX_CATEGORIZE_RETRIES retries — skipping")
                db.removeUnprocessedPhoto(photo.id)
                try { File(photo.imagePath).delete() } catch (_: Exception) {}
            }

            // Reset context between photos for fresh classification
            try { engine.resetContext() } catch (_: Exception) {}
        }

        // Unload vision model (caller reloads brain)
        onProgress("Unloading vision model...")
        try {
            engine.unloadModel()
        } catch (e: Exception) {
            Log.w(TAG, "Error unloading vision model", e)
        }

        refreshCounts()
        return categorized
    }

    private suspend fun categorizeWithRetries(
        engine: LlamaNativeEngine,
        photo: UnprocessedPhoto,
        systemPrompt: String,
        onProgress: (String) -> Unit
    ): ClothingItem? {
        for (attempt in 1..MAX_CATEGORIZE_RETRIES) {
            try {
                val (rgb, w, h) = ImageUtils.decodeImageToRgb(photo.imagePath, context)

                val messages = listOf(
                    LlmEngine.Message(
                        role = "user",
                        content = "Categorize this clothing item.",
                        imageRgb = rgb,
                        imageWidth = w,
                        imageHeight = h
                    )
                )

                val response = StringBuilder()
                engine.generate(messages, maxTokens = CATEGORIZE_MAX_TOKENS).collect { token ->
                    response.append(token)
                }

                val parsed = parseCategorizationResponse(response.toString(), photo.imagePath)
                if (parsed != null) return parsed

                if (attempt < MAX_CATEGORIZE_RETRIES) {
                    onProgress("  ~ Retry ${attempt + 1}/$MAX_CATEGORIZE_RETRIES (bad JSON)")
                    try { engine.resetContext() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Categorization attempt $attempt failed", e)
                if (attempt < MAX_CATEGORIZE_RETRIES) {
                    onProgress("  ~ Retry ${attempt + 1}/$MAX_CATEGORIZE_RETRIES (${e.message})")
                    try { engine.resetContext() } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun parseCategorizationResponse(response: String, imagePath: String): ClothingItem? {
        try {
            // Try to extract JSON from the response (may have markdown wrapping)
            val jsonStr = extractJson(response) ?: return null
            val json = JSONObject(jsonStr)

            val type = json.optString("type", "").lowercase().trim()
            if (type == "not_clothing") {
                val desc = json.optString("description", "unknown object")
                Log.d(TAG, "VLM rejected as not clothing: $desc")
                return REJECTION_MARKER // Special marker to indicate intentional skip
            }
            if (type !in WardrobeDatabase.CLOTHING_TYPES) {
                Log.w(TAG, "Unknown clothing type: $type")
                return null
            }

            return ClothingItem(
                imagePath = imagePath,
                color = json.optString("color", "unknown").trim(),
                pattern = json.optString("pattern", "solid").trim(),
                fabric = json.optString("fabric", "unknown").trim(),
                type = type,
                formality = json.optInt("formality", 3).coerceIn(1, 5),
                description = json.optString("description", "").trim(),
                createdAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse categorization: ${response.take(200)}", e)
            return null
        }
    }

    // ── Outfit generation ───────────────────────────────────

    /**
     * Generate 3 outfit suggestions using a multi-step pipeline:
     * 1. Weather pre-filter (LLM advises what to exclude/include)
     * 2. Formality filter (±0.5 from target)
     * 3. Max items cap (5/type cold, 10/type hot)
     * 4. Favorites-biased randomization
     * 5. LLM generates 3 outfit combinations
     *
     * Results are cached so "Try Again" can re-randomize without re-filtering.
     */
    suspend fun generateOutfits(
        occasion: String,
        formality: Int,
        userRequest: String?,
        gender: String,
        useFahrenheit: Boolean
    ): List<OutfitSuggestion> {
        val engine = brainEngine ?: return emptyList()
        if (!engine.isLoaded) return emptyList()

        // Step 1: Get weather
        val weather = weatherService.getCurrentWeather()
        val weatherText = weather?.formatForPrompt(useFahrenheit) ?: "Weather unknown"
        val constraints = weather?.getWeatherConstraints()
        val isHot = constraints?.isHot ?: true

        // Step 2: Get available items (cooldown-filtered)
        var items = db.getAvailableItems()
        if (items.isEmpty()) {
            Log.d(TAG, "No items available for outfit generation")
            return emptyList()
        }

        // Step 3: Apply weather constraints (exclude inappropriate items)
        if (constraints != null) {
            items = items.filter { item ->
                when {
                    constraints.avoidCoats && item.type == "coats" -> false
                    constraints.avoidJumpers && item.type in listOf("jumpers", "hoodies") -> false
                    else -> true
                }
            }
        }

        // Step 4: Formality filter — keep items within ±0.5 of target
        val formalityTarget = formality.toFloat()
        items = items.filter { item ->
            val diff = kotlin.math.abs(item.formality.toFloat() - formalityTarget)
            diff <= FORMALITY_RANGE + 0.01f  // small epsilon for float comparison
        }
        Log.d(TAG, "After formality filter (target=$formality ±$FORMALITY_RANGE): ${items.size} items")

        // If formality filter is too aggressive, widen to ±1.5
        if (items.size < 3) {
            Log.d(TAG, "Too few items after strict formality filter, widening to ±1.5")
            items = db.getAvailableItems().filter { item ->
                if (constraints != null) {
                    when {
                        constraints.avoidCoats && item.type == "coats" -> false
                        constraints.avoidJumpers && item.type in listOf("jumpers", "hoodies") -> false
                        else -> true
                    }
                } else true
            }.filter { item ->
                kotlin.math.abs(item.formality.toFloat() - formalityTarget) <= 1.5f
            }
        }

        if (items.isEmpty()) {
            Log.d(TAG, "No items after filtering")
            return emptyList()
        }

        // Cache the pre-filtered items for "Try Again"
        cachedPreFiltered = items
        cachedOccasion = occasion
        cachedFormality = formality
        cachedUserRequest = userRequest
        cachedGender = gender
        cachedUseFahrenheit = useFahrenheit
        cachedIsHot = isHot

        // Steps 5-8: randomize, cap, build prompt, generate
        return randomizeAndGenerate(items, occasion, formality, userRequest, gender, useFahrenheit, weatherText, isHot)
    }

    /**
     * "Try Again" — re-randomize from cached pre-filtered items and re-prompt LLM.
     * Skips weather + formality filtering.
     */
    suspend fun tryAgainOutfits(): List<OutfitSuggestion> {
        val items = cachedPreFiltered ?: return emptyList()
        val occasion = cachedOccasion ?: return emptyList()
        val formality = cachedFormality ?: return emptyList()

        val weather = weatherService.getCurrentWeather()
        val weatherText = weather?.formatForPrompt(cachedUseFahrenheit) ?: "Weather unknown"

        return randomizeAndGenerate(
            items, occasion, formality, cachedUserRequest,
            cachedGender ?: "unspecified", cachedUseFahrenheit, weatherText, cachedIsHot
        )
    }

    /**
     * Shared pipeline: favorites-biased randomization → cap → LLM generation.
     */
    private suspend fun randomizeAndGenerate(
        preFiltered: List<ClothingItem>,
        occasion: String,
        formality: Int,
        userRequest: String?,
        gender: String,
        useFahrenheit: Boolean,
        weatherText: String,
        isHot: Boolean
    ): List<OutfitSuggestion> {
        val engine = brainEngine ?: return emptyList()
        if (!engine.isLoaded) return emptyList()

        val maxPerType = if (isHot) MAX_ITEMS_HOT else MAX_ITEMS_COLD

        // Favorites-biased randomization: favorite items get 3x weight
        val favoriteIds = db.getFavoriteItemIds()
        val grouped = preFiltered.groupBy { it.type }
        val inventoryItems = grouped.flatMap { (_, typeItems) ->
            val weighted = typeItems.flatMap { item ->
                if (item.id in favoriteIds) listOf(item, item, item) else listOf(item)
            }.shuffled().distinctBy { it.id }
            weighted.take(maxPerType)
        }

        if (inventoryItems.isEmpty()) return emptyList()
        Log.d(TAG, "Inventory for LLM: ${inventoryItems.size} items (max $maxPerType/type, ${favoriteIds.size} favorites)")

        // If user has specific request, boost matching items to front
        val finalItems = if (!userRequest.isNullOrBlank()) {
            val keywords = userRequest.lowercase().split(" ").filter { it.length > 2 }
            inventoryItems.sortedByDescending { item ->
                val text = "${item.color} ${item.type} ${item.pattern} ${item.fabric} ${item.description}".lowercase()
                keywords.count { text.contains(it) }
            }
        } else inventoryItems

        // Build inventory text
        val inventoryText = finalItems.joinToString("\n") { item ->
            "${item.id}: [${item.type}] ${item.description} — ${item.color}, ${item.pattern}, ${item.fabric}, formality ${item.formality}"
        }

        // Build and fill prompt
        val styleProfile = db.getStyleDescription() ?: "No style preference established yet"
        val prompt = PromptLoader.loadAndFill(context, "wardrobe_outfit.txt", mapOf(
            "gender" to gender,
            "weather" to weatherText,
            "occasion" to occasion,
            "formality" to formality.toString(),
            "style_profile" to styleProfile,
            "user_request" to (userRequest ?: "none"),
            "inventory" to inventoryText
        )) ?: return emptyList()

        // Generate via LLM
        val messages = listOf(LlmEngine.Message("user", prompt))
        val response = StringBuilder()
        engine.generate(messages, maxTokens = OUTFIT_MAX_TOKENS, temperature = 0.8f).collect { token ->
            response.append(token)
        }

        // Parse response
        val itemMap = finalItems.associateBy { it.id }
        return parseOutfitResponse(response.toString(), itemMap)
    }

    private fun parseOutfitResponse(
        response: String,
        itemMap: Map<Long, ClothingItem>
    ): List<OutfitSuggestion> {
        try {
            val jsonStr = extractJsonArray(response) ?: return emptyList()
            val array = JSONArray(jsonStr)
            val suggestions = mutableListOf<OutfitSuggestion>()

            for (i in 0 until minOf(array.length(), 3)) {
                val obj = array.getJSONObject(i)
                val itemsObj = obj.getJSONObject("items")
                val reasoning = obj.optString("reasoning", "")

                val outfitItems = mutableMapOf<String, ClothingItem>()
                for (slot in listOf("top", "bottom", "shoes", "outer", "accessory")) {
                    if (itemsObj.has(slot) && !itemsObj.isNull(slot)) {
                        val itemId = itemsObj.optLong(slot, -1)
                        if (itemId > 0) {
                            itemMap[itemId]?.let { outfitItems[slot] = it }
                        }
                    }
                }

                // Include if we have at least one resolved item
                if (outfitItems.isNotEmpty()) {
                    suggestions.add(OutfitSuggestion(outfitItems, reasoning))
                }
            }

            return suggestions
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse outfit response: ${response.take(300)}", e)
            return emptyList()
        }
    }

    // ── Style tracking ──────────────────────────────────────

    /**
     * Record that the user picked an outfit. Marks items as worn and updates style.
     */
    fun recordChoice(outfit: OutfitSuggestion, occasion: String, weatherSummary: String) {
        // Save the outfit
        val slotMap = outfit.items.mapValues { it.value.id }
        val outfitId = db.saveOutfit(occasion, weatherSummary, slotMap)

        // Mark all items as worn
        for (item in outfit.items.values) {
            db.markWorn(item.id)
        }

        // Update choice count
        val count = db.getChoiceCount() + 1
        db.setStyleValue("total_choices", count.toString())

        Log.d(TAG, "Recorded outfit choice #$count (outfit $outfitId)")
    }

    fun recordRejection() {
        val count = db.getRejectionCount() + 1
        db.setStyleValue("total_rejections", count.toString())
        Log.d(TAG, "Recorded rejection #$count")
    }

    /**
     * Update the user's style description using the brain LLM.
     * Called periodically (every STYLE_UPDATE_INTERVAL choices).
     */
    suspend fun maybeUpdateStyleProfile() {
        val choices = db.getChoiceCount()
        if (choices < STYLE_UPDATE_INTERVAL) return
        if (choices % STYLE_UPDATE_INTERVAL != 0) return

        val engine = brainEngine ?: return
        if (!engine.isLoaded) return

        // Get recent favorites and chosen outfits
        val favorites = db.getFavoriteOutfits()
        val recent = db.getRecentOutfits(10)
        val currentStyle = db.getStyleDescription() ?: "No style established"

        val outfitSummary = recent.joinToString("\n") { outfit ->
            val items = outfit.items.values.joinToString(", ") { "${it.color} ${it.type}" }
            "- ${outfit.occasion}: $items${if (outfit.isFavorite) " (favorited)" else ""}"
        }

        val prompt = """Based on these recent outfit choices, describe this person's style in 2-3 sentences.
Focus on: preferred colors, formality level, recurring patterns, overall aesthetic.

Current style description: $currentStyle

Recent outfit choices:
$outfitSummary

Rejections: ${db.getRejectionCount()} times (user didn't like any of the 3 suggestions)

Updated style description:"""

        val messages = listOf(LlmEngine.Message("user", prompt))
        val response = StringBuilder()
        engine.generate(messages, maxTokens = 256).collect { token ->
            response.append(token)
        }

        val newStyle = response.toString().trim()
        if (newStyle.isNotBlank() && newStyle.length > 10) {
            db.setStyleValue("style_description", newStyle)
            Log.d(TAG, "Updated style profile: ${newStyle.take(100)}")
        }
    }

    // ── Favorites ────────────────────────────────────────────

    fun toggleFavorite(outfitId: Long) = db.toggleFavorite(outfitId)
    fun getFavorites(): List<SavedOutfit> = db.getFavoriteOutfits()

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Extract a JSON object from a response that may have surrounding text/markdown.
     */
    private fun extractJson(text: String): String? {
        // Try to find JSON in markdown code block
        val codeBlock = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*\\n?```").find(text)
        if (codeBlock != null) return codeBlock.groupValues[1]

        // Try to find bare JSON object
        val bare = Regex("\\{[\\s\\S]*\\}").find(text)
        if (bare != null) {
            val candidate = bare.value
            // Validate it's parseable
            return try {
                JSONObject(candidate)
                candidate
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun extractJsonArray(text: String): String? {
        val codeBlock = Regex("```(?:json)?\\s*\\n?(\\[[\\s\\S]*?\\])\\s*\\n?```").find(text)
        if (codeBlock != null) return codeBlock.groupValues[1]

        val bare = Regex("\\[[\\s\\S]*\\]").find(text)
        if (bare != null) {
            return try {
                JSONArray(bare.value)
                bare.value
            } catch (_: Exception) { null }
        }
        return null
    }

    // ── ScreenModule ────────────────────────────────────────

    @Composable
    override fun Screen(onBack: () -> Unit) {
        // Delegated to WardrobeScreen in ui/wardrobe/ — wired in MainActivity
    }
}
