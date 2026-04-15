package com.hermie.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hermie.assistant.R
import com.hermie.assistant.modules.overlay.BubbleActivity
import com.hermie.assistant.modules.screentime.ScreenTimeModule
import com.hermie.assistant.ui.mascot.MascotMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper for sending Hermie notifications with different mascot faces/icons.
 * Each notification type gets its own channel and can have a different icon.
 *
 * Screen time notifications support:
 * - Inline reply actions (user can justify continued use)
 * - Bubble metadata (shows Hermie as a floating bubble, no SYSTEM_ALERT_WINDOW needed)
 */
object HermieNotificationHelper {

    private const val CHANNEL_TASKS = "hermie_tasks"
    private const val CHANNEL_ALERTS = "hermie_alerts"
    private const val CHANNEL_REMINDERS = "hermie_reminders"
    private const val CHANNEL_SCREENTIME = "hermie_screentime"
    private const val CHANNEL_DND_ALERT = "hermie_dnd_alert"

    const val KEY_REPLY_TEXT = "hermie_reply_text"
    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val ACTION_SCREEN_TIME_REPLY = "com.hermie.assistant.ACTION_SCREEN_TIME_REPLY"

    private const val SHORTCUT_HERMIE = "hermie_screentime_shortcut"
    private const val BUBBLE_CATEGORY = "com.hermie.assistant.category.SCREENTIME_BUBBLE"

    const val ACTION_BUBBLE_DISMISSED = "com.hermie.assistant.ACTION_BUBBLE_DISMISSED"
    const val EXTRA_ESCALATION_LEVEL = "extra_escalation_level"

    /** Stable notification ID per app package, so updates replace previous */
    fun screenTimeNotificationId(packageName: String): Int =
        ("screentime_$packageName").hashCode().and(0x7FFFFFFF)

    /** Separate ID for the giveup notification so it doesn't collide with the bubble */
    fun screenTimeGiveupNotificationId(packageName: String): Int =
        ("screentime_giveup_$packageName").hashCode().and(0x7FFFFFFF)

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
            NotificationChannel(CHANNEL_SCREENTIME, "Screen Time", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Screen time warnings and nudges"
                // Allow bubbles on this channel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            },
            NotificationChannel(CHANNEL_DND_ALERT, "Smart DND Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Important notifications that override Do Not Disturb"
                setBypassDnd(true)
                enableVibration(true)
                enableLights(true)
            }
        )

        channels.forEach { manager.createNotificationChannel(it) }

