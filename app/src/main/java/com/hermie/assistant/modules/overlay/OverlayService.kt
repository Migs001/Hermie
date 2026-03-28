package com.hermie.assistant.modules.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotSize
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.ui.mascot.MascotView
import com.hermie.assistant.ui.theme.AppTheme

/**
 * Overlay service that draws Hermie's mascot bubble over other apps.
 * Like the old Facebook Messenger chat heads — but can expand to full character.
 *
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var expandedView: View? = null

    private var bubbleX = 0
    private var bubbleY = 100

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> showBubble(
                mood = MascotMood.valueOf(
                    intent.getStringExtra(EXTRA_MOOD) ?: MascotMood.HAPPY.name
                ),
                message = intent.getStringExtra(EXTRA_MESSAGE)
            )
            ACTION_SHOW_FULL -> showFullCharacter(
                mood = MascotMood.valueOf(
                    intent.getStringExtra(EXTRA_MOOD) ?: MascotMood.EXCITED.name
                ),
                message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
            )
            ACTION_DISMISS -> dismissAll()
        }
        return START_NOT_STICKY
    }

    /**
     * Show a small draggable bubble (like Messenger chat heads).
     * The bubble is the mascot in OVERLAY size.
     */
    private fun showBubble(mood: MascotMood, message: String?) {
        dismissAll()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val params = WindowManager.LayoutParams(
            dpToPx(64),
            dpToPx(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                AppTheme {
                    MascotView(
                        state = MascotState(mood = mood, bubbleText = message),
                        size = MascotSize.OVERLAY
                    )
                }
            }
        }

        // Make bubble draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(composeView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bubbleX = params.x
                    bubbleY = params.y
                    if (!isDragging) {
                        // Tap → expand to full character
                        showFullCharacter(mood, message ?: "")
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = composeView
        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }

    /**
     * Show the full character popup (not just a bubble).
     * Can display a message, animate, then auto-dismiss.
     */
    private fun showFullCharacter(mood: MascotMood, message: String) {
        dismissAll()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                AppTheme {
                    OverlayPopup(
                        mood = mood,
                        message = message,
                        onDismiss = { dismissAll() }
                    )
                }
            }
        }

        expandedView = composeView
        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add expanded view", e)
        }
    }

    private fun dismissAll() {
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        try {
            expandedView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        bubbleView = null
        expandedView = null
    }

    override fun onDestroy() {
        dismissAll()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW_BUBBLE = "com.hermie.SHOW_BUBBLE"
        const val ACTION_SHOW_FULL = "com.hermie.SHOW_FULL"
        const val ACTION_DISMISS = "com.hermie.DISMISS"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_MESSAGE = "message"

        fun showBubble(context: Context, mood: MascotMood = MascotMood.HAPPY, message: String? = null) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_BUBBLE
                putExtra(EXTRA_MOOD, mood.name)
                putExtra(EXTRA_MESSAGE, message)
            })
        }

        fun showFullCharacter(context: Context, mood: MascotMood, message: String) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_FULL
                putExtra(EXTRA_MOOD, mood.name)
                putExtra(EXTRA_MESSAGE, message)
            })
        }

        fun dismiss(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = ACTION_DISMISS
            })
        }
    }
}
