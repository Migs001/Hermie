package com.hermie.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext

/**
 * Offline text-to-speech using sherpa-onnx Piper VITS models.
 *
 * Supports sentence-boundary streaming: feed partial text as it arrives from the LLM,
 * and this engine will speak completed sentences while the LLM continues generating.
 */
class PiperTtsEngine {

    companion object {
        private const val TAG = "PiperTTS"
        // Characters that mark a sentence boundary for streaming TTS
        private val SENTENCE_DELIMITERS = charArrayOf('.', '?', '!', ':', ';', '\n')
    }

    enum class TtsState {
        IDLE, LOADING, READY, SPEAKING, ERROR
    }

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var sampleRate = 22050

    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Sentence queue for streaming TTS
    private val sentenceQueue = ConcurrentLinkedQueue<String>()
    private val playbackMutex = Mutex()

    @Volatile
    private var isStopped = false

    /**
     * Initialize the TTS engine with the Piper model directory.
     * The directory should contain: model.onnx, model.onnx.json, tokens.txt, espeak-ng-data/
     */
    suspend fun initialize(voiceModelDir: File) = withContext(Dispatchers.IO) {
        try {
            _state.value = TtsState.LOADING

            val modelFile = voiceModelDir.listFiles()
                ?.firstOrNull { it.name.endsWith(".onnx") && !it.name.endsWith(".json") }
                ?: throw IllegalStateException("No .onnx model file found in $voiceModelDir")

            val tokensFile = File(voiceModelDir, "tokens.txt")
            val espeakDir = File(voiceModelDir, "espeak-ng-data")

            if (!tokensFile.exists()) throw IllegalStateException("tokens.txt not found")
            if (!espeakDir.exists()) throw IllegalStateException("espeak-ng-data/ not found")

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDir.absolutePath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 2,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            tts = OfflineTts(config = config)
            sampleRate = tts!!.sampleRate()
            Log.d(TAG, "TTS initialized, sampleRate=$sampleRate")
            _state.value = TtsState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
            _error.value = e.message
            _state.value = TtsState.ERROR
        }
    }

    val isReady: Boolean get() = _state.value == TtsState.READY || _state.value == TtsState.SPEAKING

    /**
     * Speak a complete text string. Blocks until playback finishes or is stopped.
     */
    suspend fun speak(text: String) {
        if (tts == null || text.isBlank()) return
        isStopped = false
        _state.value = TtsState.SPEAKING

        withContext(Dispatchers.IO) {
            try {
                val audio = tts!!.generate(text = text, sid = 0, speed = 1.0f)
                if (!isStopped) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS generation error", e)
            } finally {
                if (_state.value == TtsState.SPEAKING) {
                    _state.value = TtsState.READY
                }
            }
        }
    }

    // ── Streaming TTS (sentence-boundary) ────────────────────

    private val streamBuffer = StringBuilder()

    /**
     * Feed tokens as they arrive from the LLM. When a sentence boundary is detected,
     * the completed sentence is queued for TTS playback.
     */
    fun feedToken(token: String) {
        streamBuffer.append(token)

        // Check for sentence boundaries
        val text = streamBuffer.toString()
        val lastBoundary = text.indexOfLast { it in SENTENCE_DELIMITERS }
        if (lastBoundary >= 0) {
            val sentence = text.substring(0, lastBoundary + 1).trim()
            if (sentence.isNotBlank()) {
                sentenceQueue.add(sentence)
            }
            streamBuffer.clear()
            if (lastBoundary + 1 < text.length) {
                streamBuffer.append(text.substring(lastBoundary + 1))
            }
        }
    }

    /**
     * Signal that the LLM has finished generating. Flushes any remaining text.
     */
    fun feedEnd() {
        val remaining = streamBuffer.toString().trim()
        if (remaining.isNotBlank()) {
            sentenceQueue.add(remaining)
        }
        streamBuffer.clear()
    }

    /**
     * Reset the streaming buffer and queue (e.g. when starting a new generation).
     */
    fun resetStream() {
        streamBuffer.clear()
        sentenceQueue.clear()
        isStopped = false
    }

    /**
     * Process the sentence queue, speaking each sentence in order.
     * Call this in a coroutine — it runs until the queue is drained and [feedEnd] was called,
     * or until [stop] is called.
     *
     * @param isGenerating lambda that returns true while the LLM is still producing tokens
     */
    suspend fun processQueue(isGenerating: () -> Boolean) {
        if (tts == null) {
            Log.w(TAG, "processQueue: tts is null, skipping")
            return
        }
        Log.d(TAG, "processQueue: starting, queue size=${sentenceQueue.size}")
        _state.value = TtsState.SPEAKING

        playbackMutex.withLock {
            try {
                while (coroutineContext.isActive && !isStopped) {
                    val sentence = sentenceQueue.poll()
                    if (sentence != null) {
                        Log.d(TAG, "Speaking: ${sentence.take(60)}...")
                        val audio = tts!!.generate(text = sentence, sid = 0, speed = 1.0f)
                        if (!isStopped) {
                            playAudio(audio.samples, audio.sampleRate)
                        }
                    } else if (!isGenerating()) {
                        // LLM done and queue empty — we're finished
                        break
                    } else {
                        // Queue empty but LLM still generating — wait a bit
                        kotlinx.coroutines.delay(50)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Queue processing error", e)
            } finally {
                if (_state.value == TtsState.SPEAKING) {
                    _state.value = TtsState.READY
                }
            }
        }
    }

    // ── Playback ─────────────────────────────────────────────

    private fun playAudio(samples: FloatArray, rate: Int) {
        if (samples.isEmpty() || isStopped) return

        val shortSamples = ShortArray(samples.size) { i ->
            (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        val bufSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(shortSamples.size * 2)

        Log.d(TAG, "playAudio: ${samples.size} samples at ${rate}Hz, duration=${samples.size * 1000L / rate}ms")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track

        try {
            track.write(shortSamples, 0, shortSamples.size)
            track.play()

            // Wait for playback to complete
            val durationMs = (shortSamples.size * 1000L) / rate
            val startTime = System.currentTimeMillis()
            while (!isStopped && (System.currentTimeMillis() - startTime) < durationMs + 100) {
                Thread.sleep(50)
            }

            track.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            track.release()
            audioTrack = null
        }
    }

    fun stop() {
        isStopped = true
        sentenceQueue.clear()
        streamBuffer.clear()
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        if (_state.value == TtsState.SPEAKING) {
            _state.value = TtsState.READY
        }
    }

    fun release() {
        stop()
        try {
            tts?.release()
        } catch (_: Exception) {}
        tts = null
        _state.value = TtsState.IDLE
    }
}
