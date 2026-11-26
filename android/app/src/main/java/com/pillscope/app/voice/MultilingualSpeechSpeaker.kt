package com.pillscope.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AssistantTtsRequestDto
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LanguagePreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SpeechPlaybackState(
    val isSpeaking: Boolean = false,
    val isLoadingCloudAudio: Boolean = false,
    val activeUtteranceId: String? = null,
    val engineReady: Boolean = false,
    val lastError: String? = null,
)

/**
 * Splits long multilingual text into natural sentence-bounded chunks for responsive Android TTS
 * playback, respecting both Latin punctuation (., !, ?) and Indic danda punctuation (।, ॥).
 */
fun splitTextForSpeech(rawText: String, maxChunkChars: Int = 360): List<String> {
    val cleaned = rawText
        .replace(Regex("[*_`#\\[\\]]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isEmpty()) return emptyList()
    if (cleaned.length <= maxChunkChars) return listOf(cleaned)

    val sentences = cleaned.split(Regex("(?<=[.!?।॥])\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flushCurrent() {
        val text = current.toString().trim()
        if (text.isNotEmpty()) chunks.add(text)
        current.setLength(0)
    }

    for (sentence in sentences) {
        if (sentence.length > maxChunkChars) {
            flushCurrent()
            var start = 0
            while (start < sentence.length) {
                val end = (start + maxChunkChars).coerceAtMost(sentence.length)
                val sliceEnd = if (end < sentence.length) {
                    val lastSpace = sentence.lastIndexOf(' ', end)
                    if (lastSpace > start + 40) lastSpace else end
                } else {
                    end
                }
                val part = sentence.substring(start, sliceEnd).trim()
                if (part.isNotEmpty()) chunks.add(part)
                start = sliceEnd
            }
        } else if (current.length + sentence.length + 1 > maxChunkChars) {
            flushCurrent()
            current.append(sentence)
        } else {
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
    }
    flushCurrent()
    return chunks
}

@Singleton
class MultilingualSpeechSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PillScopeApi,
    private val languageStore: LanguagePreferenceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SpeechPlaybackState())
    val state: StateFlow<SpeechPlaybackState> = _state.asStateFlow()

    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var pendingSpeakRequest: Triple<String, AppLanguage, String>? = null

    private var mediaPlayer: MediaPlayer? = null
    private var activeTempAudioFile: File? = null
    private var cloudJob: Job? = null
    private var lastChunkUtteranceId: String? = null

    init {
        initLocalTts()
    }

    private fun initLocalTts() {
        runCatching {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                _state.update {
                                    it.copy(
                                        isSpeaking = true,
                                        isLoadingCloudAudio = false,
                                        lastError = null,
                                    )
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == null || utteranceId == lastChunkUtteranceId) {
                                    _state.update {
                                        it.copy(isSpeaking = false, activeUtteranceId = null)
                                    }
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                _state.update {
                                    it.copy(
                                        isSpeaking = false,
                                        activeUtteranceId = null,
                                        lastError = "Device speech output interrupted.",
                                    )
                                }
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                _state.update {
                                    it.copy(
                                        isSpeaking = false,
                                        activeUtteranceId = null,
                                        lastError = "Device speech output error ($errorCode).",
                                    )
                                }
                            }
                        },
                    )
                    _state.update { it.copy(engineReady = true) }
                    pendingSpeakRequest?.let { (text, lang, id) ->
                        pendingSpeakRequest = null
                        speak(text = text, language = lang, utteranceId = id)
                    }
                } else {
                    ttsReady = false
                    _state.update { it.copy(engineReady = false) }
                }
            }
        }
    }

    fun isSpeakingUtterance(utteranceId: String): Boolean {
        val s = _state.value
        return (s.isSpeaking || s.isLoadingCloudAudio) && s.activeUtteranceId == utteranceId
    }

    fun toggleSpeak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = UUID.randomUUID().toString(),
    ) {
        if (isSpeakingUtterance(utteranceId)) {
            stop()
        } else {
            speak(text = text, language = language, utteranceId = utteranceId)
        }
    }

    fun speak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = UUID.randomUUID().toString(),
    ) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        stop()

        val preferCloud = languageStore.preferCloudVoice.value
        val deviceHasLanguage = isLanguageSupportedOnDevice(language)

        if (preferCloud || (!deviceHasLanguage && ttsReady)) {
            speakWithCloudFallbackToDevice(
                text = cleaned,
                language = language,
                utteranceId = utteranceId,
            )
            return
        }

        if (!ttsReady) {
            pendingSpeakRequest = Triple(cleaned, language, utteranceId)
            if (tts == null) initLocalTts()
            // Also try cloud synthesis if configured while local engine warms up
            if (preferCloud) {
                speakWithCloudFallbackToDevice(cleaned, language, utteranceId)
            }
            return
        }

        speakOnDevice(text = cleaned, language = language, utteranceId = utteranceId)
    }

    fun stop() {
        pendingSpeakRequest = null
        cloudJob?.cancel()
        cloudJob = null
        lastChunkUtteranceId = null
        runCatching { tts?.stop() }
        releaseMediaPlayer()
        _state.update {
            it.copy(
                isSpeaking = false,
                isLoadingCloudAudio = false,
                activeUtteranceId = null,
            )
        }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun isLanguageSupportedOnDevice(language: AppLanguage): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val result = runCatching { engine.isLanguageAvailable(language.locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private fun configureBestDeviceVoice(engine: TextToSpeech, language: AppLanguage) {
        val targetLocale = language.locale
        val langResult = runCatching { engine.setLanguage(targetLocale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        // Pick the clearest Indian voice matching this language when available
        runCatching {
            val matchingVoices = engine.voices.orEmpty().filter { voice ->
                voice.locale.language.equals(targetLocale.language, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired
            }
            val indiaPreferred = matchingVoices.firstOrNull {
                it.locale.country.equals("IN", ignoreCase = true)
            } ?: matchingVoices.firstOrNull()
            if (indiaPreferred != null) {
                engine.voice = indiaPreferred
            } else if (langResult < TextToSpeech.LANG_AVAILABLE) {
                // Fall back to language-only locale if exact IN country tag is unavailable
                engine.setLanguage(java.util.Locale.forLanguageTag(language.code))
            }
        }
        engine.setSpeechRate(languageStore.speechRate.value)
        engine.setPitch(1.0f)
    }

    private fun speakOnDevice(
        text: String,
        language: AppLanguage,
        utteranceId: String,
    ) {
        val engine = tts ?: return
        configureBestDeviceVoice(engine, language)

        val chunks = splitTextForSpeech(text)
        if (chunks.isEmpty()) return

        _state.update {
            it.copy(
                isSpeaking = true,
                isLoadingCloudAudio = false,
                activeUtteranceId = utteranceId,
                lastError = null,
            )
        }

        chunks.forEachIndexed { idx, chunk ->
            val chunkId = "${utteranceId}_$idx"
            if (idx == chunks.lastIndex) {
                lastChunkUtteranceId = chunkId
            }
            val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, queueMode, null, chunkId)
        }
    }

    private fun speakWithCloudFallbackToDevice(
        text: String,
        language: AppLanguage,
        utteranceId: String,
    ) {
        _state.update {
            it.copy(
                isSpeaking = false,
                isLoadingCloudAudio = true,
                activeUtteranceId = utteranceId,
                lastError = null,
            )
        }

        cloudJob = scope.launch {
            val audioFileResult = withContext(Dispatchers.IO) {
                runCatching {
                    val response = api.synthesizeAssistantSpeech(
                        AssistantTtsRequestDto(
                            text = text.take(1800),
                            language = language.code,
                        ),
                    )
                    if (!response.isSuccessful) {
                        error("Cloud TTS HTTP ${response.code()}")
                    }
                    val body = response.body() ?: error("Empty cloud TTS body")
                    val contentType = response.headers()["Content-Type"].orEmpty().lowercase()
                    val ext = if ("mpeg" in contentType || "mp3" in contentType) "mp3" else "wav"
                    val outFile = File(context.cacheDir, "pillscope_tts_${System.currentTimeMillis()}.$ext")
                    body.byteStream().use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (outFile.length() <= 0L) {
                        outFile.delete()
                        error("Empty audio bytes")
                    }
                    outFile
                }
            }

            val audioFile = audioFileResult.getOrNull()
            if (audioFile != null) {
                playAudioFile(audioFile, utteranceId)
            } else {
                // Cloud TTS not configured or offline -> fall back gracefully to device Android TTS
                if (ttsReady) {
                    speakOnDevice(text = text, language = language, utteranceId = utteranceId)
                } else {
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            isLoadingCloudAudio = false,
                            activeUtteranceId = null,
                            lastError = "Speech voice not installed for ${language.nativeName}.",
                        )
                    }
                }
            }
        }
    }

    private fun playAudioFile(file: File, utteranceId: String) {
        releaseMediaPlayer()
        activeTempAudioFile = file
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(languageStore.speechRate.value)
                    }
                    _state.update {
                        it.copy(
                            isSpeaking = true,
                            isLoadingCloudAudio = false,
                            activeUtteranceId = utteranceId,
                            lastError = null,
                        )
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    releaseMediaPlayer()
                    _state.update {
                        it.copy(isSpeaking = false, activeUtteranceId = null)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    releaseMediaPlayer()
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            isLoadingCloudAudio = false,
                            activeUtteranceId = null,
                            lastError = "Audio playback failed.",
                        )
                    }
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        }.onFailure {
            releaseMediaPlayer()
            _state.update {
                it.copy(
                    isSpeaking = false,
                    isLoadingCloudAudio = false,
                    activeUtteranceId = null,
                )
            }
        }
    }

    private fun releaseMediaPlayer() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
        runCatching { activeTempAudioFile?.delete() }
        activeTempAudioFile = null
    }
}
