package com.hermie.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays synthesized thinking sounds to mask LLM inference latency.
 * Uses AudioTrack to generate robotic beeps and hums — no WAV files needed.
 */
class ThinkingAudioManager(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {

    private var loopJob: Job? = null
    private var isPlaying = false
    private var currentTrack: AudioTrack? = null

    private val sampleRate = 22050

    fun startThinking(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true

        loopJob = scope.launch(Dispatchers.IO) {
            var clipIndex = 0
            while (isActive && isPlaying) {
                when (clipIndex % 3) {
                    0 -> playTone(frequency = 440f, durationMs = 150, volume = 0.3f)
                    1 -> playTone(frequency = 330f, durationMs = 200, volume = 0.2f)
                    2 -> playSweep(startHz = 300f, endHz = 600f, durationMs = 250, volume = 0.25f)
                }
                clipIndex++
                delay(1200)
            }
        }
    }

    fun stopThinking() {
        isPlaying = false
        loopJob?.cancel()
        loopJob = null
        try {
            currentTrack?.stop()
        } catch (_: Exception) { }
        try {
            currentTrack?.release()
        } catch (_: Exception) { }
        currentTrack = null
    }

    private fun playTone(frequency: Float, durationMs: Int, volume: Float) {
        if (!isPlaying) return
        val numSamples = sampleRate * durationMs / 1000
        val samples = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            // Sine wave with fade-in/fade-out envelope
            val envelope = fadeEnvelope(i, numSamples)
            val sample = (sin(2.0 * PI * frequency * t) * Short.MAX_VALUE * volume * envelope).toInt()
            samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playShortArray(samples)
    }

    private fun playSweep(startHz: Float, endHz: Float, durationMs: Int, volume: Float) {
        if (!isPlaying) return
        val numSamples = sampleRate * durationMs / 1000
        val samples = ShortArray(numSamples)

        var phase = 0.0
        for (i in 0 until numSamples) {
            val progress = i.toFloat() / numSamples
            val freq = startHz + (endHz - startHz) * progress
            val envelope = fadeEnvelope(i, numSamples)
            phase += 2.0 * PI * freq / sampleRate
            val sample = (sin(phase) * Short.MAX_VALUE * volume * envelope).toInt()
            samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playShortArray(samples)
    }

    private fun fadeEnvelope(index: Int, total: Int): Float {
        val fadeLen = (total * 0.15f).toInt().coerceAtLeast(1)
        return when {
            index < fadeLen -> index.toFloat() / fadeLen
            index > total - fadeLen -> (total - index).toFloat() / fadeLen
            else -> 1f
        }
    }

    private fun playShortArray(samples: ShortArray) {
        try {
            val bufferSize = samples.size * 2 // 2 bytes per short
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            currentTrack = track
            track.write(samples, 0, samples.size)
            track.play()

            // Wait for playback to finish
            val durationMs = samples.size * 1000L / sampleRate
            Thread.sleep(durationMs + 50)

            try { track.stop() } catch (_: Exception) { }
            try { track.release() } catch (_: Exception) { }
            if (currentTrack === track) currentTrack = null
        } catch (_: Exception) {
            // Audio is non-critical
        }
    }

    fun release() {
        stopThinking()
    }
}
