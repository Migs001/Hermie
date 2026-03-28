package com.hermie.assistant.ui.navigation

/**
 * All screens in the Hermie app.
 */
sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Home : Screen("home")
    data object Chat : Screen("chat")
    data object VoiceChat : Screen("voice_chat")
    data object Tasks : Screen("tasks")
    data object Settings : Screen("settings")
    data object ModuleDetail : Screen("module/{moduleId}") {
        fun withId(moduleId: String) = "module/$moduleId"
    }
}
