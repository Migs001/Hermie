package com.hermie.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.hermie.assistant.ui.mascot.MascotMood

/**
 * Helper for sending Hermie notifications with different mascot faces/icons.
 * Each notification type gets its own channel and can have a different icon.
 */
object HermieNotificationHelper {

    private const val CHANNEL_TASKS = "hermie_tasks"
    private const val CHANNEL_ALERTS = "hermie_alerts"
    private const val CHANNEL_REMINDERS = "hermie_reminders"
    private const val CHANNEL_SCREENTIME = "hermie_screentime"

    fun initialize(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val channels = listOf(
            NotificationChannel(CHANNEL_TASKS, "Tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Task completion and updates"
            },
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Important alerts from Hermie"
            },
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scheduled reminders"
            },
            NotificationChannel(CHANNEL_SCREENTIME, "Screen Time", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Screen time warnings"
            }
        )

        channels.forEach { manager.createNotificationChannel(it) }
    }

    /**
     * Send a notification with a specific mascot mood.
     * Different moods can map to different notification icons.
     */
    fun notify(
        context: Context,
        title: String,
        message: String,
        mood: MascotMood = MascotMood.HAPPY,
        type: NotificationType = NotificationType.GENERAL,
        notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    ) {
        val channelId = when (type) {
            NotificationType.TASK_COMPLETE -> CHANNEL_TASKS
            NotificationType.ALERT -> CHANNEL_ALERTS
            NotificationType.REMINDER -> CHANNEL_REMINDERS
            NotificationType.SCREEN_TIME -> CHANNEL_SCREENTIME
            NotificationType.GENERAL -> CHANNEL_ALERTS
        }

        // Different small icons per mood
        // TODO: Replace with custom Hermie drawable resources per mood
        val smallIcon = when (mood) {
            MascotMood.HAPPY, MascotMood.EXCITED -> android.R.drawable.ic_menu_info_details
            MascotMood.CONCERNED, MascotMood.ANNOYED -> android.R.drawable.ic_dialog_alert
            MascotMood.SURPRISED -> android.R.drawable.ic_dialog_info
            else -> android.R.drawable.ic_menu_info_details
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(smallIcon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    enum class NotificationType {
        GENERAL,
        TASK_COMPLETE,
        ALERT,
        REMINDER,
        SCREEN_TIME
    }
}
