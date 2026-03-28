package com.hermie.assistant.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.llm.ModelManager
import com.hermie.assistant.ui.components.OnboardingProgress
import com.hermie.assistant.ui.theme.HermieSurface

/**
 * Onboarding flow: Gender → Name → Personality (joke) → Model Download.
 * Clean, minimal, inspired by reference image 1.
 */
@Composable
fun OnboardingScreen(
    settings: HermieSettings,
    modelManager: ModelManager,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var gender by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Progress dots at top
        OnboardingProgress(
            currentStep = currentStep,
            totalSteps = 4,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .align(Alignment.CenterHorizontally)
        )

        // Animated step content
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally(tween(350)) { it / 2 } + fadeIn(tween(350)) togetherWith
                        slideOutHorizontally(tween(350)) { -it / 2 } + fadeOut(tween(250))
                } else {
                    slideInHorizontally(tween(350)) { -it / 2 } + fadeIn(tween(350)) togetherWith
                        slideOutHorizontally(tween(350)) { it / 2 } + fadeOut(tween(250))
                }
            },
            label = "onboarding_step",
            modifier = Modifier.fillMaxSize()
        ) { step ->
            when (step) {
                0 -> GenderScreen(
                    selectedGender = gender,
                    onGenderSelected = { gender = it },
                    onNext = { currentStep = 1 }
                )
                1 -> NameScreen(
                    name = name,
                    onNameChanged = { name = it },
                    onBack = { currentStep = 0 },
                    onNext = {
                        settings.userName = name
                        settings.userGender = gender
                        currentStep = 2
                    }
                )
                2 -> PersonalityScreen(
                    jokeMessage = settings.personalityJokeMessage,
                    onBack = { currentStep = 1 },
                    onNext = { currentStep = 3 }
                )
                3 -> ModelDownloadScreen(
                    modelManager = modelManager,
                    settings = settings,
                    onBack = { currentStep = 2 },
                    onComplete = {
                        settings.completeOnboarding()
                        onComplete()
                    }
                )
            }
        }
    }
}
