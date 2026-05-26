package com.hermie.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermie.assistant.llm.MindLlmEngine
import com.hermie.assistant.modules.ModuleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that keeps Hermie alive when the app is in background/closed.
 * Runs background modules (screen time tracking, task execution, etc.)
 *
 * Lifecycle modes:
 *  Full mode   — UI is active. All models loaded on demand by ViewModel.
 *  Minimal mode — UI gone (process-level OnStop). Brain/vision/voice unloaded.
 *                 Mind LLM stays resident for screen time and DnD.
 *
 * The transition between modes is driven by ProcessLifecycleOwner in HermieApplication,
 * which sends ACTION_GO_MINIMAL / ACTION_GO_FULL to this service.
 *
 * Graceful shutdown: user swipes the notification → ACTION_REQUEST_SHUTDOWN is sent.
 * The tick loop checks [shutdownPending] at the top of each iteration. When set,
 * it waits for any in-flight mind generation (via [LlamaNativeEngine.slotMutex]),
 * unloads the mind engine, then stops the foreground service.
 */
class HermieBackgroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null

    @Volatile
    private var shutdownPending = false

    @Volatile
    private var isMinimalMode = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Background service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                startBackgroundTick()
            }
            ACTION_STOP -> {
                stopBackgroundTick()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: notificationText()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(message))
            }
            ACTION_FIRE_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_FIRE_TASK_ID) ?: return START_STICKY
                Log.d(TAG, "Scheduled task fired: $taskId")
                firedTaskQueue.add(taskId)
                try {
                    startAsForeground()
                } catch (_: Exception) {}
            }
            ACTION_GO_MINIMAL -> {
                Log.d(TAG, "Going minimal: unloading heavy models")
                isMinimalMode = true
                onGoMinimal?.invoke()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(notificationText()))
            }
            ACTION_GO_FULL -> {
                Log.d(TAG, "Going full: restoring models")
                isMinimalMode = false
                onGoFull?.invoke()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(notificationText()))
            }
            ACTION_REQUEST_SHUTDOWN -> {
                Log.d(TAG, "Shutdown requested via notification swipe")
                shutdownPending = true
            }
        }
        return START_STICKY
    }

    private fun notificationText(): String = if (isMinimalMode) {
        "Hermie is listening (minimal)"
    } else {
        "Hermie is active"
    }

    /**
     * Call startForeground with the explicit DATA_SYNC type on API 34+.
     * Android 14 requires the 3-arg form for any service whose manifest
     * declares foregroundServiceType; calling the 2-arg form throws
     * MissingForegroundServiceTypeException.
     */
    private fun startAsForeground() {
        val notification = buildNotification(notificationText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Called when the user swipes the app out of recents.
     * Do NOT call stopSelf — the foreground service should keep running so
     * Hermie can continue monitoring screen time in the background.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — foreground service continuing")
        super.onTaskRemoved(rootIntent)
    }

    private fun startBackgroundTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                // Check shutdown flag at the top of each tick
                if (shutdownPending) {
                    performGracefulShutdown()
                    return@launch
                }

                try {
                    moduleRegistry?.backgroundModules?.forEach { module ->
                        if (module.isActive) {
                            try {
                                module.onBackgroundTick()
                            } catch (e: Exception) {
                                Log.e(TAG, "Background tick failed for ${module.id}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background tick error", e)
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopBackgroundTick() {
        tickJob?.cancel()
        tickJob = null
    }

    private suspend fun performGracefulShutdown() {
        Log.d(TAG, "Graceful shutdown: waiting for in-flight generation...")
        try {
            mindEngine?.generationMutex?.withLock { /* generation done — safe to unload */ }
        } catch (e: Exception) {
            Log.w(TAG, "Error waiting for generation mutex", e)
        }

        Log.d(TAG, "Graceful shutdown: unloading mind engine")
        try {
            mindEngine?.unloadModel()
        } catch (e: Exception) {
            Log.w(TAG, "Error unloading mind engine", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Service stopped gracefully")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermie Background",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Hermie running for background tasks"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Shutdown intent fired when the user swipes away the notification
        val shutdownIntent = Intent(this, HermieBackgroundService::class.java).apply {
            action = ACTION_REQUEST_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this, 1, shutdownIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermie")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(shutdownPendingIntent)
            .setOngoing(false) // Allow user to swipe to dismiss (triggers shutdown)
            .setSilent(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.d(TAG, "Background service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HermieBackground"
        private const val CHANNEL_ID = "hermie_background"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 60_000L

        const val ACTION_START = "com.hermie.START_BACKGROUND"
        const val ACTION_STOP = "com.hermie.STOP_BACKGROUND"
        const val ACTION_UPDATE_NOTIFICATION = "com.hermie.UPDATE_NOTIFICATION"
        const val ACTION_GO_MINIMAL = "com.hermie.GO_MINIMAL"
        const val ACTION_GO_FULL = "com.hermie.GO_FULL"
        const val ACTION_REQUEST_SHUTDOWN = "com.hermie.REQUEST_SHUTDOWN"
        const val EXTRA_MESSAGE = "message"

        const val ACTION_FIRE_TASK = "com.hermie.FIRE_TASK"
        const val EXTRA_FIRE_TASK_ID = "fire_task_id"

        val firedTaskQueue: ArrayDeque<String> = ArrayDeque()

        /** Set by the Application class so the service can access modules */
        var moduleRegistry: ModuleRegistry? = null

        /** Set by ViewModel so background task firing can execute tasks */
        var taskManager: com.hermie.assistant.modules.tasks.TaskManager? = null

        /** Predicate set by ViewModel — returns true when the Brain is free */
        var canAcquireBrain: () -> Boolean = { false }

        /**
         * Mind engine reference — held so the service can wait for in-flight generation
         * on graceful shutdown. Set by ViewModel after loading the mind model.
         */
        var mindEngine: MindLlmEngine? = null

        /**
         * Called when service receives ACTION_GO_MINIMAL.
         * Set by ViewModel to unload brain/vision/voice models.
         */
        var onGoMinimal: (() -> Unit)? = null

        /**
         * Called when service receives ACTION_GO_FULL.
         * Set by ViewModel to signal that heavy models may be reloaded on demand.
         */
        var onGoFull: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, HermieBackgroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HermieBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateNotification(context: Context, message: String) {
            val intent = Intent(context, HermieBackgroundService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }
    }
}
