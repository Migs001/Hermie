package com.hermie.assistant.modules.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NotificationListenerService that reads incoming notifications.
 * Requires user to grant Notification Access in Settings.
 *
 * When Smart DND is enabled, this service:
 * 1. Runs the DND filter callback to decide if a notification should be silenced
 * 2. Cancels silenced notifications from the status bar (actually hiding them)
 * 3. Falls back to checking persisted DND state if the callback isn't set (app process died)
 */
class HermieNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Skip our own notifications
        if (sbn.packageName == applicationContext.packageName) return

        val data = NotificationData(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            timestamp = sbn.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            key = sbn.key
        )

        Log.d(TAG, "Notification: ${data.packageName} — ${data.title}: ${data.text}")

        // DND filter gets first pass
        var consumed = false
        try {
            consumed = onDndFilterCallback?.invoke(data) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "DND filter callback error", e)
        }

        // Fallback: if no callback but DND is persisted as enabled, silence non-ongoing
        if (!consumed && onDndFilterCallback == null && isDndPersistedEnabled(this)) {
            if (!data.isOngoing) {
                consumed = true
                Log.d(TAG, "DND persisted fallback — silencing ${data.packageName}")
            }
        }

        // Add to recent notifications (always, so the log is complete)
        val current = _recentNotifications.value.toMutableList()
        current.add(0, data)
        if (current.size > MAX_RECENT) current.subList(MAX_RECENT, current.size).clear()
        _recentNotifications.value = current

        if (consumed) {
            // Actually remove the notification from the status bar
            try {
                cancelNotification(sbn.key)
                Log.d(TAG, "Cancelled notification: ${data.packageName} — ${data.title}")
            } catch (e: Exception) {
                // Some OEMs don't allow cancelling certain notifications
                Log.w(TAG, "Failed to cancel notification: ${e.message}")
                try {
                    snoozeNotification(sbn.key, 365L * 24 * 60 * 60 * 1000) // snooze ~1 year
                } catch (_: Exception) {}
            }
        } else {
            // Notify general listeners only if DND didn't consume it
            onNotificationCallback?.invoke(data)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Can track dismissed notifications if needed
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        instance = null
        Log.d(TAG, "Notification listener disconnected")
    }

    companion object {
        private const val TAG = "HermieNotifListener"
        private const val MAX_RECENT = 50

        var instance: HermieNotificationListener? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _recentNotifications = MutableStateFlow<List<NotificationData>>(emptyList())
        val recentNotifications: StateFlow<List<NotificationData>> = _recentNotifications.asStateFlow()

        /** Callback for real-time notification processing */
        var onNotificationCallback: ((NotificationData) -> Unit)? = null

        /**
         * DND filter callback — returns true if the notification was consumed
         * (silenced by Smart DND). Gets first pass before onNotificationCallback.
         */
        var onDndFilterCallback: ((NotificationData) -> Boolean)? = null

        /**
         * Check persisted DND state (works even when SmartDndModule callback isn't set,
         * e.g. after process restart). This is the safety net.
         */
        fun isDndPersistedEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("hermie_dnd", Context.MODE_PRIVATE)
            return prefs.getBoolean("dnd_enabled", false)
        }
    }
}

data class NotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val timestamp: Long,
    val isOngoing: Boolean = false,
    val key: String? = null
)
