package com.hermie.assistant.modules.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hermie.assistant.service.HermieBackgroundService
import com.hermie.assistant.service.HermieNotificationHelper

/**
 * Receives the delete intent when a screen time bubble notification is dismissed.
 * Dispatches to ScreenTimeModule.onBubbleDismissed for re-fire / giveup logic.
 */
class BubbleDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HermieNotificationHelper.ACTION_BUBBLE_DISMISSED) return

        val packageName = intent.getStringExtra(HermieNotificationHelper.EXTRA_PACKAGE_NAME) ?: return
        val escalationLevel = intent.getIntExtra(HermieNotificationHelper.EXTRA_ESCALATION_LEVEL, 0)

        Log.d(TAG, "Bubble dismissed for $packageName at level $escalationLevel")

        val registry = HermieBackgroundService.moduleRegistry
        val screenTimeModule = registry?.getModule("screentime") as? ScreenTimeModule

        if (screenTimeModule != null) {
            screenTimeModule.onBubbleDismissed(packageName, escalationLevel)
        } else {
            Log.w(TAG, "ScreenTimeModule not available — cannot handle dismissal")
        }
    }

    companion object {
        private const val TAG = "BubbleDismissReceiver"
    }
}
