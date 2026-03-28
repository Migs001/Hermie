package com.hermie.assistant.modules.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.mascot.MascotMood
import com.hermie.assistant.ui.mascot.MascotSize
import com.hermie.assistant.ui.mascot.MascotState
import com.hermie.assistant.ui.mascot.MascotView
import com.hermie.assistant.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Full overlay popup showing the mascot character with a message.
 * Auto-dismisses after a timeout, or tap to dismiss.
 */
@Composable
fun OverlayPopup(
    mood: MascotMood,
    message: String,
    onDismiss: () -> Unit,
    autoDismissMs: Long = 5000
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(autoDismissMs)
        visible = false
        delay(400) // Wait for exit animation
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(400)) + fadeIn(tween(400)),
        exit = scaleOut(tween(300)) + fadeOut(tween(300))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .clickable {
                    visible = false
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = HermieSurface,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .widthIn(max = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MascotView(
                        state = MascotState(mood = mood),
                        size = MascotSize.LARGE
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = message,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Tap to dismiss",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = HermieGrey
                        )
                    )
                }
            }
        }
    }
}
