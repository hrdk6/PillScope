package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.AssistantAskRequestDto
import com.pillscope.app.data.remote.dto.AssistantAskResponseDto
import com.pillscope.app.data.remote.dto.AssistantTtsRequestDto
import com.pillscope.app.data.remote.dto.AssistantTurnDto
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.AppStringsCatalog
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import com.pillscope.app.voice.SpeechTone
import com.pillscope.app.voice.computeTtsCacheKey
import com.pillscope.app.voice.sanitizeForDeviceSpeech
import com.pillscope.app.voice.wrapPcmAsWavIfNeeded
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AssistantAnswerOutcome(
    val response: AssistantAskResponseDto,
    val usedOfflineFallback: Boolean = false,
)

class TtsAudioDiskCache(
    private val cacheDir: File,
    private val maxBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    private val lock = Any()

    fun get(cacheKey: String): File? = synchronized(lock) {
        if (!cacheDir.exists()) return null
        val candidates = listOf(
            File(cacheDir, "$cacheKey.mp3"),
            File(cacheDir, "$cacheKey.wav"),
        )
        val hit = candidates.firstOrNull { it.isFile && it.length() > 0L } ?: return null
        hit.setLastModified(System.currentTimeMillis())
        hit
    }

    fun put(cacheKey: String, audioBytes: ByteArray, extension: String): File = synchronized(lock) {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val cleanExt = if (extension.equals("mp3", ignoreCase = true)) "mp3" else "wav"
        val target = File(cacheDir, "$cacheKey.$cleanExt")
        val temp = File(cacheDir, "$cacheKey.$cleanExt.tmp")
        temp.writeBytes(audioBytes)
        if (target.exists()) {
            target.delete()
        }
        if (!temp.renameTo(target)) {
            target.writeBytes(audioBytes)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        evictToLimit(protectedFile = target)
        target
    }

    private fun evictToLimit(protectedFile: File) {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= maxBytes) break
            if (file.absolutePath == protectedFile.absolutePath) continue
            val size = file.length()
            if (file.delete()) {
                totalSize -= size
            }
        }
    }

    companion object {
        const val CACHE_DIR_NAME = "tts_cache"
        const val DEFAULT_MAX_CACHE_BYTES = 25L * 1024L * 1024L
    }
}

