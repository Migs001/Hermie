package com.hermie.assistant.ui.mascot

/**
 * All possible mascot states/poses.
 * Each maps to a different visual representation (Canvas drawing).
 */
enum class MascotMood {
    IDLE,           // Default resting face
    HAPPY,          // Smiling
    THINKING,       // Processing / loading
    TALKING,        // Actively responding
    LISTENING,      // Mic is active, waiting for input
    EXCITED,        // Task complete, greeting, etc.
    SLEEPY,         // Background / idle for long time
    SURPRISED,      // Notification, unexpected event
    CONCERNED,      // Warning, error
    WAVING,         // First launch greeting
    ANNOYED         // Screen time limit reached
}

/**
 * Data class representing the full mascot display state.
 */
data class MascotState(
    val mood: MascotMood = MascotMood.IDLE,
    val isAnimating: Boolean = true,
    /** Optional speech bubble text (short status message) */
    val bubbleText: String? = null
)
