package com.pillscope.app.data.repository

import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.ClinicalSummaryDto
import com.pillscope.app.data.remote.dto.DeterministicDdiAlertDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.LifestyleTriggerDto
import com.pillscope.app.data.remote.dto.MedicationRecallFlagDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.sse.ScanSseEvent

enum class RiskLevel(val rank: Int) {
    NONE(0),
    MINOR(1),
    MODERATE(2),
    MAJOR(3),
    ;

    companion object {
        fun fromApi(raw: String?): RiskLevel = when (raw?.trim()?.uppercase()) {
            "MAJOR" -> MAJOR
            "MODERATE" -> MODERATE
            "MINOR" -> MINOR
            else -> NONE
        }

        fun max(a: RiskLevel, b: RiskLevel): RiskLevel = if (a.rank >= b.rank) a else b
    }
}

enum class AnalysisStage {
    IDLE,
    CONNECTING,
    DETERMINISTIC_READY,
    ML_READY,
    CITATIONS_READY,
    SUMMARY_READY,
    COMPLETE,
    ERROR,
}

data class ScanAnalysisState(
    val scanId: String? = null,
    val profileId: String? = null,
    val persisted: Boolean = true,
    val submittedQuery: String = "",
    val resolvedSalts: List<String> = emptyList(),
    val stage: AnalysisStage = AnalysisStage.IDLE,
    val overallRisk: RiskLevel = RiskLevel.NONE,
    val safetyBannerLocked: Boolean = false,
    val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    val allergyAlerts: List<AllergyAlertDto> = emptyList(),
    val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
    val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
    val activeRecalls: List<ProfileRecallAlertItemDto> = emptyList(),
    val interactions: List<InteractionItemDto> = emptyList(),
    val mlScoredPairCount: Int = 0,
    val citations: List<MonographCitationDto> = emptyList(),
    val clinicalSummary: ClinicalSummaryDto? = null,
    val citationVerified: Boolean = false,
    val blockedCitationIds: List<String> = emptyList(),
    val latencyMs: Int? = null,
    val errorMessage: String? = null,
    val fromOfflineCache: Boolean = false,
)

