package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ClinicalSummaryDto
import com.pillscope.app.data.remote.dto.DeterministicDdiAlertDto
import com.pillscope.app.data.remote.dto.DrugResolveRequestDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.InteractionItemDto
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
    val submittedQuery: String,
    val resolvedSalts: List<String>,
    val overallRisk: RiskLevel,
    val safetyBannerLocked: Boolean,
    val citationVerified: Boolean,
    val createdAtEpochMs: Long,
)

@Singleton
class ScanRepository @Inject constructor(
    private val api: PillScopeApi,
    private val sseClient: ScanSseClient,
    private val scanReportDao: ScanReportDao,
    private val json: Json,
) {
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
            submittedQuery = request.submittedQuery,
            resolvedSalts = request.confirmedSalts.orEmpty(),
            stage = AnalysisStage.CONNECTING,
        )
        return sseClient.streamAnalysis(request)
            .onEach { event ->
                if (event is ScanSseEvent.Complete) {
                    persistCompletedScan(event.payload)
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
                    submittedQuery = row.submittedQuery,
                    resolvedSalts = decodeList(row.resolvedSaltsJson),
                    overallRisk = RiskLevel.fromApi(row.overallRisk),
                    safetyBannerLocked = row.safetyBannerLocked,
                    citationVerified = row.citationVerified,
                    createdAtEpochMs = row.createdAtEpochMs,
                )
            }
        }

    suspend fun loadCachedReport(scanId: String): ScanAnalysisState? {
        val entity = scanReportDao.getByScanId(scanId) ?: return null
        return ScanAnalysisState(
            scanId = entity.scanId,
            submittedQuery = entity.submittedQuery,
            resolvedSalts = decodeList(entity.resolvedSaltsJson),
            stage = AnalysisStage.COMPLETE,
            overallRisk = RiskLevel.fromApi(entity.overallRisk),
            safetyBannerLocked = entity.safetyBannerLocked,
            duplicateSalts = decodeList<DuplicateSaltAlertDto>(entity.duplicateSaltsJson),
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

    private suspend fun persistCompletedScan(complete: ScanCompleteEventDto) {
        val entity = CachedScanReportEntity(
            scanId = complete.scanId,
            submittedQuery = complete.submittedQuery,
            resolvedSaltsJson = json.encodeToString(complete.resolvedSalts),
            overallRisk = complete.overallRisk,
            safetyBannerLocked = complete.safetyBannerLocked,
            duplicateSaltsJson = json.encodeToString(complete.duplicateSalts),
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
