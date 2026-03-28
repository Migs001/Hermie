package com.hermie.assistant.modules.notifications

import android.app.Notification
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
 * This is the Android-sanctioned way to read notifications from other apps.
 * The user must manually enable it in Settings > Notifications > Notification access.
 */
class HermieNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val data = NotificationData(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            timestamp = sbn.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        )

        Log.d(TAG, "Notification: ${data.packageName} — ${data.title}: ${data.text}")

        // Add to recent notifications
        val current = _recentNotifications.value.toMutableList()
        current.add(0, data)
        if (current.size > MAX_RECENT) current.subList(MAX_RECENT, current.size).clear()
        _recentNotifications.value = current

        // Notify listeners
        onNotificationCallback?.invoke(data)
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
    }
}

data class NotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val timestamp: Long,
    val isOngoing: Boolean = false
)
