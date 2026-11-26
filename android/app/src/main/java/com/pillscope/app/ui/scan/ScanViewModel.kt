package com.pillscope.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.ml.CameraPipelineResult
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.LiteRtLabelPreprocessor
import com.pillscope.app.ml.LocalSaltChip
import com.pillscope.app.ml.MlKitOcrEngine
import com.pillscope.app.ml.OcrTextBlockBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScanMode(val persistToServer: Boolean) {
    ADD_TO_CABINET(true),
    CHECK_BEFORE_BUYING(false),
}

data class ScanUiState(
    val manualQuery: String = "",
    val scanMode: ScanMode = ScanMode.ADD_TO_CABINET,
    val latestQuality: FrameQualityResult? = null,
    val ocrTextBlocks: List<OcrTextBlockBox> = emptyList(),
    val rawOcrText: String = "",
    val localSaltChips: List<LocalSaltChip> = emptyList(),
    val resolvedCandidates: List<BrandCandidateDto> = emptyList(),
    val isResolving: Boolean = false,
    val isCameraFrozen: Boolean = false,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val profileRepository: ProfileRepository,
    val preprocessor: LiteRtLabelPreprocessor,
    val ocrEngine: MlKitOcrEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var lastResolvedText: String = ""

    fun onScanModeChange(mode: ScanMode) {
        _uiState.update { it.copy(scanMode = mode) }
    }

    fun onManualQueryChange(query: String) {
        _uiState.update { it.copy(manualQuery = query) }
        if (query.trim().length >= 3) {
            triggerDebouncedResolve(query = query.trim(), rawOcrText = null)
        } else {
            _uiState.update { it.copy(resolvedCandidates = emptyList(), isResolving = false) }
        }
    }

    fun onCameraPipelineResult(result: CameraPipelineResult) {
        if (_uiState.value.isCameraFrozen) return
        _uiState.update { state ->
            state.copy(
                latestQuality = result.quality,
                ocrTextBlocks = if (result.quality.isAcceptable) result.ocrTextBlocks else emptyList(),
                rawOcrText = if (result.rawOcrText.isNotBlank()) result.rawOcrText else state.rawOcrText,
                localSaltChips = if (result.localSaltChips.isNotEmpty()) {
                    result.localSaltChips
                } else {
                    state.localSaltChips
                },
            )
        }

        val queryGuess = result.primaryBrandGuess.ifBlank {
            result.localSaltChips.joinToString(" ") { it.canonicalSalt }
        }
        if (result.quality.isAcceptable && queryGuess.length >= 3 && queryGuess != lastResolvedText) {
            lastResolvedText = queryGuess
            triggerDebouncedResolve(query = queryGuess, rawOcrText = result.rawOcrText)
        }
    }

    fun toggleCameraFreeze() {
        _uiState.update { it.copy(isCameraFrozen = !it.isCameraFrozen) }
    }

    private fun activeServerProfileId(): String? {
        val current = profileRepository.selectedProfileId.value
        return current.takeIf {
            it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
        }
    }

    fun buildAnalyzeRequestForCandidate(candidate: BrandCandidateDto): ScanAnalyzeRequestDto {
        val state = _uiState.value
        val isCamera = state.manualQuery.isBlank() && state.rawOcrText.isNotBlank()
        return ScanAnalyzeRequestDto(
            profileId = activeServerProfileId(),
            persist = state.scanMode.persistToServer,
            inputMode = if (isCamera) "CAMERA_OCR" else "MANUAL_TEXT",
            submittedQuery = candidate.brandName,
            confirmedSalts = candidate.canonicalSaltKeys,
            rawOcrText = state.rawOcrText.takeIf { it.isNotBlank() },
            edgeQualityMetrics = state.latestQuality?.toMetricsDto(),
        )
    }

    fun buildAnalyzeRequestForLocalChips(): ScanAnalyzeRequestDto {
        val state = _uiState.value
        val salts = state.localSaltChips.map { it.canonicalSalt }
        val query = state.manualQuery.trim().ifEmpty {
            salts.joinToString(" + ").ifEmpty { state.rawOcrText.take(64) }
        }
        return ScanAnalyzeRequestDto(
            profileId = activeServerProfileId(),
            persist = state.scanMode.persistToServer,
            inputMode = if (state.rawOcrText.isNotBlank()) "CAMERA_OCR" else "MANUAL_TEXT",
            submittedQuery = query,
            confirmedSalts = salts.takeIf { it.isNotEmpty() },
            rawOcrText = state.rawOcrText.takeIf { it.isNotBlank() },
            edgeQualityMetrics = state.latestQuality?.toMetricsDto(),
        )
    }

    fun buildAnalyzeRequestForShutter(): ScanAnalyzeRequestDto? {
        val state = _uiState.value
        state.resolvedCandidates.firstOrNull()?.let { return buildAnalyzeRequestForCandidate(it) }
        if (state.localSaltChips.isNotEmpty()) return buildAnalyzeRequestForLocalChips()
        val manual = state.manualQuery.trim()
        if (manual.isNotEmpty()) {
            return ScanAnalyzeRequestDto(
                profileId = activeServerProfileId(),
                persist = state.scanMode.persistToServer,
                inputMode = "MANUAL_TEXT",
                submittedQuery = manual,
            )
        }
        val ocrFallback = state.rawOcrText.trim().lineSequence().firstOrNull()?.take(64).orEmpty()
        if (ocrFallback.isNotEmpty()) {
            return ScanAnalyzeRequestDto(
                profileId = activeServerProfileId(),
                persist = state.scanMode.persistToServer,
                inputMode = "CAMERA_OCR",
                submittedQuery = ocrFallback,
                rawOcrText = state.rawOcrText,
                edgeQualityMetrics = state.latestQuality?.toMetricsDto(),
            )
        }
        return null
    }

    private fun triggerDebouncedResolve(query: String, rawOcrText: String?) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            delay(280L)
            _uiState.update { it.copy(isResolving = true) }
            val candidates = scanRepository.resolveDrugCandidates(
                query = query,
                rawOcrText = rawOcrText,
            )
            _uiState.update { it.copy(resolvedCandidates = candidates, isResolving = false) }
        }
    }
}
