package com.hermie.assistant.ui.mascot

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.theme.*

/**
 * Renders the Hermie mascot using Canvas drawing.
 * The mascot is a rounded character with expressive eyes and mouth.
 * Different moods change the eye/mouth shapes and add animations.
 */
@Composable
fun MascotView(
    state: MascotState,
    modifier: Modifier = Modifier,
    size: MascotSize = MascotSize.LARGE
) {
    val canvasSize = when (size) {
        MascotSize.SMALL -> 80.dp
        MascotSize.MEDIUM -> 140.dp
        MascotSize.LARGE -> 200.dp
        MascotSize.OVERLAY -> 56.dp
    }

    // Breathing animation
    val infiniteTransition = rememberInfiniteTransition(label = "mascot_anim")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Blink animation
    val blink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4000
                1f at 0
                1f at 3700
                0.1f at 3800
                1f at 3900
                1f at 4000
            }
        ),
        label = "blink"
    )

    // Bounce for excited
    val bounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutBack),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Speech bubble
        if (state.bubbleText != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieOffWhite,
                shadowElevation = 2.dp,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = state.bubbleText,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = HermieForest,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Canvas-drawn mascot
        Canvas(
            modifier = Modifier.size(canvasSize)
        ) {
            val w = this.size.width
            val h = this.size.height
            val breathOffset = breathe * (h * 0.015f)
            val bounceOffset = if (state.mood == MascotMood.EXCITED) bounce * (h * 0.05f) else 0f

            // Body (rounded rectangle)
            val bodyColor = HermieForest
            val bodyTop = h * 0.1f - bounceOffset + breathOffset
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(w * 0.1f, bodyTop),
                size = Size(w * 0.8f, h * 0.8f),
                cornerRadius = CornerRadius(w * 0.25f, w * 0.25f)
            )

            // Face features based on mood
            when (state.mood) {
                MascotMood.IDLE, MascotMood.HAPPY -> {
                    drawEyes(w, h, bodyTop, blink, HermieCream)
                    drawSmile(w, h, bodyTop, HermieCream)
                }
                MascotMood.THINKING -> {
                    drawEyes(w, h, bodyTop, blink, HermieCream, lookRight = true)
                    drawThinkingMouth(w, h, bodyTop, HermieCream)
                }
                MascotMood.TALKING -> {
                    drawEyes(w, h, bodyTop, blink, HermieCream)
                    drawTalkingMouth(w, h, bodyTop, breathe, HermieCream)
                }
                MascotMood.LISTENING -> {
                    drawEyes(w, h, bodyTop, 1f, HermieCream, wide = true)
                    drawListeningMouth(w, h, bodyTop, HermieCream)
                }
                MascotMood.EXCITED -> {
                    drawEyes(w, h, bodyTop - bounceOffset, blink, HermieCream, wide = true)
                    drawBigSmile(w, h, bodyTop - bounceOffset, HermieCream)
                }
                MascotMood.SLEEPY -> {
                    drawSleepyEyes(w, h, bodyTop, HermieCream)
                    drawSleepyMouth(w, h, bodyTop, HermieCream)
                }
                MascotMood.SURPRISED -> {
                    drawEyes(w, h, bodyTop, 1f, HermieCream, wide = true)
                    drawSurprisedMouth(w, h, bodyTop, HermieCream)
                }
                MascotMood.CONCERNED -> {
                    drawConcernedEyes(w, h, bodyTop, blink, HermieCream)
                    drawConcernedMouth(w, h, bodyTop, HermieCream)
                }
                MascotMood.WAVING -> {
                    drawEyes(w, h, bodyTop, blink, HermieCream)
                    drawBigSmile(w, h, bodyTop, HermieCream)
                    // Waving arm
                    drawCircle(
                        color = HermieTerra,
                        radius = w * 0.06f,
                        center = Offset(w * 0.85f + breathe * 5f, bodyTop + h * 0.25f - breathe * 8f)
                    )
                }
                MascotMood.ANNOYED -> {
                    drawAnnoyedEyes(w, h, bodyTop, HermieCream)
                    drawAnnoyedMouth(w, h, bodyTop, HermieCream)
                }
            }

            // Cheeks (subtle blush)
            if (state.mood == MascotMood.HAPPY || state.mood == MascotMood.EXCITED || state.mood == MascotMood.WAVING) {
                drawCircle(
                    color = HermieTerra.copy(alpha = 0.3f),
                    radius = w * 0.06f,
                    center = Offset(w * 0.25f, bodyTop + h * 0.5f)
                )
                drawCircle(
                    color = HermieTerra.copy(alpha = 0.3f),
                    radius = w * 0.06f,
                    center = Offset(w * 0.75f, bodyTop + h * 0.5f)
                )
            }
        }
    }
}

// ── Drawing helpers ──────────────────────────────────────────

