package com.pillscope.app.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pillscope.app.i18n.AppLanguage

@Stable
class VoiceInputState(
    val startListening: () -> Unit,
    val stopListening: () -> Unit,
    private val isListeningProvider: () -> Boolean,
    private val partialTextProvider: () -> String,
    private val rmsDbProvider: () -> Float,
    private val errorProvider: () -> String?,
    val clearError: () -> Unit,
) {
    val isListening: Boolean get() = isListeningProvider()
    val partialTranscript: String get() = partialTextProvider()
    val rmsDb: Float get() = rmsDbProvider()
    val errorMessage: String? get() = errorProvider()
}

/**
 * Composable voice capture controller supporting all 10 PillScope Indian languages via Android
 * SpeechRecognizer with automatic fallback to system RecognizerIntent dialog when inline
 * SpeechRecognizer is unavailable on a device.
 */
@Composable
fun rememberVoiceInputController(
    language: AppLanguage,
    promptText: String,
    onResult: (String) -> Unit,
): VoiceInputState {
    val context = LocalContext.current
    val latestLanguage by rememberUpdatedState(language)
    val latestPrompt by rememberUpdatedState(promptText)
    val latestOnResult by rememberUpdatedState(onResult)

    var isListening by remember { mutableStateOf(false) }
    var partialTranscript by remember { mutableStateOf("") }
    var rmsDb by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun buildSpeechIntent(lang: AppLanguage): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.bcp47Tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.bcp47Tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, latestPrompt)
    }

    val intentFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        isListening = false
        partialTranscript = ""
        rmsDb = 0f
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val matches = activityResult.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            if (best.isNotEmpty()) {
                errorMessage = null
                latestOnResult(best)
            }
        }
    }

    fun launchSystemSpeechDialog() {
        runCatching {
            isListening = true
            errorMessage = null
            intentFallbackLauncher.launch(buildSpeechIntent(latestLanguage))
        }.onFailure {
            isListening = false
            errorMessage = "Speech recognition is not installed on this device."
        }
    }

    fun startInlineRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            launchSystemSpeechDialog()
            return
        }

        val sr = recognizer ?: runCatching {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()?.also { recognizer = it }

        if (sr == null) {
            launchSystemSpeechDialog()
            return
        }

        sr.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    errorMessage = null
                }

                override fun onBeginningOfSpeech() {
                    isListening = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    rmsDb = rmsdB.coerceIn(0f, 12f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    isListening = false
                    rmsDb = 0f
                }

                override fun onError(error: Int) {
                    isListening = false
                    rmsDb = 0f
                    partialTranscript = ""
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> errorMessage = "Could not catch that — please tap mic and speak clearly."
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        -> errorMessage = "Microphone access is required for voice input."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            runCatching { sr.cancel() }
                            errorMessage = "Voice recognizer busy — tap mic again."
                        }
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        -> launchSystemSpeechDialog()
                        else -> errorMessage = "Voice input could not start ($error)."
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    rmsDb = 0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull()?.trim().orEmpty().ifEmpty { partialTranscript.trim() }
                    partialTranscript = ""
                    if (best.isNotEmpty()) {
                        errorMessage = null
                        latestOnResult(best)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        partialTranscript = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )

        errorMessage = null
        partialTranscript = ""
        isListening = true
        runCatching {
            sr.startListening(buildSpeechIntent(latestLanguage))
        }.onFailure {
            launchSystemSpeechDialog()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startInlineRecognition()
        } else {
            errorMessage = "Microphone permission denied. Enable microphone access to use voice."
        }
    }

    val startListening = remember(context) {
        {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                startInlineRecognition()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val stopListening = remember {
        {
            isListening = false
            rmsDb = 0f
            runCatching { recognizer?.stopListening() }
            Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }

    return remember(startListening, stopListening) {
        VoiceInputState(
            startListening = startListening,
            stopListening = stopListening,
            isListeningProvider = { isListening },
            partialTextProvider = { partialTranscript },
            rmsDbProvider = { rmsDb },
            errorProvider = { errorMessage },
            clearError = { errorMessage = null },
        )
    }
}