        // Publish the long-lived sharing shortcut for bubbles (required on Android 11+)
        publishBubbleShortcut(context)
    }

    /**
     * Publish a long-lived shortcut for the bubble. Required on Android 11+ for
     * notifications to appear as bubbles.
     */
    private fun publishBubbleShortcut(context: Context) {
        try {
            val hermiePerson = Person.Builder()
                .setName("Hermie")
                .setImportant(true)
                .build()

            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_HERMIE)
                .setShortLabel("Hermie")
                .setLongLived(true)
                .setCategories(setOf(BUBBLE_CATEGORY))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, BubbleActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                })
                .setPerson(hermiePerson)
                .build()

            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            Log.d("HermieNotif", "Bubble shortcut published")
        } catch (e: Exception) {
            Log.e("HermieNotif", "Failed to publish bubble shortcut", e)
        }
    }

    /**
     * Send a notification with a specific mascot mood.
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
            NotificationType.DND_ALERT -> CHANNEL_DND_ALERT
            NotificationType.GENERAL -> CHANNEL_ALERTS
        }

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

    /**
     * Send a screen time notification with:
     * - Inline reply action (user can justify continued use)
     * - Bubble metadata (shows Hermie as a floating bubble when expanded)
     * - Delete intent that fires BubbleDismissReceiver when the notification is swiped away
     */
    fun notifyScreenTime(
        context: Context,
        packageName: String,
        title: String,
        message: String,
        mood: MascotMood = MascotMood.ANNOYED,
        escalationLevel: Int = 0
    ) {
        val notifId = screenTimeNotificationId(packageName)

        val smallIcon = when (mood) {
            MascotMood.ANNOYED -> android.R.drawable.ic_dialog_alert
            MascotMood.CONCERNED -> android.R.drawable.ic_dialog_alert
            else -> android.R.drawable.ic_menu_info_details
        }

        // Reply action via RemoteInput
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Tell Hermie why you need more time...")
            .build()

        val replyIntent = Intent(context, ScreenTimeReplyReceiver::class.java).apply {
            action = ACTION_SCREEN_TIME_REPLY
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "Justify",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        // Launch app intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val launchPendingIntent = PendingIntent.getActivity(
            context, notifId + 1, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Bubble intent — opens BubbleActivity with the message
        val bubbleIntent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(BubbleActivity.EXTRA_MESSAGE, message)
            putExtra(BubbleActivity.EXTRA_MOOD, mood.name)
        }
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            notifId + 2,
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Hermie "person" for the notification (required for bubbles on Android 11+)
        val hermiePerson = Person.Builder()
            .setName("Hermie")
            .setImportant(true)
            .build()

        // Delete intent fires BubbleDismissReceiver when notification is swiped away
        val dismissIntent = Intent(context, com.hermie.assistant.modules.screentime.BubbleDismissReceiver::class.java).apply {
            action = ACTION_BUBBLE_DISMISSED
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_ESCALATION_LEVEL, escalationLevel)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 3,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SCREENTIME)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(smallIcon)
            .setContentIntent(launchPendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .setDeleteIntent(dismissPendingIntent)
            .setStyle(
                NotificationCompat.MessagingStyle(hermiePerson)
                    .addMessage(message, System.currentTimeMillis(), hermiePerson)
            )
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShortcutId(SHORTCUT_HERMIE)
            .addPerson(hermiePerson)

        // Add bubble metadata
        try {
            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                bubblePendingIntent,
                IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            )
                .setDesiredHeight(600)
                .setAutoExpandBubble(true)
                .setSuppressNotification(false) // Show notification AND bubble
                .build()

            builder.setBubbleMetadata(bubbleMetadata)
            Log.d("HermieNotif", "Bubble metadata added for $packageName")
        } catch (e: Exception) {
            Log.w("HermieNotif", "Bubble metadata not supported, falling back to regular notification", e)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notifId, builder.build())
    }

    /**
     * One-shot giveup notification (no bubble, no reply action) shown when the user
     * dismisses a bubble twice at the same escalation level.
     * Uses a separate notification ID so it doesn't cancel the main bubble.
     */
    fun notifyScreenTimeGiveup(
        context: Context,
        packageName: String,
        message: String
    ) {
        val notifId = screenTimeGiveupNotificationId(packageName)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SCREENTIME)
            .setContentTitle("Hermie gives up")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notifId, notification)
    }

    /**
     * Send a DND alert notification that bypasses Do Not Disturb.
     * Used when the Smart DND module determines a notification is important.
     */
    fun notifyDndAlert(
        context: Context,
        title: String,
        message: String,
        mood: MascotMood = MascotMood.CONCERNED,
        notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    ) {
        val smallIcon = android.R.drawable.ic_dialog_alert

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DND_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(smallIcon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setDefaults(Notification.DEFAULT_ALL) // Sound + vibrate
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    enum class NotificationType {
        GENERAL,
        TASK_COMPLETE,
        ALERT,
        REMINDER,
        SCREEN_TIME,
        DND_ALERT
    }
}

/**
 * Receives inline replies from screen time notifications.
 * Generates an LLM response to the user's justification — no canned messages.
 */
class ScreenTimeReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HermieNotificationHelper.ACTION_SCREEN_TIME_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(HermieNotificationHelper.KEY_REPLY_TEXT)?.toString()
        val packageName = intent.getStringExtra(HermieNotificationHelper.EXTRA_PACKAGE_NAME)

        Log.d(TAG, "Reply for $packageName: $replyText")

        if (packageName != null && !replyText.isNullOrBlank()) {
            // Append user's reply to today's conversation thread so LLM sees it in context
            val store = com.hermie.assistant.modules.screentime.ScreenTimeConversationStore(context)
            store.rolloverIfNeeded(packageName)
            store.appendTurn(
                packageName,
                com.hermie.assistant.modules.screentime.ConversationTurn(
                    System.currentTimeMillis(), "user", replyText
                )
            )
        }

        val notifId = HermieNotificationHelper.screenTimeNotificationId(packageName ?: "")

        // Immediately show a "thinking" acknowledgment so the notification clears the input
        HermieNotificationHelper.notify(
            context,
            title = "Hermie heard you",
            message = "\"$replyText\" — Let me think about that...",
            mood = MascotMood.CONCERNED,
            type = HermieNotificationHelper.NotificationType.SCREEN_TIME,
            notificationId = notifId
        )

        // Then generate an LLM response asynchronously.
        // Use goAsync() to get more time for the coroutine (up to 30s instead of 10s).
        if (packageName != null && !replyText.isNullOrBlank()) {
            val pendingResult = goAsync()

            val registry = HermieBackgroundService.moduleRegistry
            val screenTimeModule = registry?.getModule("screentime") as? ScreenTimeModule

            Log.d(TAG, "moduleRegistry=${if (registry != null) "OK" else "NULL"}, " +
                "screenTimeModule=${if (screenTimeModule != null) "OK" else "NULL"}")

            if (screenTimeModule != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val response = screenTimeModule.generateReplyResponse(packageName, replyText)
                        Log.d(TAG, "LLM reply: ${response.take(80)}...")
                        HermieNotificationHelper.notify(
                            context,
                            title = "Hermie heard you",
                            message = response,
                            mood = MascotMood.CONCERNED,
                            type = HermieNotificationHelper.NotificationType.SCREEN_TIME,
                            notificationId = notifId
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate LLM reply", e)
                        HermieNotificationHelper.notify(
                            context,
                            title = "Hermie heard you",
                            message = "\"$replyText\" — Sure, if that's what you think. I'll be watching.",
                            mood = MascotMood.CONCERNED,
                            type = HermieNotificationHelper.NotificationType.SCREEN_TIME,
                            notificationId = notifId
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            } else {
                Log.w(TAG, "ScreenTimeModule not available — using fallback reply")
                HermieNotificationHelper.notify(
                    context,
                    title = "Hermie heard you",
                    message = "\"$replyText\" — I'll keep that in mind. But my eye is on you.",
                    mood = MascotMood.CONCERNED,
                    type = HermieNotificationHelper.NotificationType.SCREEN_TIME,
                    notificationId = notifId
                )
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScreenTimeReply"
    }
}
