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

class HermieApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var minimalDebounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        HermieNotificationHelper.initialize(this)
        registerProcessLifecycleObserver()
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
        private const val MINIMAL_DEBOUNCE_MS = 5_000L
    }
}
