package com.hermie.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermie.assistant.data.ChatMessage
import com.hermie.assistant.data.Conversation
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.llm.*
import com.hermie.assistant.modules.ModuleRegistry
import com.hermie.assistant.modules.notifications.NotificationModule
import com.hermie.assistant.modules.screentime.ScreenTimeModule
import com.hermie.assistant.modules.tasks.TaskManager
import com.hermie.assistant.modules.tasks.Task
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

    private val engine: LlmEngine = LlamaNativeEngine(application)
    val taskManager = TaskManager(engine)

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

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _isBackgroundRunning = MutableStateFlow(false)
    val isBackgroundRunning: StateFlow<Boolean> = _isBackgroundRunning.asStateFlow()

    val speechState = speechManager.state
    val whisperState = whisperStt.state
    val partialTranscript = MutableStateFlow("")

    val tasks: StateFlow<List<Task>> = taskManager.tasks
    val currentTask = taskManager.currentTask

    // System prompt
    private var systemPrompt: String = ""

    init {
        loadSystemPrompt()
        initializeModules()
        setupSpeechCallbacks()

        // Create initial conversation
        newConversation()

        // Auto-load model: observe active model changes so it loads
        // immediately at startup OR as soon as onboarding finishes downloading
        viewModelScope.launch {
            modelManager.activeModel.collect { model ->
                if (model != null && !engine.isLoaded) {
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
                    if (path.isNotBlank()) {
                        try {
                            Log.d(TAG, "Auto-loading model: ${model.displayName}")
                            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Loading...")
                            engine.loadModel(path)
                            settings.setModelPath(path)
                            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-load model", e)
                            _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model error")
                        }
                    }
                }
            }
        }

        // Auto-initialize TTS if voice model is downloaded
        viewModelScope.launch {
            initializeTts()
        }
    }

    // ── Module initialization ───────────────────────────────

    private fun initializeModules() {
        viewModelScope.launch {
            moduleRegistry.register(NotificationModule())
            moduleRegistry.register(ScreenTimeModule())
            // Wire background service
            HermieBackgroundService.moduleRegistry = moduleRegistry
        }
    }

    // ── Chat ────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return

        // Add user message
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        _mascotState.value = MascotState(MascotMood.THINKING)
        _isGenerating.value = true

        // Unique ID for THIS generation's assistant message
        val responseId = "gen_${System.currentTimeMillis()}"

        viewModelScope.launch {
            // Start TTS queue processor if voice is enabled
            val shouldSpeak = _isVoiceMode.value && settings.voiceEnabled && ttsEngine.isReady
            if (shouldSpeak) ttsEngine.resetStream()
            val ttsJob = if (shouldSpeak) {
                viewModelScope.launch {
                    ttsEngine.processQueue { _isGenerating.value }
                }
            } else null

            try {
                val conversationMessages = buildLlmMessages()
                val response = StringBuilder()
                var emotion: String? = null

                engine.generate(conversationMessages).collect { token ->
                    response.append(token)

                    // Feed token to TTS for sentence-boundary streaming
                    if (shouldSpeak) ttsEngine.feedToken(token)

                    // Parse emotion from response
                    if (emotion == null) {
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
                    val assistantMsg = ChatMessage(
                        id = responseId,
                        role = "assistant",
                        content = response.toString(),
                        emotion = emotion
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

                // Parse and execute any tool calls
                parseAndExecuteTools(response.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Oops...")
            } finally {
                _isGenerating.value = false
                // Wait for TTS to finish speaking remaining sentences
                ttsJob?.join()
                if (_mascotState.value.mood == MascotMood.THINKING) {
                    _mascotState.value = MascotState(MascotMood.HAPPY)
                }

                // Auto re-engage mic in voice mode after response completes
                if (_isVoiceMode.value) {
                    reEngageVoice()
                }
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
        val voiceModel = ModelManager.VOICE_MODELS.firstOrNull() ?: return
        if (!modelManager.isDownloaded(voiceModel)) {
            Log.d(TAG, "Voice model not downloaded yet, skipping TTS init")
            return
        }
        val voiceDir = java.io.File(getApplication<Application>().filesDir, "models/voice")
        ttsEngine.initialize(voiceDir)
    }

    /** Download voice model from settings, then initialize TTS */
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

        // System prompt with tool definitions
        val toolDefs = moduleRegistry.getAllToolDefinitions()
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
        }
    }

    // ── Conversation management ─────────────────────────────

    fun newConversation() {
        val conv = Conversation()
        _conversations.value = _conversations.value + conv
        _currentConversationId.value = conv.id
        _messages.value = emptyList()
    }

    fun switchConversation(id: String) {
        _currentConversationId.value = id
        val conv = _conversations.value.find { it.id == id }
        _messages.value = conv?.messages ?: emptyList()
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
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
        _conversations.value = _conversations.value.map {
            if (it.id == id) it.copy(title = title) else it
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    // ── Voice ───────────────────────────────────────────────

    private var voiceReEngageJob: kotlinx.coroutines.Job? = null
    private var silenceTimeoutJob: kotlinx.coroutines.Job? = null

    /** Start a fresh text chat from the home screen */
    fun startTextChat() {
        if (_isVoiceMode.value) {
            stopListening()
            ttsEngine.stop()
            voiceReEngageJob?.cancel()
            silenceTimeoutJob?.cancel()
            _isVoiceMode.value = false
        }
        saveCurrentConversation()
        newConversation()
    }

    /** Start a fresh voice chat from the home screen */
    fun startVoiceChat() {
        saveCurrentConversation()
        newConversation()
        _isVoiceMode.value = true
        startDirectListening()
    }

    fun toggleVoiceMode() {
        val wasVoice = _isVoiceMode.value
        _isVoiceMode.value = !wasVoice

        // Save current conversation messages before switching
        saveCurrentConversation()

        // Start a new conversation for the new mode
        newConversation()

        if (!wasVoice) {
            // Entering voice mode — start listening immediately
            startDirectListening()
        } else {
            // Leaving voice mode — stop any listening/TTS
            stopListening()
            ttsEngine.stop()
            voiceReEngageJob?.cancel()
            silenceTimeoutJob?.cancel()
        }
    }

    fun startDirectListening() {
        if (whisperStt.isReady.value) {
            whisperStt.startListening()
        } else {
            speechManager.startQueryListening()
        }
        _mascotState.value = MascotState(MascotMood.LISTENING)

        // Start silence timeout — if no speech after 2s, disengage
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            // If still listening (no speech detected), stop
            if (!_isGenerating.value) {
                Log.d(TAG, "Silence timeout — disengaging mic")
                stopListening()
                _mascotState.value = MascotState(MascotMood.IDLE, bubbleText = "Tap or say 'Hermie'")
            }
        }
    }

    private fun stopListening() {
        whisperStt.stopListening()
        speechManager.stopListening()
    }

    /**
     * Re-engage the mic after the LLM (and TTS if active) finishes responding.
     * If 2 seconds of silence pass with no input, disengage.
     */
    private fun reEngageVoice() {
        voiceReEngageJob?.cancel()
        voiceReEngageJob = viewModelScope.launch {
            // Small delay to avoid picking up TTS audio as input
            kotlinx.coroutines.delay(300)
            if (_isVoiceMode.value) {
                Log.d(TAG, "Re-engaging voice after response")
                startDirectListening()
            }
        }
    }

    private fun setupSpeechCallbacks() {
        speechManager.onQueryRecognized = { text ->
            silenceTimeoutJob?.cancel()
            partialTranscript.value = ""
            sendMessage(text)
        }

        whisperStt.onQueryRecognized = { text ->
            silenceTimeoutJob?.cancel()
            partialTranscript.value = ""
            sendMessage(text)
        }

        // Initialize whisper if model available
        viewModelScope.launch {
            val earsPath = modelManager.modelPathFor(ModelType.EARS)
            if (earsPath.isNotBlank()) {
                val dir = java.io.File(earsPath).parent ?: return@launch
                whisperStt.initialize(dir)
            }
        }
    }

    /** Save current messages into the conversation list */
    private fun saveCurrentConversation() {
        val convId = _currentConversationId.value ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        _conversations.value = _conversations.value.map { conv ->
            if (conv.id == convId) conv.copy(
                messages = msgs,
                title = msgs.firstOrNull { it.role == "user" }?.content?.take(30) ?: conv.title
            ) else conv
        }
    }

    // ── Model management ────────────────────────────────────

    fun downloadModelForType(model: ModelInfo) {
        viewModelScope.launch {
            val hfToken = settings.hfToken.value
            val success = modelManager.downloadModel(model, hfToken.ifBlank { null })
            if (success && model.type == ModelType.BRAIN) {
                switchModel(model)
            }
        }
    }

    /** Load whichever brain model is currently set as active (called after onboarding) */
    fun loadActiveModel() {
        viewModelScope.launch {
            val path = modelManager.modelPath
            if (path.isNotBlank()) {
                try {
                    Log.d(TAG, "Loading active model: $path")
                    engine.loadModel(path)
                    settings.setModelPath(path)
                    _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Ready!")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load active model", e)
                    _mascotState.value = MascotState(MascotMood.CONCERNED, bubbleText = "Model error")
                }
            }
        }
    }

    fun switchModel(model: ModelInfo) {
        viewModelScope.launch {
            try {
                if (engine.isLoaded) engine.unloadModel()
                val path = modelManager.modelPathFor(model.type)
                if (path.isNotBlank()) {
                    engine.loadModel(path)
                    settings.setModelPath(path)
                    modelManager.setActiveModel(model)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
            }
        }
    }

    fun deleteModel(model: ModelInfo) {
        modelManager.deleteModel(model)
    }

    // ── Tasks ───────────────────────────────────────────────

    fun createTask(title: String, description: String) {
        viewModelScope.launch {
            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Planning...")
            taskManager.createTask(title, description)
            _mascotState.value = MascotState(MascotMood.HAPPY, bubbleText = "Task planned!")
        }
    }

    fun executeAllSubtasks() {
        viewModelScope.launch {
            _mascotState.value = MascotState(MascotMood.THINKING, bubbleText = "Working...")
            taskManager.executeAllSubtasks()
            _mascotState.value = MascotState(MascotMood.EXCITED, bubbleText = "Done!")
        }
    }

    fun executeNextSubtask() {
        viewModelScope.launch {
            taskManager.executeNextSubtask()
        }
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
                "prompts/system_prompt_finetuned.txt"
            } else {
                "prompts/system_prompt_base.txt"
            }
            systemPrompt = context.assets.open(promptFile)
                .bufferedReader().readText()
                .replace("Orchid", "Hermie")  // Update name
        } catch (e: Exception) {
            systemPrompt = "You are Hermie, a friendly and helpful assistant."
        }
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

    override fun onCleared() {
        speechManager.release()
        whisperStt.release()
        ttsEngine.release()
        moduleRegistry.releaseAll()
        viewModelScope.launch {
            if (engine.isLoaded) engine.unloadModel()
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "HermieVM"
    }
}