object ScanAnalysisReducer {
    fun reduce(current: ScanAnalysisState, event: ScanSseEvent): ScanAnalysisState {
        return when (event) {
            is ScanSseEvent.DeterministicSafety -> {
                val p = event.payload
                val hasHardTrigger = p.safetyBannerLocked ||
                    p.duplicateSalts.isNotEmpty() ||
                    p.allergyAlerts.isNotEmpty() ||
                    p.deterministicAlerts.any { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
                val nextLocked = current.safetyBannerLocked || hasHardTrigger
                val baseRisk = RiskLevel.max(current.overallRisk, RiskLevel.fromApi(p.overallRisk))
                val nextRisk = if (nextLocked) RiskLevel.MAJOR else baseRisk

                current.copy(
                    scanId = p.scanId,
                    profileId = p.profileId ?: current.profileId,
                    persisted = p.persisted,
                    submittedQuery = p.submittedQuery,
                    resolvedSalts = p.resolvedSalts,
                    stage = AnalysisStage.DETERMINISTIC_READY,
                    overallRisk = nextRisk,
                    safetyBannerLocked = nextLocked,
                    duplicateSalts = p.duplicateSalts,
                    allergyAlerts = p.allergyAlerts,
                    deterministicAlerts = p.deterministicAlerts,
                    activeRecalls = p.activeRecalls.map { it.toProfileRecallItem(p.submittedQuery) }
                        .ifEmpty { current.activeRecalls },
                    errorMessage = null,
                )
            }

            is ScanSseEvent.Interactions -> {
                val p = event.payload
                val nextLocked = current.safetyBannerLocked || p.safetyBannerLocked
                val mergedRisk = RiskLevel.max(current.overallRisk, RiskLevel.fromApi(p.overallRisk))
                val nextRisk = if (nextLocked) RiskLevel.MAJOR else mergedRisk

                current.copy(
                    scanId = p.scanId,
                    stage = AnalysisStage.ML_READY,
                    overallRisk = nextRisk,
                    safetyBannerLocked = nextLocked,
                    interactions = p.interactions,
                    mlScoredPairCount = p.mlScoredPairCount,
                )
            }

            is ScanSseEvent.Citations -> {
                current.copy(
                    scanId = event.payload.scanId,
                    stage = AnalysisStage.CITATIONS_READY,
                    citations = event.payload.citations,
                    lifestyleTriggers = event.payload.lifestyleTriggers.ifEmpty {
                        current.lifestyleTriggers
                    },
                )
            }

            is ScanSseEvent.ClinicalSummary -> {
                val p = event.payload
                val nextLocked = current.safetyBannerLocked || p.safetyBannerLocked
                val mergedRisk = RiskLevel.max(current.overallRisk, RiskLevel.fromApi(p.overallRisk))
                val nextRisk = if (nextLocked) RiskLevel.MAJOR else mergedRisk

                current.copy(
                    scanId = p.scanId,
                    stage = AnalysisStage.SUMMARY_READY,
                    overallRisk = nextRisk,
                    safetyBannerLocked = nextLocked,
                    citationVerified = p.citationVerified,
                    blockedCitationIds = p.blockedCitationIds,
                    clinicalSummary = p.clinicalSummary,
                    citations = if (p.citations.isNotEmpty() || p.clinicalSummary.abstained) {
                        p.citations
                    } else {
                        current.citations
                    },
                )
            }

            is ScanSseEvent.Complete -> {
                val p = event.payload
                val hasHardTrigger = p.safetyBannerLocked ||
                    p.duplicateSalts.isNotEmpty() ||
                    p.allergyAlerts.isNotEmpty() ||
                    p.deterministicAlerts.any { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
                val nextLocked = current.safetyBannerLocked || hasHardTrigger
                val mergedRisk = RiskLevel.max(current.overallRisk, RiskLevel.fromApi(p.overallRisk))
                val nextRisk = if (nextLocked) RiskLevel.MAJOR else mergedRisk

                current.copy(
                    scanId = p.scanId,
                    profileId = p.profileId ?: current.profileId,
                    persisted = p.persisted,
                    submittedQuery = p.submittedQuery,
                    resolvedSalts = p.resolvedSalts.ifEmpty { current.resolvedSalts },
                    stage = AnalysisStage.COMPLETE,
                    overallRisk = nextRisk,
                    safetyBannerLocked = nextLocked,
                    duplicateSalts = p.duplicateSalts.ifEmpty { current.duplicateSalts },
                    allergyAlerts = p.allergyAlerts.ifEmpty { current.allergyAlerts },
                    deterministicAlerts = p.deterministicAlerts.ifEmpty { current.deterministicAlerts },
                    lifestyleTriggers = p.lifestyleTriggers.ifEmpty { current.lifestyleTriggers },
                    activeRecalls = p.activeRecalls.map { it.toProfileRecallItem(p.submittedQuery) }
                        .ifEmpty { current.activeRecalls },
                    interactions = p.interactions,
                    clinicalSummary = p.clinicalSummary,
                    citations = p.citations,
                    citationVerified = p.citationVerified,
                    blockedCitationIds = p.blockedCitationIds,
                    latencyMs = p.latencyMs,
                    errorMessage = null,
                )
            }

            is ScanSseEvent.StreamError -> {
                if (current.stage == AnalysisStage.COMPLETE) {
                    current
                } else {
                    current.copy(
                        stage = AnalysisStage.ERROR,
                        errorMessage = event.message,
                    )
                }
            }
        }
    }
}

fun MedicationRecallFlagDto.toProfileRecallItem(medicationName: String): ProfileRecallAlertItemDto =
    ProfileRecallAlertItemDto(
        alertLabel = alertLabel,
        jurisdiction = "US_FDA",
        recallId = recallId,
        recallNumber = recallNumber,
        classification = classification,
        status = status,
        recallingFirm = recallingFirm,
        productDescription = medicationName,
        reasonForRecall = reasonForRecall,
        reportDate = reportDate,
        sourceUrl = sourceUrl,
        matchedMedicationId = "",
        matchedMedicationName = medicationName,
        matchBasis = matchBasis,
        matchedTerms = matchedTerms,
    )
