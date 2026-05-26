package com.hermie.assistant.modules.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

/**
 * Activity shown inside a notification bubble.
 * Displays the Hermie mascot with the screen time message.
 * Tapping dismisses the bubble.
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Time to take a break!"
        val moodName = intent?.getStringExtra(EXTRA_MOOD) ?: MascotMood.CONCERNED.name
        val mood = try { MascotMood.valueOf(moodName) } catch (_: Exception) { MascotMood.CONCERNED }

        // Check if launched as a bubble
        val isBubble = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isLaunchedFromBubble
        } else {
            true // Assume bubble on older APIs
        }

        setContent {
            AppTheme {
                BubbleContent(
                    mood = mood,
                    message = message,
                    onDismiss = {
                        if (isBubble) {
                            // Don't finish — let the bubble system handle it
                            moveTaskToBack(true)
                        } else {
                            finish()
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_MESSAGE = "bubble_message"
        const val EXTRA_MOOD = "bubble_mood"
    }
}

@Composable
private fun BubbleContent(
    mood: MascotMood,
    message: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = HermieSurface,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .widthIn(max = 300.dp),
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
                        fontFamily = HermieSerif,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = HermieForest,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                )

                Spacer(Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HermieForest,
                    modifier = Modifier.clickable { onDismiss() }
                ) {
                    Text(
                        text = "Got it",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HermieCream,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
