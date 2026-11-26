package com.pillscope.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.pillscope.app.data.repository.AssistantRepository
import com.pillscope.app.data.repository.RiskLevel
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeechTone(val apiValue: String) {
    REASSURING("reassuring"),
    CAUTION("caution"),
    URGENT("urgent"),
    ;

    companion object {
        fun fromRisk(overallRisk: String?, safetyBannerLocked: Boolean = false): SpeechTone {
            if (safetyBannerLocked) return URGENT
            return when (overallRisk?.trim()?.uppercase(Locale.US)) {
                "MAJOR" -> URGENT
                "MODERATE", "MINOR" -> CAUTION
                else -> REASSURING
            }
        }

        fun fromRisk(risk: RiskLevel, safetyBannerLocked: Boolean = false): SpeechTone {
            if (safetyBannerLocked || risk == RiskLevel.MAJOR) return URGENT
            if (risk == RiskLevel.MODERATE || risk == RiskLevel.MINOR) return CAUTION
            return REASSURING
        }

        fun fromApi(raw: String?): SpeechTone = when (raw?.trim()?.lowercase(Locale.US)) {
            "urgent" -> URGENT
            "caution" -> CAUTION
            else -> REASSURING
        }
    }
}

data class SpeechPlaybackState(
    val isSpeaking: Boolean = false,
    val isLoadingCloudAudio: Boolean = false,
    val activeUtteranceId: String? = null,
    val engineReady: Boolean = false,
    val lastError: String? = null,
)

data class DeviceVoiceCandidate(
    val name: String,
    val languageCode: String,
    val countryCode: String,
    val quality: Int,
    val requiresNetwork: Boolean,
)

private data class PendingSpeakRequest(
    val text: String,
    val language: AppLanguage,
    val utteranceId: String,
    val tone: SpeechTone,
)

private val SPEECH_MD_LINK_REGEX = Regex("\\[([^\\]]+)]\\((?:https?://|www\\.)[^)]+\\)", RegexOption.IGNORE_CASE)
private val SPEECH_URL_REGEX = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
private val SPEECH_UUID_REGEX = Regex(
    "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
    RegexOption.IGNORE_CASE,
)
private val SPEECH_CITATION_BRACKET_REGEX = Regex("\\[[^\\]]*]")
private val SPEECH_PAREN_REGEX = Regex("\\(\\s*([^()]*)\\s*\\)")
private val SPEECH_LEADING_BULLET_REGEX = Regex("(?m)^\\s*(?:[-•▪◦*]+|\\d+[.)])\\s+")
private val SPEECH_SYMBOLS_REGEX = Regex("[*_`#>~|@^\\\\{}<>]+")
private val SPEECH_MULTI_COMMA_REGEX = Regex("(?:,\\s*){2,}")
private val SPEECH_MULTI_PUNCT_REGEX = Regex("([.!?।॥])\\1+")
private val SPEECH_SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;:!?।॥])")
private val SPEECH_WHITESPACE_REGEX = Regex("\\s+")
private val PCM_RATE_REGEX = Regex("rate=(\\d{4,6})", RegexOption.IGNORE_CASE)
private val NEURAL_VOICE_TOKENS = listOf("neural", "network", "wavenet", "natural", "studio")

fun sanitizeForDeviceSpeech(rawText: String): String {
    var cleaned = SPEECH_MD_LINK_REGEX.replace(rawText, "$1")
    cleaned = SPEECH_URL_REGEX.replace(cleaned, " ")
    cleaned = SPEECH_UUID_REGEX.replace(cleaned, "")
    cleaned = SPEECH_CITATION_BRACKET_REGEX.replace(cleaned, " ")
    cleaned = SPEECH_LEADING_BULLET_REGEX.replace(cleaned, "")
    repeat(3) {
        val next = SPEECH_PAREN_REGEX.replace(cleaned) { match ->
            val inner = match.groupValues[1].trim()
            if (inner.isEmpty()) " " else ", $inner, "
        }
        cleaned = next
    }
    cleaned = cleaned
        .replace('(', ' ')
        .replace(')', ' ')
        .replace('[', ' ')
        .replace(']', ' ')
        .replace("•", ". ")
        .replace("▪", ". ")
        .replace("◦", ". ")
        .replace("—", ", ")
        .replace("–", ", ")
    cleaned = SPEECH_SYMBOLS_REGEX.replace(cleaned, " ")
    cleaned = SPEECH_MULTI_COMMA_REGEX.replace(cleaned, ", ")
    cleaned = SPEECH_MULTI_PUNCT_REGEX.replace(cleaned, "$1")
    cleaned = SPEECH_SPACE_BEFORE_PUNCT_REGEX.replace(cleaned, "$1")
    return SPEECH_WHITESPACE_REGEX.replace(cleaned, " ").trim()
}

