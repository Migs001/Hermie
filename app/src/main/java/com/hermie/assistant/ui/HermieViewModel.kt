package com.hermie.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermie.assistant.data.ChatMessage
import com.hermie.assistant.data.Conversation
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.llm.*
import com.hermie.llamacpp.LlamaCpp
import com.hermie.assistant.modules.ModuleRegistry
import com.hermie.assistant.modules.screentime.ScreenTimeModule
import com.hermie.assistant.modules.screentime.ScreenTimeTracker
import com.hermie.assistant.modules.tasks.TaskManager
import com.hermie.assistant.modules.tasks.TaskStore
import com.hermie.assistant.modules.tasks.TaskScheduler
import com.hermie.assistant.modules.tasks.Task
import com.hermie.assistant.modules.tasks.TaskStatus
import com.hermie.assistant.modules.tasks.TaskArtifact
import com.hermie.assistant.modules.dnd.SmartDndModule
import com.hermie.assistant.modules.memory.MemoryConfig
import com.hermie.assistant.modules.memory.MemoryContext
import com.hermie.assistant.modules.memory.MemoryModule
import com.hermie.assistant.modules.wardrobe.OutfitSuggestion
import com.hermie.assistant.modules.wardrobe.WardrobeModule
import com.hermie.assistant.modules.tools.*
import com.hermie.assistant.service.HermieBackgroundService
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.voice.PiperTtsEngine
import com.hermie.assistant.voice.SpeechManager
import com.hermie.assistant.voice.SherpaOnnxSttEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HermieViewModel(application: Application) : AndroidViewModel(application) {

    val settings = HermieSettings(application)
    val modelManager = ModelManager(application, settings)
    val moduleRegistry = ModuleRegistry(application)
    private val conversationStore = com.hermie.assistant.data.ConversationStore(application)

    private val llamaEngine = LlamaNativeEngine(application)
    private val engine: LlmEngine = llamaEngine
    val mindEngine = MindLlmEngine(application)
    private val embeddingEngine = EmbeddingEngine(application)
    val memoryModule = MemoryModule()
    val wardrobeModule = WardrobeModule()
    val studyModule = com.hermie.assistant.modules.study.StudyModule()
    private val thermalMonitor = com.hermie.assistant.util.ThermalMonitor(application)
    private val taskStore = TaskStore(application)
    private val taskScheduler = TaskScheduler(application)
    val taskManager = TaskManager(
        engine = engine,
        context = application,
        moduleRegistry = moduleRegistry,
        onTaskMutated = { task ->
            taskStore.save(task)
            // Update scheduled count whenever a task changes status
            updateScheduledCount()
        }
    )

    // Speech & TTS
    private val speechManager = SpeechManager(application)
    val whisperStt = SherpaOnnxSttEngine(application)
    val ttsEngine = PiperTtsEngine()

    // ── UI State ────────────────────────────────────────────

    private val _currentScreen = MutableStateFlow("home")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    private val _mascotState = MutableStateFlow(MascotState(MascotMood.IDLE))
    val mascotState: StateFlow<MascotState> = _mascotState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** True while the brain KV-cache is being reset and history prefilled on chat switch. */
    private val _isReplayingContext = MutableStateFlow(false)
    val isReplayingContext: StateFlow<Boolean> = _isReplayingContext.asStateFlow()

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _isDeskCaddyMode = MutableStateFlow(false)
    val isDeskCaddyMode: StateFlow<Boolean> = _isDeskCaddyMode.asStateFlow()

    /** Whether the user was already in voice mode before Desk Caddy was toggled on.
     *  Used to restore the correct mode when caddy is toggled off. */
    private var voiceWasOnBeforeCaddy: Boolean = false

    private val _isBackgroundRunning = MutableStateFlow(false)
    val isBackgroundRunning: StateFlow<Boolean> = _isBackgroundRunning.asStateFlow()

    // ── Mind Model Mode ─────────────────────────────────────
    //
    // The Mind (SLM) model can only do one job at a time. Its role is determined by:
    //   DnD OFF → DRIP_ATOMIZER (default: background fact extraction from raw messages)
    //   DnD ON  → NOTIFICATION_FILTER (filter incoming notifications via rules)
    //
    // When Mind is in NOTIFICATION_FILTER mode:
    //   - Drip atomization is paused
    //   - If a notification needs Mind evaluation during brain generation,
    //     it's queued and processed after brain finishes
    //   - The embedding model still runs for basic semantic retrieval
    //
    enum class MindMode { DRIP_ATOMIZER, NOTIFICATION_FILTER }

    private val _mindMode = MutableStateFlow(MindMode.DRIP_ATOMIZER)
    val mindMode: StateFlow<MindMode> = _mindMode.asStateFlow()

    /** Queued notifications waiting for Mind model to be free */
    private val _pendingNotifFilter = MutableStateFlow(false)
    val pendingNotifFilter: StateFlow<Boolean> = _pendingNotifFilter.asStateFlow()

    /** Whether input is temporarily greyed out while Mind checks notification filters */
    private val _isCheckingFilters = MutableStateFlow(false)
    val isCheckingFilters: StateFlow<Boolean> = _isCheckingFilters.asStateFlow()

    /** Timer job for unloading brain model after app is backgrounded */
    private var backgroundUnloadJob: kotlinx.coroutines.Job? = null
    private val BACKGROUND_UNLOAD_DELAY_MS = 60_000L  // 1 minute

    val speechState = speechManager.state
    val whisperState = whisperStt.state
    val partialTranscript = MutableStateFlow("")

    val tasks: StateFlow<List<Task>> = taskManager.tasks
    val currentTask = taskManager.currentTask
    val executionStatus = taskManager.executionStatus

    /** True while a task is actively being executed — gates chat/sleep/study/voice. */
    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning: StateFlow<Boolean> = _isTaskRunning.asStateFlow()

    /**
     * True while the Brain is being restored to chat mode after a task finishes.
     * Blocks chat input until the system prompt + KV cache are fully reset.
     */
    private val _isBrainRestoring = MutableStateFlow(false)
    val isBrainRestoring: StateFlow<Boolean> = _isBrainRestoring.asStateFlow()

    /** The coroutine Job for the currently-running task — cancellable for pause. */
    private var taskJob: kotlinx.coroutines.Job? = null

    /** Number of tasks with SCHEDULED status (for the upcoming tasks pill). */
    private val _scheduledTaskCount = MutableStateFlow(0)
    val scheduledTaskCount: StateFlow<Int> = _scheduledTaskCount.asStateFlow()

    // System prompt
    private var systemPrompt: String = ""

    init {
        loadSystemPrompt()
        initializeModules()
        setupSpeechCallbacks()

        // Load persisted tasks from disk and seed TaskManager
        viewModelScope.launch {
            val savedTasks = taskStore.loadAll()
            if (savedTasks.isNotEmpty()) {
                taskManager.setTasks(savedTasks)
                updateScheduledCount()
                Log.d(TAG, "Loaded ${savedTasks.size} tasks from disk")
            }
        }

        // Load persisted conversations from disk, or start fresh for first-run users
        viewModelScope.launch {
            val loaded = conversationStore.loadAll()
            if (loaded.isNotEmpty()) {
                _conversations.value = loaded
                _currentConversationId.value = loaded.first().id
                _messages.value = loaded.first().messages
                replayBrainContext()  // no-op if model not yet loaded; replayed again after load
            } else {
                newConversation()
            }
        }

        // If the native engine already has a model loaded (e.g., Activity recreated
        // but process survived), just mark us as ready immediately and sync the path
        if (engine.isLoaded) {
            Log.d(TAG, "Engine already has model loaded (process survived) — marking ready")
            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
            // Sync the wrapper's path tracker so it won't try to reload the same model
            llamaEngine.syncLoadedPath(modelManager.modelPath)
        }

        // Auto-load model: observe active model changes so it loads
        // immediately at startup OR as soon as onboarding finishes downloading.
        // This is the SINGLE loading path — all other methods (switchModel, loadActiveModel)
        // just call setActiveModel() and let this collect handle the actual loading.
        viewModelScope.launch(Dispatchers.IO) {
            modelManager.activeModel.collect { model ->
                Log.d(TAG, "activeModel collect: model=${model?.displayName}, path=${if (model != null) modelManager.modelPath else "N/A"}")
                if (model == null) {
                    // No active model — try to find any downloaded brain model
                    val fallback = ModelManager.BASE_BRAIN_MODELS.firstOrNull {
                        modelManager.isDownloaded(it)
                    }
                    if (fallback != null) {
                        Log.d(TAG, "No active model set, falling back to downloaded: ${fallback.displayName}")
                        modelManager.setActiveModel(fallback)
                    } else {
                        Log.w(TAG, "No brain models downloaded — user needs to download one")
                    }
                    return@collect
                }
                if (model != null) {
                    // Safety: only load models from BASE_BRAIN_MODELS (not legacy/unsupported)
                    val isSupported = ModelManager.BASE_BRAIN_MODELS.any { it.id == model.id }
                    if (!isSupported) {
                        Log.w(TAG, "Skipping unsupported model: ${model.displayName}")
                        _mascotState.value = MascotState(
                            MascotMood.CONCERNED,
                            bubbleText = "Unsupported model — please switch"
                        )
                        // Try to fall back to a supported downloaded model
                        val fallback = ModelManager.BASE_BRAIN_MODELS.firstOrNull {
                            modelManager.isDownloaded(it)
                        }
                        if (fallback != null) {
                            Log.d(TAG, "Falling back to ${fallback.displayName}")
                            modelManager.setActiveModel(fallback)
                        }
                        return@collect
                    }

                    val path = modelManager.modelPath
                    val model = modelManager.activeModel.value
                    if (path.isNotBlank()) {
                        try {
                            Log.d(TAG, "Auto-loading model: ${model?.displayName}")
                            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Loading...")
                            val turbo = model?.useTurboCache ?: false
                            val ctxSize = model?.contextSize ?: 8192
                            memoryModule.isDripSuppressed = true  // Block drip during model load
                            engine.loadModel(path, turbo, ctxSize)
                            settings.setModelPath(path)
                            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
                            // Replay persisted conversation history into the fresh KV cache
                            if (_messages.value.isNotEmpty()) replayBrainContext()
                            // After brain loads, auto-load SLM + embedding if available
                            autoLoadSupportModels()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-load model", e)
                            _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model error")
                        } finally {
                            if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                                memoryModule.isDripSuppressed = false
                            }
                        }
                    }
                }
            }
        }

        // SLM + embedding auto-load is handled by autoLoadSupportModels()
        // which is called after the brain model finishes loading (in the activeModel collector above).

        // Sync DnD state → if DnD was already on, switch Mind to notification filter mode
        viewModelScope.launch {
            // Small delay to let modules initialize
            kotlinx.coroutines.delay(1000)
            if (isDndEnabled()) {
                switchMindMode(MindMode.NOTIFICATION_FILTER)
            }
        }

        // TTS and STT are loaded on demand when voice mode is activated
        // to save memory at startup. See startVoiceChat() and toggleVoiceMode().

        // Auto-start background service (needed for screen time tracking etc.)
        viewModelScope.launch {
            if (!_isBackgroundRunning.value) {
                toggleBackgroundService(true)
            }
        }
    }

    // ── Module initialization ───────────────────────────────

    private fun initializeModules() {
        viewModelScope.launch {
            val screenTimeModule = ScreenTimeModule()
            screenTimeModule.setMindEngine(mindEngine)
            moduleRegistry.register(screenTimeModule)
            // Wire mind engine to the background service for lifecycle management
            HermieBackgroundService.mindEngine = mindEngine
            HermieBackgroundService.onGoMinimal = { onAppBackgrounded() }
            HermieBackgroundService.onGoFull = { onAppForegrounded() }

            // Tool modules for task execution
            moduleRegistry.register(AlarmModule())
            moduleRegistry.register(ReminderModule())
            moduleRegistry.register(CalendarModule())
            moduleRegistry.register(ContactsModule())
            moduleRegistry.register(IntentModule())
            moduleRegistry.register(WebSearchModule())
            moduleRegistry.register(ClipboardModule())

            // External-data tool modules (tasks mode — not chat-safe by default)
            moduleRegistry.register(WeatherModule())
            moduleRegistry.register(OverpassModule())
            moduleRegistry.register(DuckDuckGoModule())
            moduleRegistry.register(WebFetchModule())
            moduleRegistry.register(ArxivModule())

            // Smart DND module — LLM-powered notification filtering
            val smartDndModule = SmartDndModule()
            smartDndModule.setLlmEngine(engine)
            smartDndModule.setMindEngine(mindEngine)
            moduleRegistry.register(smartDndModule)

            // Memory module — persistent memory across sessions
            MemoryModule.loadPrompts(getApplication())
            memoryModule.setEngines(mindEngine, engine, llamaEngine)
            memoryModule.setEmbeddingEngine(embeddingEngine)
            moduleRegistry.register(memoryModule)

            // Wardrobe module — clothing & outfit recommendations
            wardrobeModule.setEngines(engine, llamaEngine, modelManager, LlamaCpp.getInferenceEngine(getApplication()))
            moduleRegistry.register(wardrobeModule)

            // Study module — learn from PDFs & Wikipedia
            studyModule.setEngines(llamaEngine, engine, memoryModule)
            moduleRegistry.register(studyModule)

            // Wire background service
            HermieBackgroundService.moduleRegistry = moduleRegistry
            HermieBackgroundService.taskManager = taskManager
            HermieBackgroundService.canAcquireBrain = { canAcquireBrain() }
            // Embedding model loading is handled by autoLoadSupportModels() after brain loads
        }
    }

    /**
     * Auto-load SLM + embedding after brain model loads.
     * Sequential: SLM first (with settling delay), then embedding.
     * Already on IO dispatcher from the activeModel collector.
     */
    private fun autoLoadSupportModels() {
        viewModelScope.launch(Dispatchers.IO) {
            // Let memory settle after brain model load (GC, mmap pages, etc.)
            Log.d(TAG, "SLM: Brain model ready, waiting for memory to settle...")
            kotlinx.coroutines.delay(3000)

            // Don't load SLM during sleep/study mode — brain uses slot 0 exclusively
            // and MindEngine switching to slot 3 causes conflicts
            if (_isSleepMode.value || _isStudyMode.value) {
                Log.d(TAG, "SLM: Sleep/study mode active — deferring SLM load")
                return@launch
            }

            try {
                val slmModel = ModelManager.SLM_MODELS.firstOrNull()
                if (slmModel != null && modelManager.isDownloaded(slmModel)) {
                    val slmPath = modelManager.modelPathFor(ModelType.SLM)
                    if (slmPath.isNotBlank()) {
                        Log.d(TAG, "Auto-loading SLM: ${slmModel.displayName} (ctx=${slmModel.contextSize})")
                        mindEngine.loadModel(slmPath, slmModel.useTurboCache, slmModel.contextSize)
                        Log.d(TAG, "SLM loaded and ready (drip atomizer mode)")
                    }
                } else {
                    Log.d(TAG, "SLM not downloaded — skipping mind engine init")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-load SLM", e)
            }

            // Load embedding engine after SLM
            loadEmbeddingModelSync()
        }
    }

    /**
     * Load the MiniLM embedding model if it's been downloaded.
     * Launches on IO thread.
     */
    private fun loadEmbeddingModel() {
        viewModelScope.launch(Dispatchers.IO) {
            loadEmbeddingModelSync()
        }
    }

    /** Load embedding model synchronously (must be called from IO dispatcher). */
    private suspend fun loadEmbeddingModelSync() {
        val mindModel = ModelManager.MIND_MODELS.firstOrNull() ?: return
        if (!modelManager.isDownloaded(mindModel)) {
            Log.d(TAG, "Embedding model not downloaded — skipping")
            return
        }
        val mindDir = java.io.File(getApplication<Application>().filesDir, "models/mind")
        val modelPath = java.io.File(mindDir, "model.tflite").absolutePath
        val vocabPath = java.io.File(mindDir, "vocab.txt").absolutePath
        try {
            embeddingEngine.load(modelPath, vocabPath)
            Log.d(TAG, "Embedding engine loaded")
            memoryModule.embedPending()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load embedding engine", e)
        }
    }

    // ── Screen time ──────────────────────────────────────────

    fun getScreenTimeModule(): ScreenTimeModule? =
        moduleRegistry.getModule("screentime") as? ScreenTimeModule

    /** Set a screen time limit for an app with personal reason */
    fun setScreenTimeLimit(packageName: String, minutes: Int, reason: String) {
        settings.setScreenTimeTrigger(packageName, minutes)
        if (reason.isNotBlank()) {
            settings.setScreenTimeReason(packageName, reason)
        }
        getScreenTimeModule()?.reloadTriggers()

        // Ensure background service is running so triggers are checked
        if (!_isBackgroundRunning.value) {
            toggleBackgroundService(true)
        }
    }

    /** Remove a screen time limit for an app */
    fun removeScreenTimeLimit(packageName: String) {
        settings.removeScreenTimeTrigger(packageName)
        getScreenTimeModule()?.reloadTriggers()
    }

    /** Request usage access — opens system Settings directly */
    fun requestUsageAccessPermission() {
        ScreenTimeTracker.openUsageAccessSettings(getApplication())
    }

    /** Request overlay permission — opens system Settings directly for our app */
    fun requestOverlayPermission() {
        ScreenTimeTracker.openOverlayPermissionSettings(getApplication())
    }

    /** Check if screen time permission is granted */
    fun hasScreenTimePermission(): Boolean =
        getScreenTimeModule()?.hasPermission() ?: false

    /** Check if overlay (draw over other apps) permission is granted */
    fun hasOverlayPermission(): Boolean =
        ScreenTimeTracker.hasOverlayPermission(getApplication())

    // ── Smart DND ────────────────────────────────────────────

    fun getSmartDndModule(): SmartDndModule? =
        moduleRegistry.getModule("smart_dnd") as? SmartDndModule

    fun toggleDnd(enable: Boolean) {
        getSmartDndModule()?.toggleDnd(enable)
        if (enable) {
            switchMindMode(MindMode.NOTIFICATION_FILTER)
        } else {
            switchMindMode(MindMode.DRIP_ATOMIZER)
        }
    }

    fun isDndEnabled(): Boolean = getSmartDndModule()?.dndEnabled?.value ?: false

    /**
     * Switch the Mind model's role between memory classification and notification filtering.
     * The SLM's system prompt is swapped to match the new role.
     */
    private fun switchMindMode(mode: MindMode) {
        if (_mindMode.value == mode) return
        _mindMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                MindMode.DRIP_ATOMIZER -> {
                    Log.d(TAG, "Mind mode → DRIP_ATOMIZER")
                }
                MindMode.NOTIFICATION_FILTER -> {
                    Log.d(TAG, "Mind mode → NOTIFICATION_FILTER")
                }
            }
        }
    }

    /**
     * Build a concise DnD filter prompt for the Mind model (must fit in ~100 tokens).
     * Max 5 rules compressed into key-value pairs.
     */
    private fun buildDndFilterPrompt(rules: List<com.hermie.assistant.modules.dnd.DndFilterRule>): String {
        val sb = StringBuilder()
        sb.append("Filter notifications. Output JSON: {\"action\":\"ALERT\"|\"SILENCE\",\"reason\":str}\n")
        sb.append("Rules (priority order):\n")
        rules.take(5).forEachIndexed { i, rule ->
            val type = when (rule.ruleType) {
                com.hermie.assistant.modules.dnd.RuleType.ALLOW_CONTACT -> "ALLOW contact"
                com.hermie.assistant.modules.dnd.RuleType.ALLOW_APP -> "ALLOW app"
                com.hermie.assistant.modules.dnd.RuleType.BLOCK_APP -> "BLOCK app"
                com.hermie.assistant.modules.dnd.RuleType.CUSTOM_LLM -> "EVAL"
            }
            sb.append("${i+1}. $type: ${rule.description.take(40)}\n")
        }
        sb.append("Default: SILENCE")
        return sb.toString()
    }

    fun hasDndPolicyAccess(): Boolean =
        getSmartDndModule()?.isDndPolicyAccessGranted() ?: false

    fun requestDndPolicyAccess() {
        getSmartDndModule()?.openDndPolicySettings()
    }

    // ── Chat ────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        if (_isSleepMode.value || _isStudyMode.value || _isWaking.value || _isReplayingContext.value) return  // Brain is busy
        if (_isTaskRunning.value || _isBrainRestoring.value) return  // Task mode has the Brain

        // Add user message (mindDebug will be updated after SLM classification)
        val userMsgId = "user_${System.currentTimeMillis()}"
        val userMsg = ChatMessage(id = userMsgId, role = "user", content = text)
        _messages.value = _messages.value + userMsg
        _mascotState.value = MascotState(MascotMood.THINKING)
        _isGenerating.value = true

        // Suppress drip atomization synchronously — prevents SLM slot conflict if
        // the background service fires dripAtomize before isBrainBusy is set inside the coroutine
        memoryModule.lastUserMessageAt = System.currentTimeMillis()
        memoryModule.isDripSuppressed = true

        // Unique ID for THIS generation's assistant message
        val responseId = "gen_${System.currentTimeMillis()}"

        viewModelScope.launch {
            // Start TTS queue processor if voice is enabled
            val shouldSpeak = _isVoiceMode.value && settings.voiceEnabled && ttsEngine.isReady
            Log.d(TAG, "shouldSpeak=$shouldSpeak (voiceMode=${_isVoiceMode.value}, voiceEnabled=${settings.voiceEnabled}, ttsReady=${ttsEngine.isReady}, ttsState=${ttsEngine.state.value})")
            if (shouldSpeak) ttsEngine.resetStream()
            val ttsJob = if (shouldSpeak) {
                viewModelScope.launch {
                    ttsEngine.processQueue { _isGenerating.value }
                }
            } else null

            try {
                var conversationMessages = buildLlmMessages()

                // ── Memory Pipeline (v4 — no SLM in chat path) ──
                // 1. Capture raw message for background drip atomization
                // 2. Run retrieval gate (linguistic + embedding probes)
                // 3. If gate fires, retrieve context and inject into LLM messages
                //
                // When DnD is ON, Mind is in NOTIFICATION_FILTER mode — skip memory
                // retrieval entirely. The embedding model still provides basic semantic
                // retrieval if available.
                var memoryEmotion: String? = null
                val isDndActive = _mindMode.value == MindMode.NOTIFICATION_FILTER

                // Always capture raw message for drip atomization (background SLM processing)
                if (memoryModule.isActive) {
                    memoryModule.rawMessageCapture(text)
                }

                if (isDndActive) {
                    // DnD mode: skip retrieval gate, show status in debug
                    Log.d(TAG, "DnD active — skipping memory retrieval")
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfFirst { it.id == userMsgId }
                    if (idx >= 0) {
                        msgs[idx] = msgs[idx].copy(
                            mindDebug = "┌ Mind Model\n│ Mode: Notification Filter\n│ Retrieval: skipped\n└"
                        )
                        _messages.value = msgs
                    }

                    // Still use embedding model for basic semantic retrieval if available
                    if (memoryModule.isActive && embeddingEngine.isLoaded) {
                        try {
                            val nodes = memoryModule.retrieveByEmbedding(text)
                            if (nodes.isNotEmpty()) {
                                val memoryText = nodes.joinToString("\n") { "- ${it.fact}" }
                                val augmented = conversationMessages.toMutableList()
                                val lastIdx = augmented.indexOfLast { it.role == "user" }
                                if (lastIdx >= 0) {
                                    val original = augmented[lastIdx].content
                                    augmented[lastIdx] = LlmEngine.Message("user",
                                        "[MEMORY]\n$memoryText\n\n[USER MESSAGE]\n$original")
                                }
                                conversationMessages = augmented
                                Log.d(TAG, "DnD mode: injected ${nodes.size} nodes via embedding")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Embedding retrieval failed in DnD mode", e)
                        }
                    }
                } else if (memoryModule.isActive) {
                    // Normal mode: 3-stage retrieval gate (no SLM in realtime)
                    try {
                        val memCtx = memoryModule.processUtterance(text)

                        // Inject memory context into LLM messages
                        val memoryText = memCtx.formatForLlm()
                        Log.d(TAG, "Memory context for LLM: ${memoryText?.take(200) ?: "(none)"}")
                        if (memoryText != null) {
                            val augmented = conversationMessages.toMutableList()
                            val lastIdx = augmented.indexOfLast { it.role == "user" }
                            if (lastIdx >= 0) {
                                val original = augmented[lastIdx].content
                                augmented[lastIdx] = LlmEngine.Message("user",
                                    "$memoryText\n\n[USER MESSAGE]\n$original")
                            }
                            conversationMessages = augmented
                        }

                        // Attach debug info to user message for the expandable dropdown
                        val debugInfo = buildMindDebug(memCtx)
                        val msgs = _messages.value.toMutableList()
                        val idx = msgs.indexOfFirst { it.id == userMsgId }
                        if (idx >= 0) {
                            msgs[idx] = msgs[idx].copy(mindDebug = debugInfo)
                            _messages.value = msgs
                        }

                        Log.d(TAG, "Memory pipeline: gate=${memCtx.gateSignals}, " +
                            "nodes=${memCtx.retrievedNodes.size}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Memory pipeline failed, continuing without it", e)
                        val msgs = _messages.value.toMutableList()
                        val idx = msgs.indexOfFirst { it.id == userMsgId }
                        if (idx >= 0) {
                            msgs[idx] = msgs[idx].copy(
                                mindDebug = "┌ Memory\n│ Error: ${e.message}\n└"
                            )
                            _messages.value = msgs
                        }
                    }
                } else {
                    // No memory pipeline available — show status in debug dropdown
                    Log.d(TAG, "Memory pipeline skipped: memoryActive=${memoryModule.isActive}, dnd=$isDndActive")
                    val msgs = _messages.value.toMutableList()
                    val idx = msgs.indexOfFirst { it.id == userMsgId }
                    if (idx >= 0) {
                        msgs[idx] = msgs[idx].copy(
                            mindDebug = "┌ Memory\n│ DB: ${if (memoryModule.isActive) "active" else "inactive"}\n│ Status: idle\n└"
                        )
                        _messages.value = msgs
                    }
                }

                val response = StringBuilder()
                val thinking = StringBuilder()
                var emotion: String? = memoryEmotion

                memoryModule.isBrainBusy = true
                engine.generate(conversationMessages).collect { token ->
                    // Separate thinking tokens (prefixed by THINK_PREFIX) from response
                    val isThinking = token.startsWith(LlamaNativeEngine.THINK_PREFIX)
                    if (isThinking) {
                        val thinkToken = token.removePrefix(LlamaNativeEngine.THINK_PREFIX)
                        thinking.append(thinkToken)
                    } else {
                        response.append(token)

                        // Feed token to TTS for sentence-boundary streaming
                        if (shouldSpeak) ttsEngine.feedToken(token)
                    }

                    // Parse emotion from response
                    if (emotion == null && !isThinking) {
                        val emotionMatch = Regex("<emotion>(\\w+)</emotion>")
                            .find(response.toString())
                        emotion = emotionMatch?.groupValues?.get(1)
                        if (emotion != null) {
                            _mascotState.value = MascotState(
                                mood = emotionToMood(emotion!!)
                            )
                        }
                    }

                    // Update THIS generation's assistant message (streaming)
                    val currentMessages = _messages.value.toMutableList()
                    val existingIdx = currentMessages.indexOfFirst { it.id == responseId }
                    val thinkingText = thinking.toString().trim().ifEmpty { null }
                    val assistantMsg = ChatMessage(
                        id = responseId,
                        role = "assistant",
                        content = if (response.isEmpty() && thinkingText != null)
                            "Thinking..." else response.toString(),
                        emotion = emotion,
                        thinkingContent = thinkingText
                    )
                    if (existingIdx >= 0) {
                        currentMessages[existingIdx] = assistantMsg
                    } else {
                        currentMessages.add(assistantMsg)
                    }
                    _messages.value = currentMessages
                }

                // Signal TTS that generation is done, flush remaining text
                if (shouldSpeak) ttsEngine.feedEnd()

                // Clean up the displayed message — strip emotion/tool tags
                val rawResponse = response.toString()
                val cleanedContent = rawResponse
                    .replace(Regex("<emotion>\\w+</emotion>"), "")
                    .replace(Regex("<tool>.*?</tool>"), "")
                    .trim()
                val finalThinking = thinking.toString().trim().ifEmpty { null }
                if (cleanedContent != rawResponse || finalThinking != null) {
                    val currentMessages = _messages.value.toMutableList()
                    val existingIdx = currentMessages.indexOfFirst { it.id == responseId }
                    if (existingIdx >= 0) {
                        currentMessages[existingIdx] = currentMessages[existingIdx].copy(
                            content = cleanedContent.ifBlank {
                                if (finalThinking != null) "Hmm, let me think about that..." else "Let me check..."
                            },
                            thinkingContent = finalThinking
                        )
                        _messages.value = currentMessages
                    }
                }
                // Reinforce edge embeddings based on what the Brain actually used
                memoryModule.reinforceFromResponse(cleanedContent)
                // Parse and execute any tool calls
                parseAndExecuteTools(rawResponse)

            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Oops...")
            } finally {
                memoryModule.isBrainBusy = false
                _isGenerating.value = false
                // Lift drip suppression only when not in voice/deskCaddy/sleep mode
                // (those modes maintain their own suppression independently)
                if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                    memoryModule.isDripSuppressed = false
                }
                // Wait for TTS to finish speaking remaining sentences
                ttsJob?.join()
                if (_mascotState.value.mood == MascotMood.THINKING) {
                    _mascotState.value = MascotState(MascotMood.HAPPY)
                }

                // Auto re-engage mic in voice mode after response completes
                if (_isVoiceMode.value) {
                    reEngageVoice()
                }

                // NOTE: Consolidation deferred to sleep mode only.
                // Running it between messages destroys the KV cache (requires
                // swapping the system prompt + resetting context), which wipes
                // the entire conversation history and makes chat unusable.
                // Buffer facts are still collected via SLM classification and
                // will be promoted to graph nodes during the next sleep cycle.
            }
        }
    }

    fun stopGeneration() {
        engine.stopGeneration()
        ttsEngine.stop()
        _isGenerating.value = false
    }

    // ── TTS ───────────────────────────────────────────────────

    private suspend fun initializeTts() {
        val voiceModel = ModelManager.VOICE_MODELS.firstOrNull()
        if (voiceModel == null) {
            Log.w(TAG, "TTS init: No voice models defined in VOICE_MODELS")
            return
        }
        if (!modelManager.isDownloaded(voiceModel)) {
            Log.w(TAG, "TTS init: Voice model '${voiceModel.displayName}' not downloaded yet. " +
                    "User needs to download it from Settings or run downloadAndInitTts()")
            return
        }
        val voiceDir = java.io.File(getApplication<Application>().filesDir, "models/voice")
        Log.d(TAG, "TTS init: Initializing from $voiceDir, files: ${voiceDir.listFiles()?.map { it.name }}")
        ttsEngine.initialize(voiceDir)
        Log.d(TAG, "TTS init: state=${ttsEngine.state.value}, isReady=${ttsEngine.isReady}, error=${ttsEngine.error.value}")
    }

    /** Download voice model from settings, then initialize TTS */
    /**
     * Auto-download SLM (memory classifier) and embedding model if not already present.
     * Runs in viewModelScope so it survives navigation away from onboarding.
     * Queued after voice model download to avoid bandwidth contention.
     */
    private fun downloadAndInitSupportModels() {
        viewModelScope.launch(Dispatchers.IO) {
            // Wait for voice model download to finish first (if running)
            // Small delay to let TTS download get started
            kotlinx.coroutines.delay(2000)

            // Download SLM if needed
            val slmModel = ModelManager.SLM_MODELS.firstOrNull()
            if (slmModel != null && !modelManager.isDownloaded(slmModel)) {
                Log.d(TAG, "Auto-downloading SLM: ${slmModel.displayName}")
                val success = modelManager.downloadModel(slmModel)
                if (success) {
                    Log.d(TAG, "SLM downloaded, auto-loading...")
                    val slmPath = modelManager.modelPathFor(ModelType.SLM)
                    if (slmPath.isNotBlank() && engine.isLoaded) {
                        try {
                            mindEngine.loadModel(slmPath, slmModel.useTurboCache, slmModel.contextSize)
                            Log.d(TAG, "SLM auto-loaded after download (drip atomizer mode)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-load SLM after download", e)
                        }
                    }
                }
            }

            // Download embedding model if needed
            val embModel = ModelManager.MIND_MODELS.firstOrNull()
            if (embModel != null && !modelManager.isDownloaded(embModel)) {
                Log.d(TAG, "Auto-downloading embedding model: ${embModel.displayName}")
                val success = modelManager.downloadModel(embModel)
                if (success) {
                    Log.d(TAG, "Embedding model downloaded, auto-loading...")
                    loadEmbeddingModel()
                }
            }
        }
    }

    fun downloadAndInitTts() {
        viewModelScope.launch {
            val voiceModel = ModelManager.VOICE_MODELS.firstOrNull() ?: return@launch
            if (!modelManager.isDownloaded(voiceModel)) {
                val success = modelManager.downloadModel(voiceModel)
                if (!success) {
                    Log.w(TAG, "Voice model download failed")
                    return@launch
                }
            }
            val voiceDir = java.io.File(getApplication<Application>().filesDir, "models/voice")
            ttsEngine.initialize(voiceDir)
        }
    }

    private fun buildLlmMessages(): List<LlmEngine.Message> {
        val msgs = mutableListOf<LlmEngine.Message>()

        // System prompt with chat-mode curated tool list (not full task inventory)
        val toolDefs = moduleRegistry.getToolDefinitionsForMode(ModuleRegistry.BrainMode.CHAT)
        val toolsText = if (toolDefs.isNotEmpty()) {
            "\n\nAvailable tools:\n" + toolDefs.joinToString("\n") { tool ->
                "${tool.name}: ${tool.description}"
            }
        } else ""

        msgs.add(LlmEngine.Message("system", systemPrompt + toolsText))

        // Conversation history (last 20 messages)
        _messages.value.takeLast(20).forEach { msg ->
            msgs.add(LlmEngine.Message(msg.role, msg.content))
        }

        return msgs
    }

    private suspend fun parseAndExecuteTools(response: String) {
        val toolPattern = Regex("<tool>(.*?)</tool>")
        val matches = toolPattern.findAll(response)
        val toolResults = mutableListOf<String>()

        for (match in matches) {
            val call = match.groupValues[1]
            // Parse: function.name(param="value", param2="value2")
            val funcMatch = Regex("""(\w+\.\w+)\((.*)\)""").find(call) ?: continue
            val toolName = funcMatch.groupValues[1]
            val paramsStr = funcMatch.groupValues[2]

            val params = mutableMapOf<String, String>()
            Regex("""(\w+)="([^"]*?)"""").findAll(paramsStr).forEach { pm ->
                params[pm.groupValues[1]] = pm.groupValues[2]
            }

            val result = moduleRegistry.executeTool(toolName, params)
            Log.d(TAG, "Tool $toolName → $result")

            when (result) {
                is com.hermie.assistant.modules.ToolResult.Success -> {
                    toolResults.add(result.message)
                }
                is com.hermie.assistant.modules.ToolResult.Error -> {
                    toolResults.add("Error: ${result.message}")
                }
            }
        }

        // If tools returned results, feed them back through the LLM for a natural response
        if (toolResults.isNotEmpty()) {
            val toolContext = toolResults.joinToString("\n\n")
            Log.d(TAG, "Feeding ${toolResults.size} tool result(s) back to LLM")

            // Add tool results as a hidden system-like context and generate follow-up
            val followUpId = "gen_followup_${System.currentTimeMillis()}"
            _isGenerating.value = true
            _mascotState.value = MascotState(MascotMood.THINKING)

            try {
                // Build messages with tool result context
                val msgs = buildLlmMessages().toMutableList()
                msgs.add(LlmEngine.Message("user",
                    "[TOOL RESULTS - Summarize these results naturally for the user]\n$toolContext"))

                val followUpResponse = StringBuilder()
                val shouldSpeak = _isVoiceMode.value && settings.voiceEnabled && ttsEngine.isReady
                if (shouldSpeak) ttsEngine.resetStream()
                val ttsJob = if (shouldSpeak) {
                    viewModelScope.launch { ttsEngine.processQueue { _isGenerating.value } }
                } else null

                memoryModule.isBrainBusy = true
                engine.generate(msgs).collect { token ->
                    followUpResponse.append(token)
                    if (shouldSpeak) ttsEngine.feedToken(token)

                    // Update the follow-up message (streaming)
                    val currentMessages = _messages.value.toMutableList()
                    val existingIdx = currentMessages.indexOfFirst { it.id == followUpId }
                    val cleanText = followUpResponse.toString()
                        .replace(Regex("<emotion>\\w+</emotion>"), "")
                        .replace(Regex("<tool>.*?</tool>"), "")
                        .trim()
                    val msg = ChatMessage(
                        id = followUpId,
                        role = "assistant",
                        content = cleanText
                    )
                    if (existingIdx >= 0) {
                        currentMessages[existingIdx] = msg
                    } else {
                        currentMessages.add(msg)
                    }
                    _messages.value = currentMessages
                }

                if (shouldSpeak) ttsEngine.feedEnd()
                memoryModule.isBrainBusy = false
                _isGenerating.value = false
                ttsJob?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Tool follow-up generation failed", e)
                memoryModule.isBrainBusy = false
                // Fallback: just show the raw tool results
                val fallbackMsg = ChatMessage(
                    id = followUpId,
                    role = "assistant",
                    content = toolContext
                )
                _messages.value = _messages.value + fallbackMsg
                _isGenerating.value = false
            }
        }
    }

    // ── Conversation management ─────────────────────────────

    fun newConversation() {
        saveCurrentConversation()
        val conv = Conversation()
        _conversations.value = _conversations.value + conv
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
        // Reset brain KV cache so the new chat starts fresh
        resetBrainContext()
        // New memory session so buffer entries are scoped
        memoryModule.newSession()

        // Persist the new (empty) conversation immediately
        viewModelScope.launch { conversationStore.save(conv) }

        // Archive the oldest conversation if we've exceeded the active cap
        val list = _conversations.value
        if (list.size > com.hermie.assistant.data.ConversationStore.MAX_ACTIVE_CONVERSATIONS) {
            val oldest = list.filter { it.id != conv.id }.minByOrNull { it.updatedAt }
            if (oldest != null) {
                _conversations.value = _conversations.value.filter { it.id != oldest.id }
                viewModelScope.launch { conversationStore.archive(oldest.id) }
                Log.d(TAG, "Archived oldest conversation ${oldest.id} (cap reached)")
            }
        }
    }

    fun switchConversation(id: String) {
        saveCurrentConversation()
        _currentConversationId.value = id
        val conv = _conversations.value.find { it.id == id }
        _messages.value = conv?.messages ?: emptyList()
        // Reset brain KV cache and replay the saved conversation history
        // so the model has full multi-turn context
        replayBrainContext()
    }

    private fun resetBrainContext() {
        viewModelScope.launch(Dispatchers.IO) {
            llamaEngine.resetContext()
        }
    }

    /**
     * Reset the brain KV cache and replay the current conversation's messages
     * into it, so the model retains full multi-turn context after switching chats.
     * Sets [_isReplayingContext] while in progress so [sendMessage] blocks until done.
     */
    private fun replayBrainContext() {
        _isReplayingContext.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = _messages.value.takeLast(20).map { msg ->
                    Pair(msg.role, msg.content)
                }
                llamaEngine.resetAndReplayHistory(history)
            } finally {
                _isReplayingContext.value = false
            }
        }
    }

    /**
     * Send a tiny throwaway message to warm up the engine (mmap pages, thread pool, KV cache).
     * Then reset context so nothing bleeds into real conversation.
     * Call on IO thread after model load.
     */
    private suspend fun primeBrainModel() {
        try {
            Log.d(TAG, "Priming brain model...")
            engine.generate(
                listOf(LlmEngine.Message("user", "hi")),
                maxTokens = 1
            ).collect { /* discard */ }
            // Reset so the prime doesn't bleed into real chat
            llamaEngine.resetContext()
            Log.d(TAG, "Brain model primed and ready")
        } catch (e: Exception) {
            Log.w(TAG, "Prime failed (non-fatal)", e)
        }
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        viewModelScope.launch { conversationStore.delete(id) }
        if (_currentConversationId.value == id) {
            val remaining = _conversations.value
            if (remaining.isNotEmpty()) {
                switchConversation(remaining.last().id)
            } else {
                newConversation()
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        var updated: Conversation? = null
        _conversations.value = _conversations.value.map {
            if (it.id == id) {
                val u = it.copy(title = title, updatedAt = System.currentTimeMillis())
                updated = u
                u
            } else it
        }
        updated?.let { u -> viewModelScope.launch { conversationStore.save(u) } }
    }

    fun clearChat() {
        _messages.value = emptyList()
        resetBrainContext()
        memoryModule.newSession()
    }

    // ── Voice ───────────────────────────────────────────────

    private var voiceReEngageJob: kotlinx.coroutines.Job? = null
    private var silenceTimeoutJob: kotlinx.coroutines.Job? = null

    /**
     * Tear down all voice-mode resources: stop listening, stop TTS, cancel
     * re-engage and silence-timeout jobs, and set [_isVoiceMode] to false.
     * Call from any site that needs to fully exit voice mode.
     */
    private fun teardownVoice() {
        stopListening()
        ttsEngine.stop()
        voiceReEngageJob?.cancel()
        silenceTimeoutJob?.cancel()
        _isVoiceMode.value = false
    }

    /** Start a fresh text chat from the home screen */
    fun startTextChat() {
        if (_isVoiceMode.value) {
            exitDeskCaddyMode()
            teardownVoice()
        }
        saveCurrentConversation()
        newConversation()
    }

    /**
     * Lazily load TTS + STT models on first voice mode entry.
     * Keeps them out of memory until the user actually needs voice.
     */
    private fun ensureVoiceModelsLoaded(onReady: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // Initialize STT (Whisper) if not already ready
            if (!whisperStt.isReady.value) {
                val earsPath = modelManager.modelPathFor(ModelType.EARS)
                if (earsPath.isNotBlank()) {
                    val dir = java.io.File(earsPath).parent
                    if (dir != null) {
                        Log.d(TAG, "Voice: loading Whisper STT from $dir")
                        whisperStt.initialize(dir)
                    }
                }
            }
            // Initialize TTS (Piper) if not already ready
            if (!ttsEngine.isReady) {
                initializeTts()
            }
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    /** Start a fresh voice chat from the home screen */
    fun startVoiceChat() {
        saveCurrentConversation()
        newConversation()
        _isVoiceMode.value = true
        memoryModule.isDripSuppressed = true  // Keep drip off while voice is active
        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Loading voice...")
        ensureVoiceModelsLoaded {
            startDirectListening()
        }
    }

    fun toggleVoiceMode() {
        if (_isTaskRunning.value) {
            Log.w(TAG, "Voice mode blocked — task is running")
            return
        }
        val wasVoice = _isVoiceMode.value
        _isVoiceMode.value = !wasVoice

        // Save current conversation messages before switching
        saveCurrentConversation()

        // Start a new conversation for the new mode
        newConversation()

        if (!wasVoice) {
            // Entering voice mode — suppress drip while mic is live
            memoryModule.isDripSuppressed = true
            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Loading voice...")
            ensureVoiceModelsLoaded {
                startDirectListening()
            }
        } else {
            // Leaving voice mode — tear down voice, then re-enable drip
            exitDeskCaddyMode()
            teardownVoice()
            if (!_isGenerating.value && !_isSleepMode.value) {
                memoryModule.isDripSuppressed = false
            }
        }
    }

    // ── Desk Caddy Mode ──────────────────────────────────────

    /**
     * Toggle Desk Caddy mode — always-on mic with no silence timeout.
     * The mic stays hot and continuously listens for queries, then re-engages
     * automatically after each response. No wake word needed.
     */
    fun toggleDeskCaddyMode() {
        val wasCaddy = _isDeskCaddyMode.value
        _isDeskCaddyMode.value = !wasCaddy
        settings.deskCaddyMode = !wasCaddy

        if (!wasCaddy) {
            // Record voice state BEFORE forcing it on, so we can restore it on exit
            voiceWasOnBeforeCaddy = _isVoiceMode.value
            memoryModule.isDripSuppressed = true  // Suppress drip while caddy is live
            if (!_isVoiceMode.value) {
                saveCurrentConversation()
                newConversation()
                _isVoiceMode.value = true
            }
            Log.d(TAG, "Desk Caddy mode ON — always listening, no silence timeout (voiceWasBefore=$voiceWasOnBeforeCaddy)")
            _mascotState.value = MascotState(MascotMood.LISTENING, bubbleText = "Desk Caddy on")
            startDirectListening()
        } else {
            exitDeskCaddyMode()
        }
    }

    private fun exitDeskCaddyMode() {
        if (_isDeskCaddyMode.value) {
            _isDeskCaddyMode.value = false
            settings.deskCaddyMode = false
            Log.d(TAG, "Desk Caddy mode OFF (voiceWasBefore=$voiceWasOnBeforeCaddy)")

            // If caddy implicitly turned voice on, turn it off again on exit
            if (!voiceWasOnBeforeCaddy) {
                teardownVoice()
            }
            voiceWasOnBeforeCaddy = false

            // Re-enable drip only when voice mode is also fully off and brain isn't busy
            if (!_isVoiceMode.value && !_isGenerating.value && !_isSleepMode.value) {
                memoryModule.isDripSuppressed = false
            }
        }
    }

    fun startDirectListening() {
        Log.d(TAG, "startDirectListening (deskCaddy=${_isDeskCaddyMode.value}, whisperReady=${whisperStt.isReady.value})")
        if (whisperStt.isReady.value) {
            whisperStt.startListening()
        } else {
            speechManager.startQueryListening()
        }
        _mascotState.value = MascotState(MascotMood.LISTENING,
            bubbleText = if (_isDeskCaddyMode.value) "Desk Caddy — listening..." else null
        )

        // In Desk Caddy mode, no silence timeout — mic stays on indefinitely.
        // In normal voice mode, silence timeout → switch to wake word listening.
        if (!_isDeskCaddyMode.value) {
            silenceTimeoutJob?.cancel()
            silenceTimeoutJob = viewModelScope.launch {
                kotlinx.coroutines.delay(8000)
                // If still listening (no speech detected after 8s)
                if (!_isGenerating.value && _isVoiceMode.value) {
                    Log.d(TAG, "Silence timeout — switching to wake word listening")
                    stopListening()
                    // Switch to wake word mode so user can say "Hermie" to re-engage
                    startWakeWordListeningMode()
                }
            }
        } else {
            // Desk Caddy: cancel any existing timeout — mic should stay on
            silenceTimeoutJob?.cancel()
        }
    }

    /**
     * Enter wake word listening mode — low-power continuous listening for "Hermie".
     * When the wake word is detected, switch back to active query listening.
     */
    private fun startWakeWordListeningMode() {
        if (!_isVoiceMode.value) return
        // Cancel any active silence timeout — we're now in wake word mode, not direct listening
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
        Log.d(TAG, "Starting wake word listening mode")
        speechManager.startWakeWordListening()
        _mascotState.value = MascotState(MascotMood.IDLE, bubbleText = "Say 'Hermie' to talk")
    }

    private fun stopListening() {
        whisperStt.stopListening()
        speechManager.stopListening()
    }

    /**
     * Re-engage the mic after the LLM (and TTS if active) finishes responding.
     * In Desk Caddy mode, always re-engages with no timeout.
     */
    private fun reEngageVoice() {
        voiceReEngageJob?.cancel()
        voiceReEngageJob = viewModelScope.launch {
            // Longer delay to avoid picking up TTS tail-end audio as input
            kotlinx.coroutines.delay(800)
            if (_isVoiceMode.value) {
                Log.d(TAG, "Re-engaging voice after response (deskCaddy=${_isDeskCaddyMode.value})")
                startDirectListening()
            }
        }
    }

    private fun setupSpeechCallbacks() {
        // ── Wake word detected ─────────────────────────────────
        // When the user says "Hermie" during wake word listening,
        // switch to active listening or process the query directly
        speechManager.onWakeWordDetected = {
            Log.d(TAG, "Wake word detected! Switching to active listening")
            silenceTimeoutJob?.cancel()
            _mascotState.value = MascotState(MascotMood.LISTENING, bubbleText = "I'm listening!")
        }

        speechManager.onQueryRecognized = { text ->
            silenceTimeoutJob?.cancel()
            partialTranscript.value = ""
            if (text.isNotBlank()) {
                sendMessage(text)
            } else if (_isDeskCaddyMode.value) {
                // Desk Caddy: empty result, re-engage immediately
                startDirectListening()
            } else if (_isVoiceMode.value) {
                // Normal voice: go back to wake word mode
                startWakeWordListeningMode()
            }
        }

        whisperStt.onQueryRecognized = { text ->
            silenceTimeoutJob?.cancel()
            partialTranscript.value = ""
            if (text.isNotBlank()) {
                sendMessage(text)
            } else if (_isDeskCaddyMode.value) {
                startDirectListening()
            } else if (_isVoiceMode.value) {
                startWakeWordListeningMode()
            }
        }

        // Cancel silence timeout when user starts speaking — prevents
        // the timeout from killing the mic while the user is mid-sentence
        speechManager.onSpeechDetected = {
            Log.d(TAG, "Speech detected — cancelled silence timeout")
            silenceTimeoutJob?.cancel()
        }

        // When listening times out with no speech (ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT)
        speechManager.onListeningTimeout = {
            if (_isDeskCaddyMode.value && _isVoiceMode.value) {
                // Desk Caddy: restart mic immediately
                Log.d(TAG, "Desk Caddy: recognizer timeout, restarting mic")
                startDirectListening()
            } else if (_isVoiceMode.value) {
                // Normal voice: switch to wake word listening
                // Cancel the silence timeout since we're handling it here
                silenceTimeoutJob?.cancel()
                silenceTimeoutJob = null
                Log.d(TAG, "Voice mode: recognizer timeout, switching to wake word")
                startWakeWordListeningMode()
            }
        }

        // Partial results also cancel silence timeout (user is actively speaking)
        viewModelScope.launch {
            speechManager.lastTranscript.collect { text ->
                if (text.isNotBlank()) {
                    silenceTimeoutJob?.cancel()
                    partialTranscript.value = text
                }
            }
        }

        viewModelScope.launch {
            whisperStt.lastTranscript.collect { text ->
                if (text.isNotBlank()) {
                    silenceTimeoutJob?.cancel()
                    partialTranscript.value = text
                }
            }
        }

        // Whisper STT is loaded on demand when voice mode is activated
        // to save memory at startup. See ensureVoiceModelsLoaded().
    }

    /** Save current messages into the in-memory list and flush to disk. */
    private fun saveCurrentConversation() {
        val convId = _currentConversationId.value ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        var updated: Conversation? = null
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == convId) {
                val u = conv.copy(
                    messages = msgs,
                    title = msgs.firstOrNull { it.role == "user" }?.content?.take(30) ?: conv.title,
                    updatedAt = System.currentTimeMillis()
                )
                updated = u
                u
            } else conv
        }
        updated?.let { u -> viewModelScope.launch { conversationStore.save(u) } }
    }

    // ── Model management ────────────────────────────────────

    fun downloadModelForType(model: ModelInfo) {
        viewModelScope.launch {
            val hfToken = settings.hfToken.value
            val success = modelManager.downloadModel(model, hfToken.ifBlank { null })
            if (success) {
                when (model.type) {
                    ModelType.BRAIN -> switchModel(model)
                    ModelType.SLM -> {
                        // Auto-load SLM after download
                        val slmPath = modelManager.modelPathFor(ModelType.SLM)
                        if (slmPath.isNotBlank() && engine.isLoaded) {
                            try {
                                mindEngine.loadModel(slmPath, model.useTurboCache, model.contextSize)
                                Log.d(TAG, "SLM loaded after download (drip atomizer mode)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load SLM after download", e)
                            }
                        }
                    }
                    ModelType.MIND -> {
                        // Auto-load embedding engine after download
                        loadEmbeddingModel()
                    }
                    else -> {} // Other types handled elsewhere
                }
            }
        }
    }

    /**
     * Called after onboarding completes. The activeModel StateFlow collector
     * handles brain loading automatically; this just kicks off TTS + support model downloads.
     * If brain isn't loaded yet (edge case), loads it as a fallback.
     */
    fun loadActiveModel() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!engine.isLoaded) {
                val path = modelManager.modelPath
                val model = modelManager.activeModel.value
                if (path.isNotBlank()) {
                    try {
                        Log.d(TAG, "loadActiveModel: loading $path (fallback)")
                        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Loading...")
                        val turbo = model?.useTurboCache ?: false
                        val ctxSize = model?.contextSize ?: 8192
                        memoryModule.isDripSuppressed = true  // Block drip during model load
                        engine.loadModel(path, turbo, ctxSize)
                        settings.setModelPath(path)
                        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
                        autoLoadSupportModels()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load active model", e)
                        _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model error")
                    } finally {
                        if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                            memoryModule.isDripSuppressed = false
                        }
                    }
                }
            } else {
                Log.d(TAG, "loadActiveModel: brain already loaded, skipping")
            }
        }
        // Kick off TTS download in background if not already downloaded
        downloadAndInitTts()
        // Auto-download SLM + embedding models if not already downloaded
        downloadAndInitSupportModels()
    }

    fun switchModel(model: ModelInfo) {
        // Directly unload + load on IO. We can't rely on activeModel.collect
        // because StateFlow won't re-emit if the model is already the active value
        // (which happens when downloadModel() sets it before we get here).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Switching...")
                memoryModule.isDripSuppressed = true  // Block drip during model swap
                if (engine.isLoaded) engine.unloadModel()
                modelManager.setActiveModel(model)

                val path = modelManager.modelPath
                if (path.isNotBlank()) {
                    Log.d(TAG, "switchModel: loading ${model.displayName} from $path")
                    val turbo = model?.useTurboCache ?: false
                    val ctxSize = model?.contextSize ?: 8192
                    engine.loadModel(path, turbo, ctxSize)
                    settings.setModelPath(path)
                    primeBrainModel()
                    _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
                    Log.d(TAG, "switchModel: ${model.displayName} loaded successfully")
                } else {
                    Log.w(TAG, "switchModel: model path is blank for ${model.displayName}")
                    _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model error")
            } finally {
                if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                    memoryModule.isDripSuppressed = false
                }
            }
        }
    }

    fun deleteModel(model: ModelInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            // Unload the model if it's currently in use
            when (model.type) {
                ModelType.SLM -> {
                    if (mindEngine.isLoaded) {
                        Log.d(TAG, "Unloading SLM before delete")
                        mindEngine.unloadModel()
                    }
                }
                ModelType.VOICE -> {
                    ttsEngine.stop()
                    // Piper doesn't have a formal unload, but deleting files is sufficient
                }
                ModelType.EARS -> {
                    whisperStt.release()
                }
                ModelType.MIND -> {
                    // Embedding engine — no formal unload needed
                }
                ModelType.VISION -> {
                    // Vision is only loaded during sleep — no runtime unload needed
                }
                ModelType.BRAIN -> {
                    // Don't allow deleting the active brain model
                    if (modelManager.activeModel.value?.id == model.id) {
                        Log.w(TAG, "Cannot delete the active brain model")
                        return@launch
                    }
                }
            }
            modelManager.deleteModel(model)
            Log.d(TAG, "Deleted model: ${model.displayName} (${model.type})")
        }
    }

    // ── Tasks ───────────────────────────────────────────────

    /**
     * Returns true when the Brain is free to take on a task.
     * Every entry point that wants to use the Brain for task execution must check this first.
     */
    fun canAcquireBrain(): Boolean =
        !_isGenerating.value &&
        !_isSleepMode.value &&
        !_isStudyMode.value &&
        !_isWaking.value &&
        !_isTaskRunning.value &&
        !_isBrainRestoring.value &&
        !_isVoiceMode.value

    /**
     * Create a new task object and either schedule it or immediately begin the
     * plan → execute lifecycle via [runTask].
     *
     * If [requirePlanReview] is true the Brain will plan subtasks and then pause at
     * AWAITING_REVIEW so the user can approve the plan before execution.
     */
    fun createTask(
        title: String,
        description: String,
        requirePlanReview: Boolean = false,
        scheduledFor: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // TaskManager.createTask() now only creates the Task object — no planning.
                val task = taskManager.createTask(title, description, requirePlanReview)

                if (scheduledFor != null && scheduledFor > System.currentTimeMillis()) {
                    // Register the alarm; task stays SCHEDULED until it fires.
                    taskScheduler.schedule(task.id, scheduledFor)
                    val scheduled = task.copy(status = TaskStatus.SCHEDULED, scheduledFor = scheduledFor)
                    taskManager.updateTask(scheduled)
                    taskStore.save(scheduled)
                    updateScheduledCount()
                    _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Task scheduled!")
                } else {
                    // Run immediately — runTask handles planning + execution + restore.
                    runTask(task.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createTask failed", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Task creation failed")
            }
        }
    }

    /**
     * Single orchestrator for task execution. Handles the full lifecycle:
     *
     * 1. Guard: check [canAcquireBrain]. If busy, mark task QUEUED and return.
     * 2. If the task has no subtasks yet — swap to planner system prompt and call
     *    [TaskManager.planCurrentTask]. If the plan requires review stop here.
     * 3. Swap to executor system prompt and call [TaskManager.executeAllSubtasks].
     * 4. Finally: restore Brain to chat mode via [restoreBrainForChat].
     *
     * Call this instead of the old `startTask()` from everywhere (approveTask,
     * resumeTask, alarm drain, etc.) except for PAUSED tasks which use [resumeTask].
     */
    fun runTask(taskId: String) {
        val task = taskManager.getTask(taskId) ?: return

        if (!canAcquireBrain()) {
            // Brain is busy — queue the task and return. It will be picked up
            // when the current brain operation finishes and conditions allow.
            Log.w(TAG, "runTask($taskId): brain busy — queuing task")
            val queued = task.copy(status = TaskStatus.QUEUED)
            taskManager.updateTask(queued)
            viewModelScope.launch { taskStore.save(queued) }
            return
        }

        taskManager.selectTask(taskId)
        _isTaskRunning.value = true
        memoryModule.isBrainBusy = true
        memoryModule.isDripSuppressed = true
        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Working on task...")

        taskJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── Phase 1: Plan (only if no subtasks yet) ──────────────────────
                if (task.subtasks.isEmpty()) {
                    _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Planning...")
                    llamaEngine.setSystemPrompt(buildTasksPlannerPrompt())
                    llamaEngine.resetContext()
                    taskManager.planCurrentTask()

                    val planned = taskManager.currentTask.value
                    if (planned == null || planned.status == TaskStatus.FAILED) {
                        _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Planning failed")
                        return@launch
                    }
                    if (planned.status == TaskStatus.AWAITING_REVIEW) {
                        // User must approve before we execute — stop here.
                        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Plan ready — review it!")
                        return@launch
                    }
                    if (planned.status == TaskStatus.SCHEDULED) {
                        // Scheduling phrase was extracted during planning — register the alarm
                        // and stop here; execution happens when the alarm fires.
                        val scheduledFor = planned.scheduledFor
                        if (scheduledFor != null && scheduledFor > System.currentTimeMillis()) {
                            taskScheduler.schedule(planned.id, scheduledFor)
                            taskStore.save(planned)
                            updateScheduledCount()
                        }
                        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Task scheduled!")
                        return@launch
                    }
                }

                // ── Phase 2: Execute ──────────────────────────────────────────────
                _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Working on task...")
                llamaEngine.setSystemPrompt(buildTasksSystemPrompt())
                llamaEngine.resetContext()
                taskManager.executeAllSubtasks()

                // ── Outcome ───────────────────────────────────────────────────────
                val finalTask = taskManager.currentTask.value
                _mascotState.value = when (finalTask?.status) {
                    TaskStatus.COMPLETED -> MascotState(MascotMood.EXCITED, bubbleText = "Task complete!")
                    TaskStatus.FAILED    -> MascotState(MascotMood.CONCERNED, bubbleText = "Task failed")
                    TaskStatus.PAUSED    -> MascotState(MascotMood.IDLE, bubbleText = "Task paused")
                    else                 -> MascotState(MascotMood.HAPPY)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Task job cancelled (pause or stop) for $taskId")
                // PAUSED status is propagated by TaskManager's executeAllSubtasks loop.
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed for $taskId", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Task error")
            } finally {
                _isTaskRunning.value = false
                memoryModule.isBrainBusy = false
                // Restore Brain to chat in a child coroutine so finally completes quickly.
                restoreBrainForChat()
            }
        }
    }

    /**
     * Pause the currently-running task. TaskManager records the subtask index +
     * iteration before the coroutine exits so it can be resumed later.
     */
    fun pauseCurrentTask() {
        if (!_isTaskRunning.value) return
        engine.stopGeneration()
        taskJob?.cancel()
        taskJob = null
        // _isTaskRunning is cleared in the finally block of runTask/resumeTask
    }

    /**
     * Resume a PAUSED task from the exact subtask + iteration it was paused at.
     * Unlike [runTask], this skips planning and goes straight to execution.
     */
    fun resumeTask(taskId: String) {
        if (!canAcquireBrain()) {
            Log.w(TAG, "resumeTask($taskId) blocked — brain is busy")
            return
        }
        val task = taskManager.getTask(taskId) ?: return
        if (task.status != TaskStatus.PAUSED) return

        taskManager.selectTask(taskId)
        _isTaskRunning.value = true
        memoryModule.isBrainBusy = true
        memoryModule.isDripSuppressed = true
        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Resuming task...")

        taskJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                llamaEngine.setSystemPrompt(buildTasksSystemPrompt())
                llamaEngine.resetContext()

                taskManager.resumeTask(
                    taskId = taskId,
                    subtaskIndex = task.pausedAtSubtaskIndex ?: 0,
                    iterationStart = task.pausedAtIteration ?: 0
                )

                val finalTask = taskManager.currentTask.value
                _mascotState.value = when (finalTask?.status) {
                    TaskStatus.COMPLETED -> MascotState(MascotMood.EXCITED, bubbleText = "Task complete!")
                    TaskStatus.FAILED    -> MascotState(MascotMood.CONCERNED, bubbleText = "Task failed")
                    TaskStatus.PAUSED    -> MascotState(MascotMood.IDLE, bubbleText = "Task paused")
                    else                 -> MascotState(MascotMood.HAPPY)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Resume job cancelled for $taskId")
            } catch (e: Exception) {
                Log.e(TAG, "Task resume failed for $taskId", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Resume failed")
            } finally {
                _isTaskRunning.value = false
                memoryModule.isBrainBusy = false
                restoreBrainForChat()
            }
        }
    }

    /**
     * Approve a task that is AWAITING_REVIEW and begin executing it immediately.
     * The plan (subtasks list) was already built during the planning phase; we just
     * mark it PENDING and hand it to [runTask] which goes straight to execution.
     */
    fun approveTask(taskId: String) {
        val task = taskManager.getTask(taskId) ?: return
        if (task.status != TaskStatus.AWAITING_REVIEW) return

        if (task.scheduledFor != null && task.scheduledFor > System.currentTimeMillis()) {
            // Plan approved for a scheduled task — register the alarm, don't execute yet.
            val scheduled = task.copy(status = TaskStatus.SCHEDULED)
            taskManager.updateTask(scheduled)
            viewModelScope.launch {
                taskStore.save(scheduled)
                taskScheduler.schedule(taskId, task.scheduledFor)
                updateScheduledCount()
            }
            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Task approved & scheduled!")
        } else {
            // No schedule — run immediately (subtasks already planned, skips planning phase).
            val approved = task.copy(status = TaskStatus.PENDING)
            taskManager.updateTask(approved)
            viewModelScope.launch { taskStore.save(approved) }
            runTask(taskId)
        }
    }

    /**
     * Reject a task that is AWAITING_REVIEW — marks it FAILED with a rejection note.
     */
    fun rejectTask(taskId: String) {
        val task = taskManager.getTask(taskId) ?: return
        val rejected = task.copy(status = TaskStatus.FAILED)
        taskManager.updateTask(rejected)
        viewModelScope.launch { taskStore.save(rejected) }
    }

    /**
     * Delete a task and cancel its alarm if scheduled.
     */
    fun deleteTask(taskId: String) {
        taskScheduler.cancel(taskId)
        taskManager.removeTask(taskId)
        viewModelScope.launch { taskStore.delete(taskId) }
        updateScheduledCount()
    }

    /**
     * Dismiss the artifact chip for a completed task.
     */
    fun dismissTaskArtifact(taskId: String) {
        val task = taskManager.getTask(taskId) ?: return
        taskManager.updateTask(task.copy(artifactDismissed = true))
        viewModelScope.launch { taskStore.save(taskManager.getTask(taskId) ?: task) }
    }

    fun executeAllSubtasks() {
        val taskId = taskManager.currentTask.value?.id ?: return
        runTask(taskId)
    }

    fun executeNextSubtask() {
        viewModelScope.launch(Dispatchers.IO) {
            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Working...")
            val result = taskManager.executeNextSubtask()
            _mascotState.value = when (result?.status) {
                TaskStatus.COMPLETED -> MascotState(MascotMood.HAPPY, bubbleText = "Step done!")
                TaskStatus.FAILED    -> MascotState(MascotMood.CONCERNED, bubbleText = "Step failed")
                else                 -> MascotState(MascotMood.HAPPY)
            }
        }
    }

    fun deselectTask() {
        taskManager.deselectTask()
    }

    /**
     * Restore the Brain to chat mode after a task finishes.
     * Runs asynchronously; gates chat via [_isBrainRestoring] until complete.
     */
    private fun restoreBrainForChat() {
        _isBrainRestoring.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                llamaEngine.setSystemPrompt(buildSystemPrompt())
                llamaEngine.resetContext()
                Log.d(TAG, "Brain restored to chat mode after task")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore brain after task", e)
            } finally {
                _isBrainRestoring.value = false
                if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                    memoryModule.isDripSuppressed = false
                }
            }
        }
    }

    /**
     * Build the Tasks-mode system prompt (loaded from assets/prompts/tasks_system.txt).
     * Applied to Brain before the execution phase so the model behaves as a strict executor.
     */
    private fun buildTasksSystemPrompt(): String {
        return com.hermie.assistant.data.PromptLoader.loadAndFill(
            getApplication(),
            "tasks_system.txt",
            mapOf(
                "user_name" to settings.userName,
                "user_gender" to settings.userGender
            )
        ) ?: "You are Hermie's task execution engine. Use tools to complete the assigned task."
    }

    /**
     * Build the planner system prompt (loaded from assets/prompts/tasks_planner.txt).
     * Applied to Brain before the planning phase; produces a numbered step list only.
     */
    private fun buildTasksPlannerPrompt(): String {
        return com.hermie.assistant.data.PromptLoader.load(
            getApplication(),
            "tasks_planner.txt"
        ) ?: "Break the goal into 2–7 numbered steps. Output the numbered list only."
    }

    /** Recount SCHEDULED tasks and update the state flow. */
    private fun updateScheduledCount() {
        _scheduledTaskCount.value = taskManager.tasks.value
            .count { it.status == TaskStatus.SCHEDULED }
    }

    // ── Background service ──────────────────────────────────

    fun toggleBackgroundService(enable: Boolean) {
        val context = getApplication<Application>()
        if (enable) {
            HermieBackgroundService.start(context)
        } else {
            HermieBackgroundService.stop(context)
        }
        _isBackgroundRunning.value = enable
    }

    // ── Navigation ──────────────────────────────────────────

    fun navigateTo(screen: String) {
        _currentScreen.value = screen
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun loadSystemPrompt() {
        try {
            val context = getApplication<Application>()
            val promptFile = if (modelManager.activeModel.value?.finetuned == true) {
                "system_prompt_finetuned.txt"
            } else {
                "system_prompt_base.txt"
            }
            systemPrompt = com.hermie.assistant.data.PromptLoader.load(context, promptFile)
                ?.replace("Orchid", "Hermie")  // Update name
                ?: "You are Hermie, a friendly and helpful assistant."
        } catch (e: Exception) {
            systemPrompt = "You are Hermie, a friendly and helpful assistant."
        }
        // Set system prompt on the native engine so it's applied right after model load
        llamaEngine.setSystemPrompt(systemPrompt)
    }

    // ── Sleep mode (explicit consolidation) ──────────────────

    private val _isSleepMode = MutableStateFlow(false)
    val isSleepMode: StateFlow<Boolean> = _isSleepMode.asStateFlow()

    private val _sleepProgress = MutableStateFlow("")
    val sleepProgress: StateFlow<String> = _sleepProgress.asStateFlow()

    /** Scrollable log of consolidation progress messages */
    private val _sleepLog = MutableStateFlow<List<String>>(emptyList())
    val sleepLog: StateFlow<List<String>> = _sleepLog.asStateFlow()

    /** The coroutine Job for sleep consolidation — cancellable */
    private var sleepJob: kotlinx.coroutines.Job? = null

    /**
     * Enter sleep mode — iteratively consolidate memory + prune.
     * Blocks chat, voice, and tasks. Shows detailed progress log.
     * The main LLM processes the entire short-term buffer into the graph.
     */
    fun startSleepMode() {
        if (_isSleepMode.value) return
        if (_isTaskRunning.value) {
            Log.w(TAG, "Sleep mode blocked — task is running")
            return
        }
        _isSleepMode.value = true
        _sleepLog.value = emptyList()
        _mascotState.value = MascotState(MascotMood.SLEEPY, bubbleText = "Consolidating memories...")
        memoryModule.isBrainBusy = true
        memoryModule.isDripSuppressed = true  // Hard-block drip during sleep consolidation

        sleepJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                appendSleepLog("Starting memory consolidation...")
                appendSleepLog("Device temperature: ${thermalMonitor.formatStatus()}")
                _sleepProgress.value = "Closing session..."
                appendSleepLog("Closing current session...")
                memoryModule.newSession()

                // Phase 0: Study queue — extract facts from queued articles/PDFs
                val queueSize = studyModule.queuedItems.value.size
                if (queueSize > 0) {
                    _sleepProgress.value = "Studying queued items..."
                    appendSleepLog("--- Phase 0: Study Queue ($queueSize items) ---")

                    // Set extraction system prompt
                    llamaEngine.setSystemPrompt(com.hermie.assistant.modules.study.StudyModule.EXTRACTION_SYSTEM_PROMPT)
                    llamaEngine.resetContext()

                    val studyFacts = studyModule.processQueue(
                        onProgress = { progress -> appendSleepLog(progress) },
                        onThermalCheck = { thermalCheckForSleep() }
                    )
                    appendSleepLog("Study queue: $studyFacts facts extracted")

                    // Restore consolidation system prompt
                    llamaEngine.setSystemPrompt(buildSystemPrompt())
                    llamaEngine.resetContext()

                    // Thermal check after study queue before consolidation
                    thermalCheckForSleep()
                }

                // Reset any orphaned buffer entries (marked processed but no matching node)
                val orphansReset = memoryModule.resetOrphanedBuffer()
                if (orphansReset > 0) {
                    appendSleepLog("Reset $orphansReset orphaned buffer entries for re-processing")
                }

                // Check how much work there is (including newly-added study facts)
                val unprocessed = memoryModule.getUnprocessedCount()
                appendSleepLog("Found $unprocessed unprocessed buffer entries")

                if (unprocessed == 0) {
                    appendSleepLog("No memories to consolidate.")
                    _sleepProgress.value = "No new memories to process"
                } else {
                    // Phase 1: Iterative consolidation
                    _sleepProgress.value = "Consolidating memories..."
                    appendSleepLog("--- Phase 1: Consolidation ---")

                    val totalOps = memoryModule.runIterativeConsolidation(
                        onProgress = { progress -> appendSleepLog(progress) },
                        onBetweenBatches = { thermalCheckForSleep() }
                    )

                    appendSleepLog("Consolidation finished: $totalOps total operations")

                    // Restore Hermie's personality system prompt after consolidation
                    appendSleepLog("Restoring system prompt...")
                    llamaEngine.setSystemPrompt(buildSystemPrompt())
                    llamaEngine.resetContext()

                    // Thermal check between consolidation and pruning
                    thermalCheckForSleep()

                    // Phase 2: Pruning
                    _sleepProgress.value = "Pruning graph..."
                    appendSleepLog("--- Phase 2: Pruning ---")

                    memoryModule.runPruningWithProgress { progress ->
                        appendSleepLog(progress)
                    }
                }

                // Thermal check before wardrobe (heaviest phase — vision model)
                thermalCheckForSleep()

                // Phase 3: Wardrobe categorization (if pending photos + vision model downloaded)
                val wardrobePhotos = wardrobeModule.getUnprocessedCount()
                if (wardrobePhotos > 0 && isVisionModelDownloaded()) {
                    _sleepProgress.value = "Categorizing wardrobe photos..."
                    appendSleepLog("--- Phase 3: Wardrobe Categorization ---")
                    appendSleepLog("$wardrobePhotos photos to categorize")

                    // Unload SLM first — it uses slot 3 and can race with vision loading on slot 0
                    try {
                        if (mindEngine.isLoaded) {
                            appendSleepLog("Unloading SLM to free memory...")
                            mindEngine.unloadModel()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error unloading SLM before wardrobe", e)
                    }

                    val categorized = wardrobeModule.categorizePhotos { progress ->
                        appendSleepLog(progress)
                    }

                    appendSleepLog("Categorized $categorized items")

                    // Reload brain model after vision model was used
                    appendSleepLog("Reloading brain model...")
                    try {
                        reloadBrainModel()
                        appendSleepLog("Brain model reloaded")
                    } catch (e: Exception) {
                        appendSleepLog("ERROR: Failed to reload brain: ${e.message}")
                        Log.e(TAG, "Failed to reload brain after wardrobe", e)
                    }

                    // Reload SLM after brain is back
                    autoLoadSupportModels()
                } else if (wardrobePhotos > 0) {
                    appendSleepLog("$wardrobePhotos wardrobe photos pending but no vision model downloaded — skipping")
                }

                // Phase 4: Exploratory graph linking — runs perpetually until user wakes Hermie
                thermalCheckForSleep()
                appendSleepLog("--- Phase 4: Exploratory Linking ---")
                _sleepProgress.value = "Exploring connections..."
                _mascotState.value = MascotState(MascotMood.SLEEPY, bubbleText = "Exploring connections...")

                val newLinks = memoryModule.runExploratoryLinking(
                    nativeEngine = llamaEngine,
                    onProgress = { progress -> appendSleepLog(progress) },
                    onThermalCheck = { thermalCheckForSleep() }
                )
                appendSleepLog("Exploratory linking: $newLinks new connections discovered")

                // Restore Hermie's system prompt after Phase 4 (linker leaves clean KV cache but not personality prompt)
                llamaEngine.setSystemPrompt(buildSystemPrompt())
                llamaEngine.resetContext()

                // Summary
                val nodeCount = memoryModule.getNodeCount()
                val bufferCount = memoryModule.getBufferCount()
                appendSleepLog("--- Done ---")
                appendSleepLog("Graph: $nodeCount active nodes")
                appendSleepLog("Buffer: $bufferCount total entries")
                _sleepProgress.value = "Done! Memories consolidated."
                _mascotState.value = MascotState(MascotMood.SLEEPY, bubbleText = "Zzz... memories saved")

            } catch (e: kotlinx.coroutines.CancellationException) {
                appendSleepLog("Consolidation cancelled — waking up.")
                _sleepProgress.value = "Cancelled"
            } catch (e: Exception) {
                Log.e(TAG, "Sleep consolidation failed", e)
                appendSleepLog("Error: ${e.message}")
                _sleepProgress.value = "Consolidation failed"
            }
            // Note: sleep mode stays active until user taps "Wake Hermie"
        }
    }

    /**
     * Wake flag — blocks sendMessage() until the system prompt is fully restored.
     * Without this, the user can send a message while the consolidation prompt
     * is still active, producing garbage like "[]".
     */
    private val _isWaking = MutableStateFlow(false)

    fun stopSleepMode() {
        // Stop native generation immediately so the token loop exits
        engine.stopGeneration()

        // Block messages until prompt is restored
        _isWaking.value = true
        _isSleepMode.value = false
        _sleepProgress.value = ""
        _sleepLog.value = emptyList()
        memoryModule.isBrainBusy = false
        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Good morning!")

        // Cancel the sleep coroutine and WAIT for it to actually stop,
        // then restore the system prompt.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wait for the sleep coroutine to fully terminate before touching the engine.
                // This prevents the race where the sleep coroutine sets the consolidation
                // prompt AFTER we try to restore the personality prompt.
                val job = sleepJob
                sleepJob = null
                job?.cancel()
                job?.join() // ← wait until it's truly done
                Log.d(TAG, "Sleep coroutine fully stopped")

                // Now safe to restore the personality prompt
                llamaEngine.setSystemPrompt(buildSystemPrompt())
                llamaEngine.resetContext()
                Log.d(TAG, "Restored system prompt after wake")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore system prompt after wake", e)
            } finally {
                _isWaking.value = false
                Log.d(TAG, "Wake complete — chat unlocked")
                // Re-enable drip only if not in voice/deskCaddy mode
                if (!_isVoiceMode.value && !_isDeskCaddyMode.value) {
                    memoryModule.isDripSuppressed = false
                }
            }

            // Reload SLM (it was deferred during sleep)
            autoLoadSupportModels()
        }
    }

    /**
     * Check device thermals and pause if too hot.
     * Shared between sleep mode phases: consolidation, study queue, wardrobe.
     * Suspends until the device cools down, logging progress to the sleep log.
     */
    private suspend fun thermalCheckForSleep() {
        thermalMonitor.coolDown { progress ->
            appendSleepLog(progress)
            _sleepProgress.value = "Cooling down..."
        }
    }

    /**
     * Thermal check for study mode — logs to the study log instead.
     */
    private suspend fun thermalCheckForStudy() {
        thermalMonitor.coolDown { progress ->
            studyModule.appendStudyLog(progress)
            studyModule.setProgress("Cooling down...")
        }
    }

    private fun appendSleepLog(message: String) {
        val current = _sleepLog.value.toMutableList()
        current.add(message)
        // Keep last 200 lines to avoid unbounded growth
        if (current.size > 200) {
            _sleepLog.value = current.takeLast(200)
        } else {
            _sleepLog.value = current
        }
        Log.d(TAG, "Sleep: $message")
    }

    // ── Study mode ──────────────────────────────────────────────

    private val _isStudyMode = MutableStateFlow(false)
    val isStudyMode: StateFlow<Boolean> = _isStudyMode.asStateFlow()

    private var studyJob: kotlinx.coroutines.Job? = null

    /**
     * Start study mode for a Wikipedia article.
     * Enters study mode (banner expands), then runs the extraction pipeline.
     */
    fun startStudyWikipedia(articleTitle: String) {
        if (_isStudyMode.value || _isSleepMode.value || _isTaskRunning.value) return
        _isStudyMode.value = true
        studyModule.startStudyMode()
        memoryModule.isBrainBusy = true
        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Studying...")
        navigateTo("home") // Navigate to home to show the study banner

        studyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runStudyPipeline {
                    studyModule.studyWikipediaArticle(
                        articleTitle,
                        onProgress = { progress -> studyModule.appendStudyLog(progress) },
                        onThermalCheck = { thermalCheckForStudy() }
                    )
                }
            } finally {
                memoryModule.isBrainBusy = false
            }
        }
    }

    /**
     * Start study mode for a PDF file.
     */
    fun startStudyPdf(uri: android.net.Uri, fileName: String) {
        if (_isStudyMode.value || _isSleepMode.value || _isTaskRunning.value) return
        _isStudyMode.value = true
        studyModule.startStudyMode()
        memoryModule.isBrainBusy = true
        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Reading PDF...")
        navigateTo("home")

        studyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runStudyPipeline {
                    studyModule.studyPdf(
                        uri, fileName,
                        onProgress = { progress -> studyModule.appendStudyLog(progress) },
                        onThermalCheck = { thermalCheckForStudy() }
                    )
                }
            } finally {
                memoryModule.isBrainBusy = false
            }
        }
    }

    /**
     * Shared study pipeline: sets extraction prompt, runs the study block,
     * then restores the normal system prompt. Handles cancellation gracefully.
     */
    private suspend fun runStudyPipeline(studyBlock: suspend () -> Int) {
        try {
            studyModule.appendStudyLog("Setting up extraction prompt...")
            studyModule.setProgress("Preparing brain for study...")

            llamaEngine.setSystemPrompt(com.hermie.assistant.modules.study.StudyModule.EXTRACTION_SYSTEM_PROMPT)
            llamaEngine.resetContext()

            val facts = studyBlock()

            studyModule.setProgress("Done! $facts facts extracted.")
            studyModule.appendStudyLog("Study complete: $facts facts stored in memory buffer")
            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "$facts new facts!")

        } catch (e: kotlinx.coroutines.CancellationException) {
            studyModule.appendStudyLog("Study cancelled.")
            studyModule.setProgress("Cancelled")
            throw e  // Re-throw so the coroutine is properly cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Study failed", e)
            studyModule.appendStudyLog("Error: ${e.message}")
            studyModule.setProgress("Study failed")
        }
        // Always restore normal prompt (unless cancelled — stopStudyMode handles that)
        try {
            studyModule.appendStudyLog("Restoring system prompt...")
            llamaEngine.setSystemPrompt(buildSystemPrompt())
            llamaEngine.resetContext()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore prompt after study", e)
        }
    }

    fun stopStudyMode() {
        // Stop native generation first so the token loop exits
        engine.stopGeneration()

        // Block messages until prompt is restored
        _isWaking.value = true
        _isStudyMode.value = false
        studyModule.stopStudyMode()
        memoryModule.isBrainBusy = false
        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Back to normal!")

        // Wait for study coroutine to fully stop, then restore prompt
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val job = studyJob
                studyJob = null
                job?.cancel()
                job?.join()
                Log.d(TAG, "Study coroutine fully stopped")

                llamaEngine.setSystemPrompt(buildSystemPrompt())
                llamaEngine.resetContext()
                Log.d(TAG, "Restored system prompt after study stop")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore system prompt after study stop", e)
            } finally {
                _isWaking.value = false
                Log.d(TAG, "Study stop complete — chat unlocked")
            }

            // Reload SLM (it was deferred during study)
            autoLoadSupportModels()
        }
    }

    fun searchWikipedia(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            studyModule.searchWikipedia(query)
        }
    }

    fun queueWikipediaArticle(title: String) {
        studyModule.queueWikipediaArticle(title)
    }

    fun queueStudyPdf(uri: android.net.Uri, fileName: String) {
        studyModule.queuePdf(uri, fileName)
    }

    fun removeFromStudyQueue(index: Int) {
        studyModule.removeFromQueue(index)
    }

    // ── Mind debug helper ─────────────────────────────────────

    private fun buildMindDebug(memCtx: MemoryContext): String {
        val gate = memCtx.gateSignals
        val sb = StringBuilder()

        sb.appendLine("┌ Retrieval Gate")
        sb.appendLine("│ linguistic: ${if (gate.linguisticHit) "✓ triggered" else "—"}")
        sb.appendLine("│ embedding probe: ${if (gate.probeHit) "✓ hit" else "—"}")
        sb.appendLine("│ → retrieve: ${if (gate.shouldRetrieve) "YES" else "NO"}")

        val retrieval = memCtx.retrieval
        if (retrieval.anchors.isNotEmpty() || retrieval.dfsNodes.isNotEmpty()) {
            sb.appendLine("│")
            sb.appendLine("├ Retrieved Memory (${retrieval.allNodes.size} nodes)")

            // Seed nodes (from embedding search)
            for (anchor in retrieval.anchors) {
                sb.appendLine("│ ● [${anchor.category}] ${anchor.fact}")
                sb.appendLine("│   accessed:${anchor.accessCount}x")
            }

            // DFS expanded nodes (from graph walk)
            if (retrieval.dfsNodes.isNotEmpty()) {
                sb.appendLine("│")
                sb.appendLine("│ DFS Expanded (${retrieval.dfsNodes.size})")
                for (node in retrieval.dfsNodes) {
                    sb.appendLine("│   → [${node.category}] ${node.fact}")
                }
            }
        }

        if (retrieval.bufferHits.isNotEmpty()) {
            sb.appendLine("│")
            sb.appendLine("├ Buffer Hits (${retrieval.bufferHits.size})")
            retrieval.bufferHits.forEach { b ->
                sb.appendLine("│ ○ ${b.extracted}")
            }
        }

        if (memCtx.recentBuffer.isNotEmpty()) {
            sb.appendLine("│")
            sb.appendLine("├ Session Buffer (${memCtx.recentBuffer.size})")
            memCtx.recentBuffer.forEach { b ->
                sb.appendLine("│ · ${b.extracted}")
            }
        }

        // Show drip atomizer status
        val rawCount = memoryModule.getRawMessageCount()
        if (rawCount > 0) {
            sb.appendLine("│")
            sb.appendLine("├ Drip Queue: $rawCount messages pending")
        }

        sb.append("└")
        return sb.toString()
    }

    private fun emotionToMood(emotion: String): MascotMood = when (emotion) {
        "happy" -> MascotMood.HAPPY
        "sad" -> MascotMood.CONCERNED
        "angry" -> MascotMood.ANNOYED
        "excited" -> MascotMood.EXCITED
        "concerned" -> MascotMood.CONCERNED
        "goofy" -> MascotMood.HAPPY
        "surprised" -> MascotMood.SURPRISED
        "amazed" -> MascotMood.EXCITED
        "nervous" -> MascotMood.CONCERNED
        else -> MascotMood.IDLE
    }

    // ── App Lifecycle (Background/Foreground) ──────────────

    /**
     * Called when the app comes to the foreground.
     * Cancels any pending brain unload and reloads if needed.
     */
    fun onAppForegrounded() {
        backgroundUnloadJob?.cancel()
        backgroundUnloadJob = null
        Log.d(TAG, "App foregrounded — cancelled background unload timer")

        // Drain any task IDs queued by alarm receiver while app was backgrounded
        viewModelScope.launch {
            while (HermieBackgroundService.firedTaskQueue.isNotEmpty()) {
                val firedId = HermieBackgroundService.firedTaskQueue.removeFirst()
                Log.d(TAG, "Processing fired task from alarm: $firedId")
                val task = taskManager.getTask(firedId)
                if (task != null && task.status == TaskStatus.SCHEDULED) {
                    // Mark as PENDING then run (plan + execute).
                    val pending = task.copy(status = TaskStatus.PENDING, scheduledFor = null)
                    taskManager.updateTask(pending)
                    taskStore.save(pending)
                    updateScheduledCount()
                    runTask(firedId)
                } else {
                    Log.w(TAG, "Fired task $firedId not found or not in SCHEDULED state: ${task?.status}")
                }
            }
        }

        // Reload brain if it was unloaded while backgrounded
        if (!engine.isLoaded) {
            Log.d(TAG, "Brain model was unloaded — reloading")
            viewModelScope.launch(Dispatchers.IO) {
                val path = modelManager.modelPath
                val model = modelManager.activeModel.value
                if (path.isNotBlank() && model != null) {
                    try {
                        _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Waking up...")
                        memoryModule.isDripSuppressed = true  // Block drip during reload
                        engine.loadModel(path, model.useTurboCache, model.contextSize)
                        _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")

                        // Reload support models if not in DnD mode
                        if (_mindMode.value == MindMode.DRIP_ATOMIZER) {
                            autoLoadSupportModels()
                        }
                    } finally {
                        if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                            memoryModule.isDripSuppressed = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the app goes to the background.
     * Starts a 1-minute timer to unload Brain + Embedding to free memory.
     * Mind model stays loaded for DnD notification filtering / Screen Time.
     */
    fun onAppBackgrounded() {
        // Persist current conversation so no messages are lost if the process is killed
        saveCurrentConversation()
        viewModelScope.launch { _conversations.value.forEach { conversationStore.save(it) } }

        // Don't unload during voice mode, sleep mode, or active task execution
        if (_isVoiceMode.value || _isSleepMode.value || _isTaskRunning.value) {
            Log.d(TAG, "App backgrounded but voice/sleep/task active — keeping models loaded")
            return
        }

        Log.d(TAG, "App backgrounded — starting ${BACKGROUND_UNLOAD_DELAY_MS/1000}s unload timer")
        backgroundUnloadJob?.cancel()
        backgroundUnloadJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(BACKGROUND_UNLOAD_DELAY_MS)
            Log.d(TAG, "Background unload timer expired — unloading Brain + Embedding")

            // Unload brain (slot 0)
            if (engine.isLoaded) {
                try {
                    engine.unloadModel()
                    Log.d(TAG, "Brain model unloaded (backgrounded)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unload brain", e)
                }
            }

            // Unload embedding
            if (embeddingEngine.isLoaded) {
                embeddingEngine.release()
                Log.d(TAG, "Embedding engine released (backgrounded)")
            }

            // Update the foreground notification to show reduced state
            HermieBackgroundService.updateNotification(
                getApplication(),
                if (isDndEnabled()) "DnD active — filtering notifications"
                else "Background monitoring active"
            )
        }
    }

    override fun onCleared() {
        speechManager.release()
        whisperStt.release()
        ttsEngine.release()
        embeddingEngine.release()
        moduleRegistry.releaseAll()
        backgroundUnloadJob?.cancel()
        viewModelScope.launch {
            if (engine.isLoaded) engine.unloadModel()
        }
        super.onCleared()
    }

    // ── Wardrobe ────────────────────────────────────────────

    private val _wardrobeOutfits = MutableStateFlow<List<OutfitSuggestion>>(emptyList())
    val wardrobeOutfits: StateFlow<List<OutfitSuggestion>> = _wardrobeOutfits.asStateFlow()

    private val _isWardrobeGenerating = MutableStateFlow(false)
    val isWardrobeGenerating: StateFlow<Boolean> = _isWardrobeGenerating.asStateFlow()

    fun addWardrobePhotos(uris: List<String>) {
        wardrobeModule.addPhotos(uris)
    }

    fun generateWardrobeOutfits(occasion: String, formality: Int, userRequest: String?) {
        if (_isWardrobeGenerating.value) return
        _isWardrobeGenerating.value = true
        _wardrobeOutfits.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gender = settings.userGender.ifBlank { "unspecified" }
                val useFahrenheit = settings.wardrobeTemperatureUnit == "fahrenheit"
                val suggestions = wardrobeModule.generateOutfits(
                    occasion, formality, userRequest, gender, useFahrenheit
                )
                _wardrobeOutfits.value = suggestions
                // Try updating style profile after generation
                wardrobeModule.maybeUpdateStyleProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Outfit generation failed", e)
            } finally {
                _isWardrobeGenerating.value = false
            }
        }
    }

    fun pickWardrobeOutfit(outfit: OutfitSuggestion) {
        val occasion = _wardrobeOutfits.value.let { "picked" } // Current occasion context
        wardrobeModule.recordChoice(outfit, occasion, "")
        _wardrobeOutfits.value = emptyList()
    }

    fun tryAgainWardrobeOutfits() {
        if (_isWardrobeGenerating.value) return
        _isWardrobeGenerating.value = true
        _wardrobeOutfits.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val suggestions = wardrobeModule.tryAgainOutfits()
                _wardrobeOutfits.value = suggestions
            } catch (e: Exception) {
                Log.e(TAG, "Try again outfit generation failed", e)
            } finally {
                _isWardrobeGenerating.value = false
            }
        }
    }

    fun rejectAllWardrobeOutfits() {
        wardrobeModule.recordRejection()
        _wardrobeOutfits.value = emptyList()
    }

    fun deactivateWardrobeItem(itemId: Long) {
        wardrobeModule.deactivateItem(itemId)
    }

    fun isVisionModelDownloaded(): Boolean =
        ModelManager.VISION_MODELS.any { modelManager.isDownloaded(it) }

    /**
     * Reload the brain model after it was unloaded (e.g., after wardrobe categorization).
     * Also re-loads support models (SLM, embeddings).
     */
    private suspend fun reloadBrainModel() {
        val brainModel = modelManager.activeModel.value ?: return
        val brainPath = modelManager.modelPath
        if (brainPath.isBlank()) return

        Log.d(TAG, "Reloading brain model: ${brainModel.displayName}")
        memoryModule.isDripSuppressed = true  // Block drip during reload
        try {
            llamaEngine.setSystemPrompt(buildSystemPrompt())
            llamaEngine.loadModel(brainPath, brainModel.useTurboCache, brainModel.contextSize)
            Log.d(TAG, "Brain model reloaded")
        } finally {
            if (!_isVoiceMode.value && !_isDeskCaddyMode.value && !_isSleepMode.value) {
                memoryModule.isDripSuppressed = false
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val brainModel = modelManager.activeModel.value
        val promptFile = if (brainModel?.finetuned == true) "system_prompt_finetuned.txt" else "system_prompt_base.txt"
        return com.hermie.assistant.data.PromptLoader.loadAndFill(
            getApplication(),
            promptFile,
            mapOf(
                "user_name" to settings.userName,
                "user_gender" to settings.userGender,
                "personality" to settings.personalityJokeMessage
            )
        ) ?: "You are Hermie, a helpful assistant."
    }

    companion object {
        private const val TAG = "HermieVM"
    }
}
