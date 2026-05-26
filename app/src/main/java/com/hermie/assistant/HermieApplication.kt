package com.hermie.assistant

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermie.assistant.service.HermieBackgroundService
import com.hermie.assistant.service.HermieNotificationHelper
import kotlinx.coroutines.*
import java.io.File

class HermieApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var minimalDebounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        wipeBubbleIconCacheIfNeeded()
        HermieNotificationHelper.initialize(this)
        // Start the foreground service immediately so Hermie survives when the user
        // swipes the task away. Service re-issues startForeground on each start.
        try {
            HermieBackgroundService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not auto-start background service", e)
        }
        registerProcessLifecycleObserver()
    }

    /**
     * Wipe the on-disk bubble icon cache when [BUBBLE_ICON_SCHEMA_VERSION] is bumped.
     * `HermieNotificationHelper.mascotIconUri` will regenerate files on next use.
     * Bump [BUBBLE_ICON_SCHEMA_VERSION] whenever [MascotBitmapRenderer] drawing logic changes.
     */
    private fun wipeBubbleIconCacheIfNeeded() {
        val prefs = getSharedPreferences("hermie_settings", MODE_PRIVATE)
        val stored = prefs.getInt(KEY_BUBBLE_ICON_SCHEMA, 0)
        if (stored < BUBBLE_ICON_SCHEMA_VERSION) {
            val cacheDir = File(cacheDir, "bubble_icons")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d(TAG, "Bubble icon cache wiped (schema $stored → $BUBBLE_ICON_SCHEMA_VERSION)")
            }
            prefs.edit().putInt(KEY_BUBBLE_ICON_SCHEMA, BUBBLE_ICON_SCHEMA_VERSION).apply()
        }
    }

    /**
     * Observes process-level foreground/background transitions.
     * Activity-level pause/resume (e.g. screen rotation) does NOT fire these —
     * only the whole-app transition between foreground and background does.
     *
     * ON_STOP with ~5s debounce → ACTION_GO_MINIMAL (unload brain/vision/voice)
     * ON_START → cancel pending minimal, ACTION_GO_FULL
     */
    private fun registerProcessLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App came to foreground — cancel any pending minimal transition
                minimalDebounceJob?.cancel()
                minimalDebounceJob = null

                Log.d(TAG, "App foregrounded — sending GO_FULL")
                sendServiceIntent(HermieBackgroundService.ACTION_GO_FULL)
            }

            override fun onStop(owner: LifecycleOwner) {
                // App went to background — debounce before going minimal to avoid
                // false triggers on quick transitions (e.g. permission dialogs)
                minimalDebounceJob?.cancel()
                minimalDebounceJob = appScope.launch {
                    delay(MINIMAL_DEBOUNCE_MS)
                    Log.d(TAG, "App backgrounded — sending GO_MINIMAL")
                    sendServiceIntent(HermieBackgroundService.ACTION_GO_MINIMAL)
                }
            }
        })
    }

    private fun sendServiceIntent(action: String) {
        try {
            val intent = Intent(this, HermieBackgroundService::class.java).apply {
                this.action = action
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not send $action to background service", e)
        }
    }

    companion object {
        private const val TAG = "HermieApplication"
        private const val MINIMAL_DEBOUNCE_MS = 30_000L

        /** Bump to force a bubble icon cache wipe on next launch. */
        private const val BUBBLE_ICON_SCHEMA_VERSION = 1
        private const val KEY_BUBBLE_ICON_SCHEMA = "bubble_icon_schema_version"
    }
}
