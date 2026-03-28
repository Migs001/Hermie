package com.hermie.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.hermie.assistant.modules.notifications.NotificationModule
import com.hermie.assistant.modules.screentime.ScreenTimeTracker
import com.hermie.assistant.service.HermieNotificationHelper
import com.hermie.assistant.ui.HermieViewModel
import com.hermie.assistant.ui.chat.ChatDrawer
import com.hermie.assistant.ui.chat.ChatScreen
import com.hermie.assistant.ui.home.HomeScreen
import com.hermie.assistant.ui.home.ModuleCardData
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.navigation.Screen
import com.hermie.assistant.ui.onboarding.OnboardingScreen
import com.hermie.assistant.ui.settings.SettingsScreen
import com.hermie.assistant.ui.tasks.TasksScreen
import com.hermie.assistant.ui.theme.AppTheme
import com.hermie.assistant.voice.SpeechManager
import com.hermie.assistant.voice.SherpaOnnxSttEngine

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult?.invoke(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize notification channels
        HermieNotificationHelper.initialize(this)

        setContent {
            AppTheme {
                val vm: HermieViewModel = viewModel()

                val currentScreen by vm.currentScreen.collectAsState()
                val mascotState by vm.mascotState.collectAsState()
                val messages by vm.messages.collectAsState()
                val conversations by vm.conversations.collectAsState()
                val currentConvId by vm.currentConversationId.collectAsState()
                val isGenerating by vm.isGenerating.collectAsState()
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
                                        // Navigate to module-specific screen
                                    },
                                    onSettingsClick = { vm.navigateTo("settings") },
                                    moduleCards = buildModuleCards(vm)
                                )

                                "chat" -> ChatScreen(
                                    messages = messages,
                                    isGenerating = isGenerating,
                                    isListening = isListening,
                                    isVoiceMode = isVoiceMode,
                                    partialTranscript = partialTranscript,
                                    mascotMood = mascotState.mood,
                                    onSendMessage = { vm.sendMessage(it) },
                                    onMicClick = { vm.startDirectListening() },
                                    onStopGeneration = { vm.stopGeneration() },
                                    onToggleVoiceMode = { vm.toggleVoiceMode() },
                                    onOpenDrawer = { showDrawer = true },
                                    onBack = { vm.navigateTo("home") }
                                )

                                "tasks" -> TasksScreen(
                                    tasks = tasks,
                                    currentTask = currentTask,
                                    onBack = { vm.navigateTo("home") },
                                    onCreateTask = { title, desc -> vm.createTask(title, desc) },
                                    onSelectTask = { vm.taskManager.selectTask(it) },
                                    onExecuteAll = { vm.executeAllSubtasks() },
                                    onExecuteNext = { vm.executeNextSubtask() },
                                    onDeleteTask = { vm.taskManager.deleteTask(it) }
                                )

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

        requestMicPermission()
    }

    private fun buildModuleCards(vm: HermieViewModel): List<ModuleCardData> {
        return listOf(
            ModuleCardData(
                moduleId = "notifications",
                title = "Notifications",
                subtitle = "Read notifications from other apps",
                icon = Icons.Outlined.Notifications,
                isActive = NotificationModule.isNotificationAccessGranted(this)
            ),
            ModuleCardData(
                moduleId = "screentime",
                title = "Screen Time",
                subtitle = "Monitor app usage & set limits",
                icon = Icons.Outlined.Timer,
                isActive = vm.moduleRegistry.getModule("screentime")?.isActive ?: false
            )
        )
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionResult = { /* voice features enabled if granted */ }
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