private fun DrawScope.drawEyes(
    w: Float, h: Float, bodyTop: Float,
    blink: Float, color: Color,
    wide: Boolean = false, lookRight: Boolean = false
) {
    val eyeH = if (wide) h * 0.1f else h * 0.08f
    val eyeW = w * 0.09f
    val eyeY = bodyTop + h * 0.35f
    val xOffset = if (lookRight) w * 0.02f else 0f

    // Left eye
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.32f + xOffset, eyeY + eyeH * (1f - blink) / 2),
        size = Size(eyeW, eyeH * blink),
        cornerRadius = CornerRadius(eyeW / 2, eyeH * blink / 2)
    )
    // Right eye
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.59f + xOffset, eyeY + eyeH * (1f - blink) / 2),
        size = Size(eyeW, eyeH * blink),
        cornerRadius = CornerRadius(eyeW / 2, eyeH * blink / 2)
    )
}

private fun DrawScope.drawSmile(w: Float, h: Float, bodyTop: Float, color: Color) {
    val path = Path().apply {
        moveTo(w * 0.35f, bodyTop + h * 0.55f)
        quadraticBezierTo(w * 0.5f, bodyTop + h * 0.65f, w * 0.65f, bodyTop + h * 0.55f)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.02f))
}

private fun DrawScope.drawBigSmile(w: Float, h: Float, bodyTop: Float, color: Color) {
    val path = Path().apply {
        moveTo(w * 0.3f, bodyTop + h * 0.52f)
        quadraticBezierTo(w * 0.5f, bodyTop + h * 0.7f, w * 0.7f, bodyTop + h * 0.52f)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.025f))
}

private fun DrawScope.drawThinkingMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Small "o" shape, off-center
    drawCircle(
        color = color,
        radius = w * 0.035f,
        center = Offset(w * 0.55f, bodyTop + h * 0.58f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.015f)
    )
}

private fun DrawScope.drawTalkingMouth(w: Float, h: Float, bodyTop: Float, openness: Float, color: Color) {
    val mouthH = w * 0.04f + openness * w * 0.04f
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.42f, bodyTop + h * 0.55f),
        size = Size(w * 0.16f, mouthH),
        cornerRadius = CornerRadius(w * 0.08f, mouthH / 2)
    )
}

private fun DrawScope.drawListeningMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Small dot
    drawCircle(
        color = color,
        radius = w * 0.025f,
        center = Offset(w * 0.5f, bodyTop + h * 0.57f)
    )
}

private fun DrawScope.drawSleepyEyes(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Horizontal lines (closed eyes)
    val eyeY = bodyTop + h * 0.38f
    drawLine(color, Offset(w * 0.28f, eyeY), Offset(w * 0.42f, eyeY), strokeWidth = w * 0.02f)
    drawLine(color, Offset(w * 0.58f, eyeY), Offset(w * 0.72f, eyeY), strokeWidth = w * 0.02f)
}

private fun DrawScope.drawSleepyMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Slight wavy line
    val path = Path().apply {
        moveTo(w * 0.4f, bodyTop + h * 0.56f)
        quadraticBezierTo(w * 0.5f, bodyTop + h * 0.58f, w * 0.6f, bodyTop + h * 0.56f)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.015f))
}

private fun DrawScope.drawSurprisedMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    drawCircle(
        color = color,
        radius = w * 0.055f,
        center = Offset(w * 0.5f, bodyTop + h * 0.58f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.02f)
    )
}

private fun DrawScope.drawConcernedEyes(w: Float, h: Float, bodyTop: Float, blink: Float, color: Color) {
    drawEyes(w, h, bodyTop, blink, color)
    // Worried eyebrows (angled lines)
    val browY = bodyTop + h * 0.28f
    drawLine(color, Offset(w * 0.28f, browY + h * 0.03f), Offset(w * 0.42f, browY), strokeWidth = w * 0.015f)
    drawLine(color, Offset(w * 0.58f, browY), Offset(w * 0.72f, browY + h * 0.03f), strokeWidth = w * 0.015f)
}

private fun DrawScope.drawConcernedMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Slight frown
    val path = Path().apply {
        moveTo(w * 0.35f, bodyTop + h * 0.6f)
        quadraticBezierTo(w * 0.5f, bodyTop + h * 0.54f, w * 0.65f, bodyTop + h * 0.6f)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.02f))
}

private fun DrawScope.drawAnnoyedEyes(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Half-lidded / flat eyes
    val eyeY = bodyTop + h * 0.37f
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.3f, eyeY),
        size = Size(w * 0.12f, h * 0.04f),
        cornerRadius = CornerRadius(w * 0.06f, h * 0.02f)
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.58f, eyeY),
        size = Size(w * 0.12f, h * 0.04f),
        cornerRadius = CornerRadius(w * 0.06f, h * 0.02f)
    )
}

private fun DrawScope.drawAnnoyedMouth(w: Float, h: Float, bodyTop: Float, color: Color) {
    // Straight line
    drawLine(
        color,
        Offset(w * 0.38f, bodyTop + h * 0.57f),
        Offset(w * 0.62f, bodyTop + h * 0.57f),
        strokeWidth = w * 0.02f
    )
}

enum class MascotSize { SMALL, MEDIUM, LARGE, OVERLAY }