fun splitTextForSpeech(rawText: String, maxChunkChars: Int = 360): List<String> {
    val cleaned = sanitizeForDeviceSpeech(rawText)
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

fun computeTtsCacheKey(
    text: String,
    language: AppLanguage,
    tone: SpeechTone,
    speechRate: Float,
): String {
    val normalized = sanitizeForDeviceSpeech(text)
    val payload = "$normalized|${language.code}|${tone.apiValue}|${"%.2f".format(Locale.US, speechRate)}"
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun wrapPcmAsWavIfNeeded(rawBytes: ByteArray, contentType: String): Pair<ByteArray, String> {
    val lowered = contentType.lowercase(Locale.US)
    if ("mpeg" in lowered || "mp3" in lowered) {
        return rawBytes to "mp3"
    }
    val hasRiffHeader = rawBytes.size >= 12 &&
        rawBytes[0] == 'R'.code.toByte() &&
        rawBytes[1] == 'I'.code.toByte() &&
        rawBytes[2] == 'F'.code.toByte() &&
        rawBytes[3] == 'F'.code.toByte()
    if (hasRiffHeader) {
        return rawBytes to "wav"
    }
    if ("l16" in lowered || "pcm" in lowered || "wav" in lowered) {
        val sampleRate = PCM_RATE_REGEX.find(lowered)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
        return buildWavFromPcm16Mono(rawBytes, sampleRate) to "wav"
    }
    return rawBytes to "wav"
}

private fun buildWavFromPcm16Mono(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcmBytes.size
    val chunkSize = 36 + dataSize

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(chunkSize)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
    }.array()

    return ByteArrayOutputStream(44 + dataSize).apply {
        write(header)
        write(pcmBytes)
    }.toByteArray()
}

private fun isNeuralVoiceName(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return NEURAL_VOICE_TOKENS.any { token -> token in lower }
}

fun rankDeviceVoices(
    candidates: List<DeviceVoiceCandidate>,
    targetLanguage: AppLanguage,
    isOnline: Boolean,
): DeviceVoiceCandidate? {
    val matching = candidates.filter {
        it.languageCode.equals(targetLanguage.code, ignoreCase = true)
    }
    if (matching.isEmpty()) return null

    return matching.maxByOrNull { candidate ->
        val isIndia = candidate.countryCode.equals("IN", ignoreCase = true)
        val isNeural = isNeuralVoiceName(candidate.name)
        val isVeryHigh = candidate.quality >= Voice.QUALITY_VERY_HIGH
        if (!isOnline) {
            val offlineScore = if (!candidate.requiresNetwork) 1_000_000 else 0
            val countryScore = if (isIndia) 100_000 else 0
            val veryHighBonus = if (isVeryHigh) 20_000 else 0
            val qualityScore = candidate.quality * 20
            val neuralScore = if (isNeural) 500 else 0
            offlineScore + countryScore + veryHighBonus + qualityScore + neuralScore
        } else {
            val countryScore = if (isIndia) 1_000_000 else 0
            val veryHighBonus = if (isVeryHigh) 200_000 else 0
            val qualityScore = candidate.quality * 200
            val indiaNeuralBonus = if (isIndia && (candidate.requiresNetwork || isNeural)) 50_000 else 0
            val generalNeuralBonus = if (candidate.requiresNetwork || isNeural) 5_000 else 0
            countryScore + veryHighBonus + qualityScore + indiaNeuralBonus + generalNeuralBonus
        }
    }
}

@Singleton
class MultilingualSpeechSpeaker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assistantRepository: AssistantRepository,
    private val languageStore: LanguagePreferenceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SpeechPlaybackState())
    val state: StateFlow<SpeechPlaybackState> = _state.asStateFlow()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false
    private var pendingSpeakRequest: PendingSpeakRequest? = null

    private var mediaPlayer: MediaPlayer? = null
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

                            @Suppress("OVERRIDE_DEPRECATION")
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
                    pendingSpeakRequest?.let { pending ->
                        pendingSpeakRequest = null
                        speak(
                            text = pending.text,
                            language = pending.language,
                            utteranceId = pending.utteranceId,
                            tone = pending.tone,
                        )
                    }
                } else {
                    ttsReady = false
                    _state.update { it.copy(engineReady = false) }
                }
            }
        }
    }

    fun isSpeakingUtterance(utteranceId: String): Boolean {
        val current = _state.value
        return (current.isSpeaking || current.isLoadingCloudAudio) &&
            current.activeUtteranceId == utteranceId
    }

    fun toggleSpeak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = UUID.randomUUID().toString(),
        tone: SpeechTone = SpeechTone.REASSURING,
    ) {
        if (isSpeakingUtterance(utteranceId)) {
            stop()
        } else {
            speak(text = text, language = language, utteranceId = utteranceId, tone = tone)
        }
    }

    fun speak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = UUID.randomUUID().toString(),
        tone: SpeechTone = SpeechTone.REASSURING,
    ) {
        val cleaned = sanitizeForDeviceSpeech(text)
        if (cleaned.isEmpty()) return
        stop()

        val preferCloud = languageStore.preferCloudNeuralVoice.value
        val deviceHasLanguage = isLanguageSupportedOnDevice(language)

        if (preferCloud || (!deviceHasLanguage && ttsReady)) {
            speakWithCloudFallbackToDevice(
                text = cleaned,
                language = language,
                utteranceId = utteranceId,
                tone = tone,
            )
            return
        }

        if (!ttsReady) {
            pendingSpeakRequest = PendingSpeakRequest(cleaned, language, utteranceId, tone)
            if (tts == null) initLocalTts()
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

    private fun isDeviceOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

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

        runCatching {
            val installedVoices = engine.voices.orEmpty().toList()
            val candidates = installedVoices.map { voice ->
                DeviceVoiceCandidate(
                    name = voice.name,
                    languageCode = voice.locale.language,
                    countryCode = voice.locale.country,
                    quality = voice.quality,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            val bestCandidate = rankDeviceVoices(
                candidates = candidates,
                targetLanguage = language,
                isOnline = isDeviceOnline(),
            )
            val bestVoice = bestCandidate?.let { picked ->
                installedVoices.firstOrNull { it.name == picked.name }
            }
            if (bestVoice != null) {
                engine.voice = bestVoice
            } else if (langResult < TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Locale.forLanguageTag(language.code))
            }
        }
        val configuredRate = languageStore.speechRate.value
        val offlineRate = (configuredRate * (OFFLINE_TTS_DEFAULT_RATE / LanguagePreferenceStore.DEFAULT_SPEECH_RATE))
            .coerceIn(LanguagePreferenceStore.MIN_SPEECH_RATE, 1.30f)
        engine.setSpeechRate(offlineRate)
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
        tone: SpeechTone,
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
            val rate = languageStore.speechRate.value
            val audioFileResult = withContext(Dispatchers.IO) {
                assistantRepository.fetchOrCacheSpeechAudio(
                    text = text,
                    language = language,
                    tone = tone,
                    speechRate = rate,
                    cacheRootDir = context.cacheDir,
                )
            }

            val audioFile = audioFileResult.getOrNull()
            if (audioFile != null) {
                playAudioFile(
                    file = audioFile,
                    utteranceId = utteranceId,
                    fallbackText = text,
                    fallbackLanguage = language,
                )
            } else {
                fallbackToDeviceOrNotify(text = text, language = language, utteranceId = utteranceId)
            }
        }
    }

    private fun fallbackToDeviceOrNotify(
        text: String,
        language: AppLanguage,
        utteranceId: String,
    ) {
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

    private fun playAudioFile(
        file: File,
        utteranceId: String,
        fallbackText: String,
        fallbackLanguage: AppLanguage,
    ) {
        releaseMediaPlayer()
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
                    fallbackToDeviceOrNotify(
                        text = fallbackText,
                        language = fallbackLanguage,
                        utteranceId = utteranceId,
                    )
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        }.onFailure {
            releaseMediaPlayer()
            fallbackToDeviceOrNotify(
                text = fallbackText,
                language = fallbackLanguage,
                utteranceId = utteranceId,
            )
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
    }

    companion object {
        const val OFFLINE_TTS_DEFAULT_RATE = 0.92f
    }
}
