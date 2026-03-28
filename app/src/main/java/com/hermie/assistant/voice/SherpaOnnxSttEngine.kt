package com.hermie.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline speech-to-text using sherpa-onnx Whisper models.
 * Records audio from microphone, runs Whisper inference on-device.
 */
class SherpaOnnxSttEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _state = MutableStateFlow(SttState.IDLE)
    val state: StateFlow<SttState> = _state.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var onQueryRecognized: (String) -> Unit = {}
    var onSilenceTimeout: () -> Unit = {}

    enum class SttState {
        IDLE, LISTENING, PROCESSING
    }

    suspend fun initialize(modelDir: String) = withContext(Dispatchers.IO) {
        try {
            val encoderFile = File(modelDir, "tiny.en-encoder.int8.onnx")
            val decoderFile = File(modelDir, "tiny.en-decoder.int8.onnx")
            val tokensFile = File(modelDir, "tiny.en-tokens.txt")

            if (!encoderFile.exists() || !decoderFile.exists() || !tokensFile.exists()) {
                Log.e(TAG, "Whisper model files not found in $modelDir")
                return@withContext
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        language = "en",
                        task = "transcribe",
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )

            recognizer = OfflineRecognizer(config = config)
            _isReady.value = true
            Log.d(TAG, "Whisper STT initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Whisper STT", e)
            _error.value = "STT init failed: ${e.message}"
        }
    }

    fun startListening() {
        if (!_isReady.value || recognizer == null) {
            _error.value = "STT not ready"
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _error.value = "Microphone permission needed"
            return
        }

        _error.value = null
        _lastTranscript.value = ""
        _state.value = SttState.LISTENING
        isRecording = true

        Thread {
            recordAndRecognize()
        }.start()
    }

    fun stopListening() {
        isRecording = false
    }

    fun clearError() {
        _error.value = null
    }

    private fun recordAndRecognize() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(bufferSize, SAMPLE_RATE * 4) // At least 1 second buffer
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _error.value = "Failed to open microphone"
                _state.value = SttState.IDLE
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Recording started")

            val allSamples = mutableListOf<Float>()
            val readBuffer = FloatArray(SAMPLE_RATE / 10) // 100ms chunks
            var silenceFrames = 0
            val maxSilenceFrames = 20 // 2 seconds of silence = stop

            while (isRecording) {
                val read = audioRecord?.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING) ?: -1
                if (read > 0) {
                    for (i in 0 until read) {
                        allSamples.add(readBuffer[i])
                    }

                    // Simple silence detection (RMS)
                    val rms = Math.sqrt(readBuffer.take(read).map { it * it.toDouble() }.average())
                    if (rms < SILENCE_THRESHOLD) {
                        silenceFrames++
                        if (silenceFrames >= maxSilenceFrames && allSamples.size > SAMPLE_RATE) {
                            // Enough audio + silence detected, stop
                            break
                        }
                    } else {
                        silenceFrames = 0
                    }

                    // Max recording length: 30 seconds
                    if (allSamples.size > SAMPLE_RATE * 30) break
                }
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (allSamples.size < SAMPLE_RATE / 2) {
                // Less than 0.5 seconds of audio — no meaningful speech
                Log.d(TAG, "Too short, ignoring (silence timeout)")
                _state.value = SttState.IDLE
                onSilenceTimeout()
                return
            }

            _state.value = SttState.PROCESSING
            Log.d(TAG, "Processing ${allSamples.size} samples (${allSamples.size / SAMPLE_RATE.toFloat()}s)")

            val samples = allSamples.toFloatArray()
            val result = runRecognition(samples)

            // Filter out Whisper silence/noise artifacts
            val cleaned = result
                .replace(Regex("""\[.*?]"""), "")  // [Silence], [BLANK_AUDIO], etc.
                .replace(Regex("""\(.*?\)"""), "")  // (silence), (music), etc.
                .trim()

            if (cleaned.isNotBlank()) {
                Log.d(TAG, "Recognized: \"$cleaned\"")
                _lastTranscript.value = cleaned
                _state.value = SttState.IDLE
                onQueryRecognized(cleaned)
            } else {
                Log.d(TAG, "Empty/silence result: \"$result\"")
                _state.value = SttState.IDLE
                onSilenceTimeout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording/recognition error", e)
            _error.value = "STT error: ${e.message}"
            _state.value = SttState.IDLE
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
        }
    }

    private fun runRecognition(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    fun release() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recognizer?.release()
        recognizer = null
        _isReady.value = false
    }

    companion object {
        private const val TAG = "SherpaOnnxStt"
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_THRESHOLD = 0.01
    }
}
