package com.pillscope.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.ml.CameraPipelineResult
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.LiteRtLabelPreprocessor
import com.pillscope.app.ml.LocalSaltChip
import com.pillscope.app.ml.MlKitOcrEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val manualQuery: String = "",
    val latestQuality: FrameQualityResult? = null,
    val rawOcrText: String = "",
    val localSaltChips: List<LocalSaltChip> = emptyList(),
    val resolvedCandidates: List<BrandCandidateDto> = emptyList(),
    val isResolving: Boolean = false,
    val isCameraFrozen: Boolean = false,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    val preprocessor: LiteRtLabelPreprocessor,
    val ocrEngine: MlKitOcrEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var lastResolvedText: String = ""

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

    fun buildAnalyzeRequestForCandidate(candidate: BrandCandidateDto): ScanAnalyzeRequestDto {
        val state = _uiState.value
        val isCamera = state.manualQuery.isBlank() && state.rawOcrText.isNotBlank()
        return ScanAnalyzeRequestDto(
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
            inputMode = if (state.rawOcrText.isNotBlank()) "CAMERA_OCR" else "MANUAL_TEXT",
            submittedQuery = query,
            confirmedSalts = salts.takeIf { it.isNotEmpty() },
            rawOcrText = state.rawOcrText.takeIf { it.isNotBlank() },
            edgeQualityMetrics = state.latestQuality?.toMetricsDto(),
        )
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