@Singleton
class AssistantRepository @Inject constructor(
    private val api: PillScopeApi,
    private val medicationDao: MedicationDao,
) {
    suspend fun fetchOrCacheSpeechAudio(
        text: String,
        language: AppLanguage,
        tone: SpeechTone,
        speechRate: Float,
        cacheRootDir: File,
    ): Result<File> = runCatching {
        val sanitized = sanitizeForDeviceSpeech(text).take(1800)
        require(sanitized.isNotEmpty()) { "Cannot synthesize empty speech text." }

        val diskCache = TtsAudioDiskCache(File(cacheRootDir, TtsAudioDiskCache.CACHE_DIR_NAME))
        val cacheKey = computeTtsCacheKey(
            text = sanitized,
            language = language,
            tone = tone,
            speechRate = speechRate,
        )
        diskCache.get(cacheKey)?.let { cachedFile ->
            return@runCatching cachedFile
        }

        val response = api.synthesizeAssistantSpeech(
            AssistantTtsRequestDto(
                text = sanitized,
                language = language.code,
                tone = tone.apiValue,
                speechRate = speechRate,
            ),
        )
        if (!response.isSuccessful) {
            error("Cloud TTS HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty cloud TTS body")
        val rawBytes = body.use { it.bytes() }
        if (rawBytes.isEmpty()) {
            error("Empty audio bytes")
        }
        val contentType = response.headers()["Content-Type"].orEmpty()
        val (playableBytes, extension) = wrapPcmAsWavIfNeeded(rawBytes, contentType)
        diskCache.put(cacheKey, playableBytes, extension)
    }

    suspend fun askQuestion(
        question: String,
        language: AppLanguage,
        profile: UserProfile,
        scanId: String? = null,
        contextMedicineName: String? = null,
        contextSalts: List<String> = emptyList(),
        history: List<AssistantTurnDto> = emptyList(),
    ): AssistantAnswerOutcome {
        val serverProfileId = profile.id.takeIf {
            it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
        }
        val remoteResult = runCatching {
            api.askAssistant(
                AssistantAskRequestDto(
                    question = question,
                    language = language.code,
                    profileId = serverProfileId,
                    scanId = scanId,
                    contextMedicineName = contextMedicineName,
                    contextSalts = contextSalts,
                    conversationHistory = history.takeLast(6),
                ),
            )
        }
        remoteResult.getOrNull()?.let { remote ->
            return AssistantAnswerOutcome(response = remote, usedOfflineFallback = false)
        }

        return AssistantAnswerOutcome(
            response = buildOfflineLocalizedAnswer(
                question = question,
                language = language,
                profile = profile,
                contextSalts = contextSalts,
            ),
            usedOfflineFallback = true,
        )
    }

    private suspend fun buildOfflineLocalizedAnswer(
        question: String,
        language: AppLanguage,
        profile: UserProfile,
        contextSalts: List<String>,
    ): AssistantAskResponseDto {
        val activeMeds = runCatching {
            medicationDao.observeActiveMedicationsForProfile(profile.id).first()
        }.getOrDefault(emptyList())

        val extractedSalts = OnDeviceSaltChipExtractor.extractSaltChips(question).map { it.canonicalSalt }
        val candidateSalts = (contextSalts + extractedSalts)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        val cabinetSalts = activeMeds
            .flatMap { it.canonicalSaltKeysCsv.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        val allSalts = (candidateSalts + cabinetSalts).distinct()
        val allergyAlerts = OfflineAllergyChecker.detectConflicts(
            candidateSalts = allSalts,
            allergies = profile.allergyEntries.ifEmpty {
                profile.allergies.map { AllergyEntryDto(allergen = it) }
            },
        )
        val duplicateMatches = candidateSalts.flatMap { salt ->
            activeMeds.filter { med ->
                med.canonicalSaltKeysCsv.split(",").any { it.trim().equals(salt, ignoreCase = true) }
            }.map { med -> salt to med.displayName }
        }

        val locked = allergyAlerts.isNotEmpty() || duplicateMatches.isNotEmpty()
        val strings = AppStringsCatalog.forLanguage(language)
        val cabinetList = activeMeds.joinToString(", ") { it.displayName }

        val parts = mutableListOf<String>()
        if (locked) {
            parts.add("${strings.criticalSafetyAlert}:")
            allergyAlerts.forEach { alert ->
                parts.add("${alert.canonicalSalt} (${alert.allergen}): ${alert.reason}")
            }
            duplicateMatches.forEach { (salt, medName) ->
                parts.add("$salt — $medName")
            }
        } else if (cabinetList.isNotBlank()) {
            parts.add("${strings.medicationCabinetTitle} (${profile.name}): $cabinetList.")
            if (profile.allergies.isNotEmpty()) {
                parts.add("${strings.drugAllergiesTitle}: ${profile.allergies.joinToString(", ")}.")
            }
        } else {
            parts.add(strings.emptyCabinetMessage)
        }
        parts.add(strings.offlineAssistantNotice)

        val text = parts.joinToString("\n\n")
        val spoken = sanitizeForDeviceSpeech(parts.take(3).joinToString(" "))
        return AssistantAskResponseDto(
            answerText = text,
            spokenText = spoken,
            language = language.code,
            bcp47Locale = language.bcp47Tag,
            overallRisk = if (locked) "MAJOR" else "NONE",
            safetyBannerLocked = locked,
            ttsTone = if (locked) SpeechTone.URGENT.apiValue else SpeechTone.REASSURING.apiValue,
            referencedSalts = allSalts,
            suggestedFollowups = listOf(strings.starterQ1, strings.starterQ2, strings.starterQ3),
            citations = emptyList(),
            disclaimer = strings.medicalDisclaimerPoint1,
            provider = "offline_local",
        )
    }
}
