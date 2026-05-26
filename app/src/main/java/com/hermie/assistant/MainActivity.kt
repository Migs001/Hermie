package com.hermie.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermie.assistant.modules.dnd.SmartDndModule
import com.hermie.assistant.modules.notifications.NotificationModule
import com.hermie.assistant.service.HermieNotificationHelper
import com.hermie.assistant.ui.HermieViewModel
import com.hermie.assistant.ui.chat.ChatDrawer
import com.hermie.assistant.ui.chat.ChatScreen
import com.hermie.assistant.ui.home.HomeScreen
import com.hermie.assistant.ui.home.ModuleCardData
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.navigation.Screen
import com.hermie.assistant.ui.onboarding.OnboardingScreen
import com.hermie.assistant.ui.memory.MemoryScreen
import com.hermie.assistant.ui.screentime.ScreenTimeScreen
import com.hermie.assistant.ui.study.StudyScreen
import com.hermie.assistant.ui.settings.SettingsScreen
import com.hermie.assistant.ui.tasks.TasksScreen
import com.hermie.assistant.ui.theme.AppTheme
import com.hermie.assistant.voice.SpeechManager
import com.hermie.assistant.voice.SherpaOnnxSttEngine

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private var pendingCameraUri: Uri? = null
    private var onImagePicked: ((String) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult?.invoke(granted)
        }

    private val multiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Results logged but not blocking — features degrade gracefully
            results.forEach { (perm, granted) ->
                android.util.Log.d("MainActivity", "Permission $perm: $granted")
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                pendingCameraUri?.let { uri -> onImagePicked?.invoke(uri.toString()) }
            }
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onImagePicked?.invoke(it.toString()) }
        }

    private fun launchCamera(onResult: (String) -> Unit) {
        onImagePicked = onResult
        val photoFile = java.io.File.createTempFile(
            "hermie_photo_", ".jpg",
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        )
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingCameraUri = uri
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(uri)
        } else {
            onPermissionResult = { granted ->
                if (granted) cameraLauncher.launch(uri)
            }
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchGallery(onResult: (String) -> Unit) {
        onImagePicked = onResult
        galleryLauncher.launch("image/*")
    }

    // Reference to ViewModel for lifecycle callbacks
    private var viewModelRef: HermieViewModel? = null

    override fun onResume() {
        super.onResume()
        viewModelRef?.onAppForegrounded()
    }

    override fun onPause() {
        super.onPause()
        viewModelRef?.onAppBackgrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize notification channels
        HermieNotificationHelper.initialize(this)

        setContent {
            AppTheme {
                val vm: HermieViewModel = viewModel()
                viewModelRef = vm

                val currentScreen by vm.currentScreen.collectAsState()
                val mascotState by vm.mascotState.collectAsState()
                val messages by vm.messages.collectAsState()
                val conversations by vm.conversations.collectAsState()
                val currentConvId by vm.currentConversationId.collectAsState()
                val isGenerating by vm.isGenerating.collectAsState()
                val isReplayingContext by vm.isReplayingContext.collectAsState()
                val isVoiceMode by vm.isVoiceMode.collectAsState()
                val speechState by vm.speechState.collectAsState()
                val whisperState by vm.whisperStt.state.collectAsState()
                val partialTranscript by vm.partialTranscript.collectAsState()
                val tasks by vm.tasks.collectAsState()
                val currentTask by vm.currentTask.collectAsState()
                val isBackgroundRunning by vm.isBackgroundRunning.collectAsState()
                val onboardingComplete by vm.settings.isOnboardingComplete.collectAsState()
                var showDrawer by remember { mutableStateOf(false) }

                val isListening = speechState == SpeechManager.ListeningState.LISTENING_FOR_QUERY ||
                    whisperState == SherpaOnnxSttEngine.SttState.LISTENING ||
                    whisperState == SherpaOnnxSttEngine.SttState.PROCESSING

                // Show onboarding if not completed
                if (!onboardingComplete) {
                    OnboardingScreen(
                        settings = vm.settings,
                        modelManager = vm.modelManager,
                        onComplete = {
                            vm.loadActiveModel()
                            vm.navigateTo("home")
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 4 } togetherWith
                                    fadeOut(tween(200))
                            },
                            label = "screen_transition"
                        ) { screen ->
                            when (screen) {
                                "home" -> HomeScreen(
                                    userName = vm.settings.userName,
                                    mascotState = mascotState,
                                    activeTaskCount = tasks.count {
                                        it.status != com.hermie.assistant.modules.tasks.TaskStatus.COMPLETED &&
                                        it.status != com.hermie.assistant.modules.tasks.TaskStatus.FAILED
                                    },
                                    onChatClick = {
                                        vm.startTextChat()
                                        vm.navigateTo("chat")
                                    },
                                    onVoiceChatClick = {
                                        vm.startVoiceChat()
                                        vm.navigateTo("chat")
                                    },
                                    onTasksClick = { vm.navigateTo("tasks") },
                                    onModuleClick = { moduleId ->
                                        when (moduleId) {
                                            "screentime" -> vm.navigateTo("screentime")
                                            "smart_dnd" -> vm.navigateTo("smart_dnd")
                                            "memory" -> vm.navigateTo("memory")
                                            "wardrobe" -> vm.navigateTo("wardrobe")
                                            "study" -> vm.navigateTo("study")
                                        }
                                    },
                                    onSettingsClick = { vm.navigateTo("settings") },
                                    moduleCards = buildModuleCards(vm),
                                    isSleepMode = vm.isSleepMode.collectAsState().value,
                                    sleepProgress = vm.sleepProgress.collectAsState().value,
                                    sleepLog = vm.sleepLog.collectAsState().value,
                                    onSleepClick = {
                                        if (vm.isSleepMode.value) vm.stopSleepMode()
                                        else vm.startSleepMode()
                                    },
                                    isStudyMode = vm.isStudyMode.collectAsState().value,
                                    studyProgress = vm.studyModule.studyProgress.collectAsState().value,
                                    studyLog = vm.studyModule.studyLog.collectAsState().value,
                                    onStopStudy = { vm.stopStudyMode() },
                                    onMemoryClick = { vm.navigateTo("memory") }
                                )

                                "chat" -> {
                                    val isDeskCaddy by vm.isDeskCaddyMode.collectAsState()
                                    ChatScreen(
                                        messages = messages,
                                        isGenerating = isGenerating,
                                        isListening = isListening,
                                        isVoiceMode = isVoiceMode,
                                        isDeskCaddyMode = isDeskCaddy,
                                        isReplayingContext = isReplayingContext,
                                        partialTranscript = partialTranscript,
                                        mascotMood = mascotState.mood,
                                        onSendMessage = { vm.sendMessage(it) },
                                        onMicClick = { vm.startDirectListening() },
                                        onStopGeneration = { vm.stopGeneration() },
                                        onToggleVoiceMode = { vm.toggleVoiceMode() },
                                        onToggleDeskCaddy = { vm.toggleDeskCaddyMode() },
                                        onOpenDrawer = { showDrawer = true },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "tasks" -> {
                                    val executionStatus by vm.executionStatus.collectAsState()
                                    TasksScreen(
                                        tasks = tasks,
                                        currentTask = currentTask,
                                        executionStatus = executionStatus,
                                        onBack = { vm.navigateTo("home") },
                                        onCreateTask = { title, desc, review -> vm.createTask(title, desc, review) },
                                        onSelectTask = { vm.taskManager.selectTask(it) },
                                        onDeselectTask = { vm.deselectTask() },
                                        onExecuteAll = { vm.executeAllSubtasks() },
                                        onExecuteNext = { vm.executeNextSubtask() },
                                        onDeleteTask = { vm.taskManager.deleteTask(it) }
                                    )
                                }

                                "smart_dnd" -> {
                                    val dndModule = vm.getSmartDndModule()
                                    val dndEnabled by (dndModule?.dndEnabled
                                        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                                    val silenced by (dndModule?.silencedCount
                                        ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()
                                    val rules = remember(dndEnabled) {
                                        dndModule?.let {
                                            val store = com.hermie.assistant.modules.dnd.DndSettingsStore(this@MainActivity)
                                            store.getRules()
                                        } ?: emptyList()
                                    }

                                    com.hermie.assistant.ui.dnd.DndScreen(
                                        isDndEnabled = dndEnabled,
                                        hasPolicyAccess = vm.hasDndPolicyAccess(),
                                        hasNotificationAccess = com.hermie.assistant.modules.notifications.NotificationModule
                                            .isNotificationAccessGranted(this@MainActivity),
                                        rules = rules,
                                        silencedCount = silenced,
                                        onToggleDnd = { vm.toggleDnd(it) },
                                        onRequestPolicyAccess = { vm.requestDndPolicyAccess() },
                                        onRequestNotificationAccess = {
                                            com.hermie.assistant.modules.notifications.NotificationModule
                                                .openNotificationAccessSettings(this@MainActivity)
                                        },
                                        onAddRule = { desc, type, contact, app ->
                                            val rule = com.hermie.assistant.modules.dnd.DndFilterRule(
                                                description = desc,
                                                ruleType = type,
                                                contactName = contact,
                                                packagePattern = app
                                            )
                                            com.hermie.assistant.modules.dnd.DndSettingsStore(this@MainActivity)
                                                .addRule(rule)
                                        },
                                        onRemoveRule = { ruleId ->
                                            com.hermie.assistant.modules.dnd.DndSettingsStore(this@MainActivity)
                                                .removeRule(ruleId)
                                        },
                                        onViewMissed = {
                                            // Navigate to chat and ask Hermie for missed summary
                                            vm.navigateTo("chat")
                                            vm.sendMessage("What notifications did I miss while DND was on?")
                                        },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "screentime" -> {
                                    val screenTimeModule = vm.getScreenTimeModule()
                                    val tracker = screenTimeModule?.getTracker()

                                    // Collect usage as state, default to empty
                                    val usageFlow = tracker?.appUsageToday
                                    val appUsage = usageFlow?.collectAsState()?.value ?: emptyMap()

                                    // Refresh usage data when screen is shown
                                    LaunchedEffect(Unit) {
                                        tracker?.queryTodayUsage()
                                    }

                                    ScreenTimeScreen(
                                        hasPermission = vm.hasScreenTimePermission(),
                                        appUsage = appUsage,
                                        settings = vm.settings,
                                        onRequestPermission = { vm.requestUsageAccessPermission() },
                                        onSetLimit = { pkg, minutes, reason ->
                                            vm.setScreenTimeLimit(pkg, minutes, reason)
                                        },
                                        onRemoveLimit = { pkg ->
                                            vm.removeScreenTimeLimit(pkg)
                                        },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "memory" -> {
                                    val isSleepMode by vm.isSleepMode.collectAsState()
                                    val sleepProgress by vm.sleepProgress.collectAsState()

                                    // Load memory data off main thread
                                    var refreshTick by remember { mutableStateOf(0) }
                                    var memNodes by remember { mutableStateOf(emptyList<com.hermie.assistant.modules.memory.MemoryNode>()) }
                                    var memBuffer by remember { mutableStateOf(emptyList<com.hermie.assistant.modules.memory.BufferEntry>()) }
                                    var nodeCount by remember { mutableStateOf(0) }
                                    var bufferCount by remember { mutableStateOf(0) }
                                    var unprocessedCount by remember { mutableStateOf(0) }

                                    LaunchedEffect(refreshTick) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            memNodes = vm.memoryModule.getAllNodes()
                                            memBuffer = vm.memoryModule.getAllBuffer()
                                            nodeCount = vm.memoryModule.getNodeCount()
                                            bufferCount = vm.memoryModule.getBufferCount()
                                            unprocessedCount = vm.memoryModule.getUnprocessedCount()
                                        }
                                    }

                                    MemoryScreen(
                                        nodes = memNodes,
                                        bufferEntries = memBuffer,
                                        nodeCount = nodeCount,
                                        bufferCount = bufferCount,
                                        unprocessedCount = unprocessedCount,
                                        onRefresh = { refreshTick++ },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "wardrobe" -> {
                                    val outfitSuggestions by vm.wardrobeOutfits.collectAsState()
                                    val isWardrobeGenerating by vm.isWardrobeGenerating.collectAsState()
                                    val wardrobeItems by produceState(emptyList<com.hermie.assistant.modules.wardrobe.ClothingItem>()) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            value = vm.wardrobeModule.getAllItems()
                                        }
                                    }
                                    val unprocessedCount by vm.wardrobeModule.unprocessedCount.collectAsState()
                                    val favorites by produceState(emptyList<com.hermie.assistant.modules.wardrobe.SavedOutfit>()) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            value = vm.wardrobeModule.getFavorites()
                                        }
                                    }
                                    // Fetch weather for the banner
                                    val useFahrenheit = vm.settings.wardrobeTemperatureUnit == "fahrenheit"
                                    val weatherData by produceState<com.hermie.assistant.ui.wardrobe.WardrobeWeather?>(null) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val w = vm.wardrobeModule.getWeather()
                                            if (w != null) {
                                                val toDisplay = { c: Double ->
                                                    if (useFahrenheit) (c * 9.0 / 5.0 + 32).toInt() else c.toInt()
                                                }
                                                value = com.hermie.assistant.ui.wardrobe.WardrobeWeather(
                                                    temperature = toDisplay(w.temperature),
                                                    tempMin = w.tempMin?.let { toDisplay(it) },
                                                    tempMax = w.tempMax?.let { toDisplay(it) },
                                                    cloudCover = w.cloudCover,
                                                    precipitation = w.precipitation,
                                                    useFahrenheit = useFahrenheit
                                                )
                                            }
                                        }
                                    }

                                    com.hermie.assistant.ui.wardrobe.WardrobeScreen(
                                        isVisionModelDownloaded = vm.isVisionModelDownloaded(),
                                        items = wardrobeItems,
                                        unprocessedCount = unprocessedCount,
                                        occasions = vm.settings.getWardrobeOccasions(),
                                        outfitSuggestions = outfitSuggestions,
                                        isGenerating = isWardrobeGenerating,
                                        favorites = favorites,
                                        weather = weatherData,
                                        onGenerateOutfits = { occasion, formality, request ->
                                            vm.generateWardrobeOutfits(occasion, formality, request)
                                        },
                                        onPickOutfit = { vm.pickWardrobeOutfit(it) },
                                        onRejectAll = { vm.rejectAllWardrobeOutfits() },
                                        onTryAgain = { vm.tryAgainWardrobeOutfits() },
                                        onAddClothes = {
                                            launchCamera { uri ->
                                                vm.addWardrobePhotos(listOf(uri))
                                            }
                                        },
                                        onDeactivateItem = { vm.deactivateWardrobeItem(it) },
                                        onEditItem = { /* TODO: edit dialog */ },
                                        onToggleFavorite = { vm.wardrobeModule.toggleFavorite(it) },
                                        onUpdateFormality = { name, formality ->
                                            val updated = vm.settings.getWardrobeOccasions().map {
                                                if (it.first == name) it.first to formality else it
                                            }
                                            vm.settings.setWardrobeOccasions(updated)
                                        },
                                        onDownloadVision = { vm.navigateTo("settings") },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "study" -> {
                                    val isStudying by vm.isStudyMode.collectAsState()
                                    val searchResults by vm.studyModule.searchResults.collectAsState()
                                    val isSearching by vm.studyModule.isSearching.collectAsState()
                                    val factsExtracted by vm.studyModule.totalFactsExtracted.collectAsState()
                                    val queuedItems by vm.studyModule.queuedItems.collectAsState()

                                    StudyScreen(
                                        isStudying = isStudying,
                                        searchResults = searchResults,
                                        isSearching = isSearching,
                                        factsExtracted = factsExtracted,
                                        queuedItems = queuedItems,
                                        onSearchWikipedia = { vm.searchWikipedia(it) },
                                        onStudyArticle = { vm.startStudyWikipedia(it) },
                                        onQueueArticle = { vm.queueWikipediaArticle(it) },
                                        onStudyPdf = { uri, name -> vm.startStudyPdf(uri, name) },
                                        onQueuePdf = { uri, name -> vm.queueStudyPdf(uri, name) },
                                        onRemoveFromQueue = { vm.removeFromStudyQueue(it) },
                                        onBack = { vm.navigateTo("home") }
                                    )
                                }

                                "settings" -> {
                                    val ttsState by vm.ttsEngine.state.collectAsState()
                                    val ttsDownloadState by vm.modelManager.downloadStateFor(com.hermie.assistant.llm.ModelType.VOICE).collectAsState()
                                    SettingsScreen(
                                        settings = vm.settings,
                                        modelManager = vm.modelManager,
                                        onBack = { vm.navigateTo("home") },
                                        onDownloadModel = { vm.downloadModelForType(it) },
                                        onSwitchModel = { vm.switchModel(it) },
                                        onDeleteModel = { vm.deleteModel(it) },
                                        onClearChat = { vm.clearChat() },
                                        onToggleBackground = { vm.toggleBackgroundService(it) },
                                        isBackgroundRunning = isBackgroundRunning,
                                        onDownloadTtsModel = { vm.downloadAndInitTts() },
                                        isTtsReady = ttsState == com.hermie.assistant.voice.PiperTtsEngine.TtsState.READY ||
                                            ttsState == com.hermie.assistant.voice.PiperTtsEngine.TtsState.SPEAKING,
                                        isTtsDownloading = ttsDownloadState is com.hermie.assistant.llm.ModelManager.DownloadState.Downloading
                                    )
                                }
                            }
                        }

                        // Chat drawer (overlay on any screen)
                        ChatDrawer(
                            isOpen = showDrawer,
                            conversations = conversations,
                            currentConversationId = currentConvId,
                            onClose = { showDrawer = false },
                            onNewChat = { vm.newConversation() },
                            onSelectChat = { vm.switchConversation(it) },
                            onRenameChat = { id, title -> vm.renameConversation(id, title) },
                            onDeleteChat = { vm.deleteConversation(it) }
                        )
                    }
                }
            }
        }

        requestCorePermissions()
    }

    private fun buildModuleCards(vm: HermieViewModel): List<ModuleCardData> {
        return listOf(
            ModuleCardData(
                moduleId = "smart_dnd",
                title = "Do Not Disturb",
                subtitle = "Smart notification filtering with AI",
                icon = Icons.Outlined.DoNotDisturb,
                isActive = vm.getSmartDndModule()?.isActive ?: false
            ),
            ModuleCardData(
                moduleId = "screentime",
                title = "Screen Time",
                subtitle = "Monitor app usage & set limits",
                icon = Icons.Outlined.Timer,
                isActive = vm.moduleRegistry.getModule("screentime")?.isActive ?: false
            ),
            ModuleCardData(
                moduleId = "study",
                title = "Study",
                subtitle = "Learn from PDFs & Wikipedia",
                icon = Icons.Outlined.AutoStories,
                isActive = vm.studyModule.isActive
            ),
            ModuleCardData(
                moduleId = "wardrobe",
                title = "Wardrobe",
                subtitle = "Outfit recommendations from your clothes",
                icon = Icons.Outlined.Checkroom,
                isActive = vm.wardrobeModule.isActive
            )
        )
    }

    private fun requestCorePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            multiPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
