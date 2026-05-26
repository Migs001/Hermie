package com.hermie.assistant.modules.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service used exclusively for global actions.
 * No events are subscribed to — we never read screen content.
 * The only capability used is GLOBAL_ACTION_HOME at escalation level 2,
 * which sends the user back to the home screen when they've been on an
 * over-limit app for too long and already dismissed two warnings.
 */
class HermieAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HermieAccessibilityService? = null

        /**
         * Send the user to the home screen.
         * Returns true if the action was dispatched, false if the service is not connected.
         */
        fun goHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not subscribed to any events — intentionally empty
    }

    override fun onInterrupt() {
        // No ongoing operations to interrupt
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
