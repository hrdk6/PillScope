package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.LifestyleTriggerDao
import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.CachedLifestyleTriggerEntity
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ClinicalSummaryDto
import com.pillscope.app.data.remote.dto.DeterministicDdiAlertDto
import com.pillscope.app.data.remote.dto.DrugResolveRequestDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.LifestyleTriggerDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.remote.dto.ScanCompleteEventDto
import com.pillscope.app.data.remote.sse.ScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseEvent
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class CachedScanSummary(
    val scanId: String,
    val profileId: String,
    val submittedQuery: String,
    val resolvedSalts: List<String>,
    val overallRisk: RiskLevel,
    val safetyBannerLocked: Boolean,
    val citationVerified: Boolean,
    val createdAtEpochMs: Long,
)

data class CachedScanEvidence(
    val interactions: List<InteractionItemDto>,
    val citations: List<MonographCitationDto>,
)

@Singleton
class ScanRepository {
    private val api: PillScopeApi
    private val sseClient: ScanSseClient
    private val scanReportDao: ScanReportDao
    private val json: Json
    private val lifestyleTriggerDao: LifestyleTriggerDao?

    @Inject
    constructor(
        api: PillScopeApi,
        sseClient: ScanSseClient,
        scanReportDao: ScanReportDao,
        json: Json,
        lifestyleTriggerDao: LifestyleTriggerDao,
    ) {
        this.api = api
        this.sseClient = sseClient
        this.scanReportDao = scanReportDao
        this.json = json
        this.lifestyleTriggerDao = lifestyleTriggerDao
    }

    constructor(
        api: PillScopeApi,
        sseClient: ScanSseClient,
        scanReportDao: ScanReportDao,
        json: Json,
    ) {
        this.api = api
        this.sseClient = sseClient
        this.scanReportDao = scanReportDao
        this.json = json
        this.lifestyleTriggerDao = null
    }

