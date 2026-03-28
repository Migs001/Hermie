package com.hermie.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages speech recognition with wake word detection.
 * SpeechRecognizer MUST run on the main thread — all calls are posted to mainHandler.
 *
 * On API 33+ tries on-device recognizer first for better reliability.
 */
class SpeechManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(ListeningState.IDLE)
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    // Error message visible to UI so user knows what went wrong
    private val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    var onWakeWordDetected: () -> Unit = {}
    var onQueryRecognized: (String) -> Unit = {}

    private var isListeningForWakeWord = false
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3

    // On API 33+ prefer on-device recognizer (no network needed)
    private var useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Tracks whether we've tried both on-device and default recognizers for language errors
    private var triedBothRecognizers = false

    // Retry generation: incremented on each start/stop to cancel stale postDelayed callbacks
    private var retryGeneration = 0

    enum class ListeningState {
        IDLE,
        LISTENING_FOR_WAKE_WORD,
        LISTENING_FOR_QUERY,
        PROCESSING
    }

    fun startWakeWordListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition NOT available on this device")
            _sttError.value = "Speech recognition not available"
            return
        }

        isListeningForWakeWord = true
        consecutiveErrors = 0
        retryGeneration++  // cancel any pending retries from previous session
        triedBothRecognizers = false
        _sttError.value = null
        _state.value = ListeningState.LISTENING_FOR_WAKE_WORD
        Log.d(TAG, "Starting wake word listening (gen=$retryGeneration)")
        startRecognitionOnMainThread()
    }

    fun startQueryListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition NOT available on this device")
            _sttError.value = "Speech recognition not available"
            return
        }

        isListeningForWakeWord = false
        consecutiveErrors = 0
        retryGeneration++
        triedBothRecognizers = false
        _sttError.value = null
        _lastTranscript.value = ""
        _state.value = ListeningState.LISTENING_FOR_QUERY
        Log.d(TAG, "Starting query listening (direct mic, gen=$retryGeneration)")
        startRecognitionOnMainThread()
    }

    fun clearError() {
        _sttError.value = null
    }

    fun stopListening() {
        Log.d(TAG, "stopListening()")
        _state.value = ListeningState.IDLE
        _lastTranscript.value = ""
        isListeningForWakeWord = false
        retryGeneration++  // cancel pending retries
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.cancel()
        }
    }

    private fun startRecognitionOnMainThread() {
        mainHandler.post {
            startRecognition()
        }
    }

    private fun startRecognition() {
        // Must be called on main thread
        if (_state.value == ListeningState.IDLE) {
            Log.d(TAG, "startRecognition() skipped — state is IDLE (was cancelled)")
            return
        }

        try {
            recognizer?.destroy()
            recognizer = null

            // On API 33+ try on-device recognizer for better reliability (no network needed)
            recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && useOnDevice) {
                try {
                    Log.d(TAG, "Creating ON-DEVICE speech recognizer (API 33+)")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "On-device recognizer failed, falling back to default", e)
                    useOnDevice = false
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                Log.d(TAG, "Creating default speech recognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer?.setRecognitionListener(createListener())

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (!isListeningForWakeWord) {
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2500L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2000L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        3000L
                    )
                }
            }

            recognizer?.startListening(intent)
            Log.d(TAG, "startListening() called, wakeWord=$isListeningForWakeWord, onDevice=$useOnDevice")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            _state.value = ListeningState.IDLE
            _sttError.value = "Failed to start: ${e.message}"
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech — microphone is active")
            consecutiveErrors = 0
            _sttError.value = null
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech — user started talking")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech — user stopped talking")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            Log.d(TAG, "onResults: \"$text\" (${matches?.size ?: 0} matches)")

            if (isListeningForWakeWord) {
                if (containsWakeWord(text)) {
                    Log.d(TAG, "Wake word detected in: \"$text\"")
                    onWakeWordDetected()
                    val query = extractQueryAfterWakeWord(text)
                    if (query.isNotBlank()) {
                        _state.value = ListeningState.PROCESSING
                        onQueryRecognized(query)
                    } else {
                        startQueryListening()
                    }
                } else {
                    startRecognition()
                }
            } else {
                if (text.isNotBlank()) {
                    Log.d(TAG, "Query recognized: \"$text\"")
                    _state.value = ListeningState.PROCESSING
                    _lastTranscript.value = text
                    onQueryRecognized(text)
                } else {
                    Log.d(TAG, "Empty result in query mode, going idle")
                    _state.value = ListeningState.IDLE
                    _lastTranscript.value = ""
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches =
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                Log.d(TAG, "onPartialResults: \"$text\"")
                _lastTranscript.value = text
            }
        }

        override fun onError(error: Int) {
            val errorName = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                11 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
                13 -> "ERROR_LANGUAGE_UNAVAILABLE"
                else -> "UNKNOWN($error)"
            }
            Log.e(TAG, "onError: $errorName (code=$error)")

            // NO_MATCH and SPEECH_TIMEOUT are normal — user didn't say anything
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                if (_state.value != ListeningState.IDLE) {
                    Log.d(TAG, "Timeout/no match — restarting")
                    startRecognition()
                }
                return
            }

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.e(TAG, "Mic permission denied!")
                _state.value = ListeningState.IDLE
                _sttError.value = "Microphone permission needed"
                return
            }

            // Language errors (11, 13) — need offline language pack or internet
            if (error == 11 || error == 13) {
                if (!triedBothRecognizers) {
                    // Try the other recognizer type once
                    triedBothRecognizers = true
                    useOnDevice = !useOnDevice
                    Log.d(TAG, "Language error — trying ${if (useOnDevice) "on-device" else "default"} recognizer")
                    if (_state.value != ListeningState.IDLE) {
                        startRecognition()
                    }
                    return
                } else {
                    // Both recognizer types failed — need offline language pack
                    Log.e(TAG, "Both recognizers failed — offline language pack needed")
                    _state.value = ListeningState.IDLE
                    _sttError.value = "Wake word needs offline speech. Go to Settings > System > Languages > Speech > Offline speech recognition and download English."
                    return
                }
            }

            // Network errors → try on-device recognizer (works offline)
            if (error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !useOnDevice) {
                    Log.d(TAG, "Network error — switching to on-device recognizer")
                    useOnDevice = true
                    if (_state.value != ListeningState.IDLE) {
                        startRecognition()
                    }
                    return
                }
                _sttError.value =
                    "Voice needs internet or offline language. Go to Settings > Google > Voice > Offline to download English."
                Log.e(TAG, "Network/server error — likely needs offline language pack")
            }

            if (error == SpeechRecognizer.ERROR_CLIENT) {
                Log.e(TAG, "CLIENT error — recognizer may have been destroyed or busy")
            }

            // RECOGNIZER_BUSY: don't count as error, just wait and retry
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                Log.d(TAG, "Recognizer busy — will retry after delay")
                scheduleRetry(2000L)
                return
            }

            consecutiveErrors++
            // Wake word mode: be more resilient — back off but keep trying
            val limit = if (isListeningForWakeWord) maxConsecutiveErrors * 3 else maxConsecutiveErrors
            if (consecutiveErrors >= limit) {
                Log.e(TAG, "Too many consecutive errors ($consecutiveErrors), stopping")
                _state.value = ListeningState.IDLE
                if (_sttError.value == null) {
                    _sttError.value = "Speech error: $errorName"
                }
                return
            }

            if (_state.value != ListeningState.IDLE) {
                // Exponential back-off: 1s, 2s, 4s... capped at 5s
                val backoff = (1000L * (1 shl (consecutiveErrors - 1).coerceAtMost(2)))
                    .coerceAtMost(5000L)
                Log.d(TAG, "Retrying in ${backoff}ms (attempt $consecutiveErrors/$limit)")
                scheduleRetry(backoff)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Schedule a retry, cancelling any previous pending retry via generation token */
    private fun scheduleRetry(delayMs: Long) {
        val gen = retryGeneration
        mainHandler.postDelayed({
            // Only execute if this retry hasn't been superseded
            if (gen == retryGeneration && _state.value != ListeningState.IDLE) {
                startRecognition()
            } else {
                Log.d(TAG, "Stale retry cancelled (gen=$gen, current=$retryGeneration)")
            }
        }, delayMs)
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("hermie") ||
                lower.contains("hermy") ||
                lower.contains("her me") ||
                lower.contains("hey hermie")
    }

    private fun extractQueryAfterWakeWord(text: String): String {
        val lower = text.lowercase()
        val patterns = listOf("hey hermie ", "hermie ", "hermy ", "her me ")
        for (pattern in patterns) {
            val index = lower.indexOf(pattern)
            if (index >= 0) {
                return text.substring(index + pattern.length).trim()
            }
        }
        return ""
    }

    fun release() {
        stopListening()
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    companion object {
        private const val TAG = "SpeechManager"
    }
}
