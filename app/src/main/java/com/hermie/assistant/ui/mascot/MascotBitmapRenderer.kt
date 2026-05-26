package com.hermie.assistant.ui.mascot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

// Hermie palette as ARGB ints (matches Color.kt)
private const val COLOR_FOREST = 0xFF344C3D.toInt()   // body
private const val COLOR_CREAM  = 0xFFFFFEFC.toInt()   // eyes / mouth
private const val COLOR_TERRA  = 0xFFB57B66.toInt()   // cheeks

// Cache: one bitmap per mood, populated on first render
private val cache = mutableMapOf<MascotMood, Bitmap>()

/**
 * Render a static mascot frame to a Bitmap suitable for use as an adaptive notification
 * icon. The mascot occupies the inner ~66% safe zone; the outer ring is transparent so
 * the system's adaptive icon mask applies cleanly.
 *
 * No Compose imports — pure android.graphics.
 */
fun renderMascotBitmap(mood: MascotMood, sizePx: Int): Bitmap {
    cache[mood]?.let { return it }

    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Inner safe zone: 66% of canvas, centred
    val innerSize = sizePx * 0.66f
    val offset = (sizePx - innerSize) / 2f
    val w = innerSize
    val h = innerSize

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Body (rounded rectangle) — bodyTop = offset + h*0.1
    val bodyTop   = offset + h * 0.1f
    val bodyLeft  = offset + w * 0.1f
    val bodyRight = offset + w * 0.9f
    val bodyBot   = offset + h * 0.9f
    val rx = w * 0.25f
    fillPaint.color = COLOR_FOREST
    canvas.drawRoundRect(RectF(bodyLeft, bodyTop, bodyRight, bodyBot), rx, rx, fillPaint)

    // Face features per mood
    when (mood) {
        MascotMood.HAPPY, MascotMood.IDLE -> {
            drawEyes(canvas, w, h, offset, bodyTop, strokePaint, fillPaint)
            drawSmile(canvas, w, h, offset, bodyTop, strokePaint)
        }
        MascotMood.CONCERNED -> {
            drawEyes(canvas, w, h, offset, bodyTop, strokePaint, fillPaint)
            drawConcernedBrows(canvas, w, h, offset, bodyTop, strokePaint)
            drawConcernedMouth(canvas, w, h, offset, bodyTop, strokePaint)
        }
        MascotMood.ANNOYED -> {
            drawAnnoyedEyes(canvas, w, h, offset, bodyTop, fillPaint)
            drawAnnoyedMouth(canvas, w, h, offset, bodyTop, strokePaint)
        }
        else -> {
            // Fallback to happy for unsupported moods
            drawEyes(canvas, w, h, offset, bodyTop, strokePaint, fillPaint)
            drawSmile(canvas, w, h, offset, bodyTop, strokePaint)
        }
    }

    cache[mood] = bmp
    return bmp
}

// ── Drawing helpers ──────────────────────────────────────────────────────────
// All coordinates use `offset` as the origin of the inner safe zone.
// `w` and `h` are the safe zone dimensions (equal = innerSize).

private fun drawEyes(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float,
    strokePaint: Paint, fillPaint: Paint
) {
    val eyeH = h * 0.08f
    val eyeW = w * 0.09f
    val eyeY = bodyTop + h * 0.35f

    fillPaint.color = COLOR_CREAM

    // Left eye
    canvas.drawRoundRect(
        RectF(offset + w * 0.32f, eyeY, offset + w * 0.32f + eyeW, eyeY + eyeH),
        eyeW / 2, eyeH / 2, fillPaint
    )
    // Right eye
    canvas.drawRoundRect(
        RectF(offset + w * 0.59f, eyeY, offset + w * 0.59f + eyeW, eyeY + eyeH),
        eyeW / 2, eyeH / 2, fillPaint
    )
}

private fun drawSmile(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float, strokePaint: Paint
) {
    strokePaint.color = COLOR_CREAM
    strokePaint.strokeWidth = w * 0.02f
    val path = Path().apply {
        moveTo(offset + w * 0.35f, bodyTop + h * 0.55f)
        quadTo(offset + w * 0.5f, bodyTop + h * 0.65f, offset + w * 0.65f, bodyTop + h * 0.55f)
    }
    canvas.drawPath(path, strokePaint)
}

private fun drawConcernedBrows(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float, strokePaint: Paint
) {
    strokePaint.color = COLOR_CREAM
    strokePaint.strokeWidth = w * 0.015f
    val browY = bodyTop + h * 0.28f
    canvas.drawLine(
        offset + w * 0.28f, browY + h * 0.03f,
        offset + w * 0.42f, browY,
        strokePaint
    )
    canvas.drawLine(
        offset + w * 0.58f, browY,
        offset + w * 0.72f, browY + h * 0.03f,
        strokePaint
    )
}

private fun drawConcernedMouth(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float, strokePaint: Paint
) {
    strokePaint.color = COLOR_CREAM
    strokePaint.strokeWidth = w * 0.02f
    val path = Path().apply {
        moveTo(offset + w * 0.35f, bodyTop + h * 0.6f)
        quadTo(offset + w * 0.5f, bodyTop + h * 0.54f, offset + w * 0.65f, bodyTop + h * 0.6f)
    }
    canvas.drawPath(path, strokePaint)
}

private fun drawAnnoyedEyes(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float, fillPaint: Paint
) {
    val eyeY = bodyTop + h * 0.37f
    fillPaint.color = COLOR_CREAM

    canvas.drawRoundRect(
        RectF(offset + w * 0.3f, eyeY, offset + w * 0.3f + w * 0.12f, eyeY + h * 0.04f),
        w * 0.06f, h * 0.02f, fillPaint
    )
    canvas.drawRoundRect(
        RectF(offset + w * 0.58f, eyeY, offset + w * 0.58f + w * 0.12f, eyeY + h * 0.04f),
        w * 0.06f, h * 0.02f, fillPaint
    )
}

private fun drawAnnoyedMouth(
    canvas: Canvas, w: Float, h: Float, offset: Float, bodyTop: Float, strokePaint: Paint
) {
    strokePaint.color = COLOR_CREAM
    strokePaint.strokeWidth = w * 0.02f
    canvas.drawLine(
        offset + w * 0.38f, bodyTop + h * 0.57f,
        offset + w * 0.62f, bodyTop + h * 0.57f,
        strokePaint
    )
}
