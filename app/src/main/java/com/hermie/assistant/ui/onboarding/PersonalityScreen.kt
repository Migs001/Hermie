package com.hermie.assistant.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.theme.*

@Composable
fun PersonalityScreen(
    jokeMessage: String,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var showJoke by remember { mutableStateOf(false) }

    // Fake slider values
    var friendliness by remember { mutableFloatStateOf(0.5f) }
    var humor by remember { mutableFloatStateOf(0.5f) }
    var seriousness by remember { mutableFloatStateOf(0.5f) }
    var creativity by remember { mutableFloatStateOf(0.5f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = HermieForest,
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.Start)
                .clickable(onClick = onBack)
        )

        Spacer(Modifier.height(24.dp))

        HermieSectionLabel("PERSONALITY")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Shape Hermie's\npersonality",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Adjust the sliders to your preference",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 15.sp,
                color = HermieGrey,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(32.dp))

        AnimatedVisibility(
            visible = !showJoke,
            exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
        ) {
            Column {
                PersonalitySlider("Friendliness", friendliness) { friendliness = it }
                Spacer(Modifier.height(20.dp))
                PersonalitySlider("Humor", humor) { humor = it }
                Spacer(Modifier.height(20.dp))
                PersonalitySlider("Seriousness", seriousness) { seriousness = it }
                Spacer(Modifier.height(20.dp))
                PersonalitySlider("Creativity", creativity) { creativity = it }
            }
        }

        AnimatedVisibility(
            visible = showJoke,
            enter = fadeIn(tween(500)) + expandVertically(tween(400))
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = HermieOffWhite),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "\uD83D\uDE0F",
                        fontSize = 48.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = jokeMessage,
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        HermieButton(
            text = if (showJoke) "Proceed" else "Apply",
            onClick = {
                if (showJoke) {
                    onNext()
                } else {
                    showJoke = true
                }
            },
            enabled = true, // Always enabled — sliders can be skipped
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

@Composable
private fun PersonalitySlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieForest
                )
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 14.sp,
                    color = HermieGrey
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = HermieTerra,
                activeTrackColor = HermieTerra,
                inactiveTrackColor = HermieTan.copy(alpha = 0.5f)
            )
        )
    }
}
