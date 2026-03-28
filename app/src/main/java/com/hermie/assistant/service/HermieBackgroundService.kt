package com.hermie.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermie.assistant.modules.ModuleRegistry
import kotlinx.coroutines.*

/**
 * Foreground service that keeps Hermie alive when the app is in background/closed.
 * Runs background modules (screen time tracking, task execution, etc.)
 *
 * Shows a persistent notification so Android doesn't kill the service.
 * This is how the app runs with the app "closed" — the notification stays.
 */
class HermieBackgroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Background service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Hermie is active"))
                startBackgroundTick()
            }
            ACTION_STOP -> {
                stopBackgroundTick()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Hermie is active"
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(message))
            }
        }
        return START_STICKY
    }

    private fun startBackgroundTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                try {
                    // Tick all background modules
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermie")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // TODO: custom Hermie icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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
        private const val TICK_INTERVAL_MS = 60_000L // 1 minute

        const val ACTION_START = "com.hermie.START_BACKGROUND"
        const val ACTION_STOP = "com.hermie.STOP_BACKGROUND"
        const val ACTION_UPDATE_NOTIFICATION = "com.hermie.UPDATE_NOTIFICATION"
        const val EXTRA_MESSAGE = "message"

        /** Set by the Application class so the service can access modules */
        var moduleRegistry: ModuleRegistry? = null

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
