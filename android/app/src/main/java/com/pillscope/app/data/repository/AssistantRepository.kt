package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.AssistantAskRequestDto
import com.pillscope.app.data.remote.dto.AssistantAskResponseDto
import com.pillscope.app.data.remote.dto.AssistantTurnDto
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.AppStringsCatalog
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class AssistantAnswerOutcome(
    val response: AssistantAskResponseDto,
    val usedOfflineFallback: Boolean = false,
)

@Singleton
class AssistantRepository @Inject constructor(
    private val api: PillScopeApi,
    private val medicationDao: MedicationDao,
) {
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

        // Offline / guest-mode localized safety fallback using Room cabinet & local allergy engine
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
        val spoken = parts.take(3).joinToString(" ")
        return AssistantAskResponseDto(
            answerText = text,
            spokenText = spoken,
            language = language.code,
            bcp47Locale = language.bcp47Tag,
            overallRisk = if (locked) "MAJOR" else "NONE",
            safetyBannerLocked = locked,
            referencedSalts = allSalts,
            suggestedFollowups = listOf(strings.starterQ1, strings.starterQ2, strings.starterQ3),
            citations = emptyList(),
            disclaimer = strings.medicalDisclaimerPoint1,
            provider = "offline_local",
        )
    }
}