    suspend fun resolveDrugCandidates(
        query: String,
        rawOcrText: String? = null,
    ): List<BrandCandidateDto> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() && rawOcrText.isNullOrBlank()) return emptyList()

        val remote = runCatching {
            api.resolveDrug(
                DrugResolveRequestDto(
                    query = trimmed.ifEmpty { rawOcrText.orEmpty().take(120) },
                    rawOcrText = rawOcrText,
                    limit = 5,
                ),
            )
        }.getOrNull()

        if (remote != null && remote.candidates.isNotEmpty()) {
            return remote.candidates
        }

        val combined = listOfNotNull(trimmed.takeIf { it.isNotEmpty() }, rawOcrText).joinToString("\n")
        val localChips = OnDeviceSaltChipExtractor.extractSaltChips(combined)
        if (localChips.isEmpty()) return emptyList()

        val salts = localChips.map {
            SaltComponentDto(canonicalSalt = it.canonicalSalt, strengthText = it.strengthText)
        }
        return listOf(
            BrandCandidateDto(
                brandMappingId = null,
                brandName = trimmed.ifEmpty { localChips.joinToString(" + ") { it.canonicalSalt } },
                genericSalts = salts,
                canonicalSaltKeys = localChips.map { it.canonicalSalt }.sorted(),
                matchStrategy = "ocr_salt_parse",
                confidence = 0.70,
            ),
        )
    }

    fun analyzeMedicineStream(request: ScanAnalyzeRequestDto): Flow<ScanAnalysisState> {
        val initial = ScanAnalysisState(
            profileId = request.profileId,
            persisted = request.persist,
            submittedQuery = request.submittedQuery,
            resolvedSalts = request.confirmedSalts.orEmpty(),
            stage = AnalysisStage.CONNECTING,
        )
        return sseClient.streamAnalysis(request)
            .onEach { event ->
                if (event is ScanSseEvent.Complete) {
                    cacheTriggersFromComplete(event.payload)
                    if (event.payload.persisted) {
                        persistCompletedScan(event.payload)
                    }
                }
            }
            .runningFold(initial) { current, event ->
                ScanAnalysisReducer.reduce(current, event)
            }
    }

    fun observeCachedReports(limit: Int = 30): Flow<List<CachedScanSummary>> =
        scanReportDao.observeRecentReports(limit).map { rows ->
            rows.map { row ->
                CachedScanSummary(
                    scanId = row.scanId,
                    profileId = row.profileId,
                    submittedQuery = row.submittedQuery,
                    resolvedSalts = decodeList(row.resolvedSaltsJson),
                    overallRisk = RiskLevel.fromApi(row.overallRisk),
                    safetyBannerLocked = row.safetyBannerLocked,
                    citationVerified = row.citationVerified,
                    createdAtEpochMs = row.createdAtEpochMs,
                )
            }
        }

    fun observeCachedEvidenceForProfile(profileId: String, limit: Int = 30): Flow<CachedScanEvidence> =
        scanReportDao.observeRecentReportsForProfile(profileId, limit).map { rows ->
            val interactions = rows.flatMap { decodeList<InteractionItemDto>(it.interactionsJson) }
            val citations = rows.flatMap { decodeList<MonographCitationDto>(it.citationsJson) }
            CachedScanEvidence(interactions = interactions, citations = citations)
        }

    suspend fun syncRecentScansForProfile(profileId: String) {
        val serverProfileId = profileId.takeIf {
            it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
        }
        val remote = runCatching { api.listScans(profileId = serverProfileId, limit = 25) }.getOrNull()
            ?: return
        val now = System.currentTimeMillis()
        remote.items.forEachIndexed { idx, log ->
            val summaryJson = log.clinicalSummary?.let { json.encodeToString(it) } ?: "{}"
            val entity = CachedScanReportEntity(
                scanId = log.id,
                profileId = log.profileId.ifBlank { profileId },
                persisted = true,
                submittedQuery = log.submittedQuery,
                resolvedSaltsJson = json.encodeToString(log.resolvedSalts.map { it.canonicalSalt }),
                overallRisk = log.overallRisk,
                safetyBannerLocked = log.safetyBannerLocked,
                duplicateSaltsJson = json.encodeToString(log.duplicateSalts),
                allergyAlertsJson = json.encodeToString(log.allergyAlerts),
                lifestyleTriggersJson = json.encodeToString(log.lifestyleTriggers),
                deterministicAlertsJson = json.encodeToString(log.deterministicAlerts),
                interactionsJson = json.encodeToString(log.interactions),
                clinicalSummaryJson = summaryJson,
                citationsJson = json.encodeToString(log.citations),
                citationVerified = log.citationVerified,
                latencyMs = log.latencyMs ?: 0,
                createdAtEpochMs = now - (idx * 1000L),
            )
            scanReportDao.upsert(entity)
        }
    }

    suspend fun loadCachedReport(scanId: String): ScanAnalysisState? {
        val entity = scanReportDao.getByScanId(scanId) ?: return null
        return ScanAnalysisState(
            scanId = entity.scanId,
            profileId = entity.profileId,
            persisted = entity.persisted,
            submittedQuery = entity.submittedQuery,
            resolvedSalts = decodeList(entity.resolvedSaltsJson),
            stage = AnalysisStage.COMPLETE,
            overallRisk = RiskLevel.fromApi(entity.overallRisk),
            safetyBannerLocked = entity.safetyBannerLocked,
            duplicateSalts = decodeList<DuplicateSaltAlertDto>(entity.duplicateSaltsJson),
            allergyAlerts = decodeList<AllergyAlertDto>(entity.allergyAlertsJson),
            lifestyleTriggers = decodeList<LifestyleTriggerDto>(entity.lifestyleTriggersJson),
            deterministicAlerts = decodeList<DeterministicDdiAlertDto>(entity.deterministicAlertsJson),
            interactions = decodeList<InteractionItemDto>(entity.interactionsJson),
            clinicalSummary = runCatching {
                json.decodeFromString<ClinicalSummaryDto>(entity.clinicalSummaryJson)
            }.getOrNull(),
            citations = decodeList<MonographCitationDto>(entity.citationsJson),
            citationVerified = entity.citationVerified,
            latencyMs = entity.latencyMs,
            fromOfflineCache = true,
        )
    }

    private suspend fun cacheTriggersFromComplete(complete: ScanCompleteEventDto) {
        val dao = lifestyleTriggerDao ?: return
        if (complete.lifestyleTriggers.isEmpty() || complete.resolvedSalts.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = complete.resolvedSalts.flatMap { salt ->
            complete.lifestyleTriggers.map { trigger ->
                CachedLifestyleTriggerEntity(
                    id = "$salt:${trigger.type}",
                    canonicalSalt = salt,
                    triggerType = trigger.type,
                    severity = trigger.severity,
                    shortNote = trigger.shortNote,
                    sourceSection = trigger.sourceSection,
                    updatedAtEpochMs = now,
                )
            }
        }
        dao.upsertAll(entities)
    }

    private suspend fun persistCompletedScan(complete: ScanCompleteEventDto) {
        val entity = CachedScanReportEntity(
            scanId = complete.scanId,
            profileId = complete.profileId ?: DefaultProfileIds.LOCAL_DEFAULT,
            persisted = complete.persisted,
            submittedQuery = complete.submittedQuery,
            resolvedSaltsJson = json.encodeToString(complete.resolvedSalts),
            overallRisk = complete.overallRisk,
            safetyBannerLocked = complete.safetyBannerLocked,
            duplicateSaltsJson = json.encodeToString(complete.duplicateSalts),
            allergyAlertsJson = json.encodeToString(complete.allergyAlerts),
            lifestyleTriggersJson = json.encodeToString(complete.lifestyleTriggers),
            deterministicAlertsJson = json.encodeToString(complete.deterministicAlerts),
            interactionsJson = json.encodeToString(complete.interactions),
            clinicalSummaryJson = json.encodeToString(complete.clinicalSummary),
            citationsJson = json.encodeToString(complete.citations),
            citationVerified = complete.citationVerified,
            latencyMs = complete.latencyMs,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        scanReportDao.upsert(entity)
    }

    private inline fun <reified T> decodeList(rawJson: String): List<T> =
        runCatching { json.decodeFromString<List<T>>(rawJson) }.getOrDefault(emptyList())
}
