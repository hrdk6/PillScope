package com.pillscope.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.i18n.LanguagePreferenceStore
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
import java.util.concurrent.atomic.AtomicBoolean
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
    val isForcedCaptureHeld: Boolean = false,
    val isSearchFocused: Boolean = false,
    val resolveError: String? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val profileRepository: ProfileRepository,
    private val languageStore: LanguagePreferenceStore,
    val preprocessor: LiteRtLabelPreprocessor,
    val ocrEngine: MlKitOcrEngine,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    val forceNextOcr: AtomicBoolean = AtomicBoolean(false)

    private var resolveJob: Job? = null
    private var lastResolvedText: String = ""
    @Volatile
    private var pendingForcedSingleFrame: Boolean = false

    fun onScanModeChange(mode: ScanMode) {
        _uiState.update { it.copy(scanMode = mode) }
    }

    fun onSearchFocusChanged(focused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = focused) }
    }

    fun onManualQueryChange(query: String) {
        val clearedOcr = query.isNotBlank()
        if (clearedOcr) {
            lastResolvedText = ""
        }
        _uiState.update { state ->
            state.copy(
                manualQuery = query,
                rawOcrText = if (clearedOcr) "" else state.rawOcrText,
                localSaltChips = if (clearedOcr) emptyList() else state.localSaltChips,
                ocrTextBlocks = if (clearedOcr) emptyList() else state.ocrTextBlocks,
                isForcedCaptureHeld = if (clearedOcr) false else state.isForcedCaptureHeld,
                resolveError = null,
            )
        }
        if (query.trim().length >= 3) {
            triggerDebouncedResolve(query = query.trim(), rawOcrText = null)
        } else {
            resolveJob?.cancel()
            _uiState.update { it.copy(resolvedCandidates = emptyList(), isResolving = false) }
        }
    }

    fun clearScanState() {
        resolveJob?.cancel()
        lastResolvedText = ""
        pendingForcedSingleFrame = false
        forceNextOcr.set(false)
        _uiState.update { state ->
            state.copy(
                manualQuery = "",
                rawOcrText = "",
                localSaltChips = emptyList(),
                ocrTextBlocks = emptyList(),
                resolvedCandidates = emptyList(),
                isResolving = false,
                isForcedCaptureHeld = false,
                isCameraFrozen = false,
                resolveError = null,
            )
        }
    }

    fun clearManualSearchOnly() {
        resolveJob?.cancel()
        lastResolvedText = ""
        _uiState.update { state ->
            state.copy(
                manualQuery = "",
                resolvedCandidates = emptyList(),
                isResolving = false,
                resolveError = null,
            )
        }
    }

    fun removeSaltChip(canonicalSalt: String) {
        _uiState.update { state ->
            state.copy(
                localSaltChips = state.localSaltChips.filterNot {
                    it.canonicalSalt.equals(canonicalSalt, ignoreCase = true)
                },
            )
        }
    }

    fun requestForcedOcrCapture() {
        pendingForcedSingleFrame = true
        forceNextOcr.set(true)
        _uiState.update { it.copy(resolveError = null) }
    }

    fun onCameraPipelineResult(result: CameraPipelineResult) {
        val currentState = _uiState.value
        val isForcedResult = result.wasForced
        if (currentState.isCameraFrozen && !isForcedResult && !pendingForcedSingleFrame) return

        // While the user is typing or focused in manual search, keep camera frames from overwriting results.
        val suppressOcrForSearch = currentState.manualQuery.isNotBlank() || currentState.isSearchFocused
        if (isForcedResult) {
            pendingForcedSingleFrame = false
        }

        val forcedFoundContent = isForcedResult &&
            (result.rawOcrText.isNotBlank() || result.localSaltChips.isNotEmpty() || result.ocrTextBlocks.isNotEmpty())
        val shouldFreezeAfterForce = isForcedResult && (forcedFoundContent || currentState.isCameraFrozen)

        _uiState.update { state ->
            state.copy(
                latestQuality = result.quality,
                ocrTextBlocks = when {
                    suppressOcrForSearch -> emptyList()
                    result.quality.isAcceptable || isForcedResult -> result.ocrTextBlocks
                    state.isForcedCaptureHeld -> state.ocrTextBlocks
                    else -> emptyList()
                },
                rawOcrText = if (!suppressOcrForSearch && result.rawOcrText.isNotBlank()) {
                    result.rawOcrText
                } else {
                    state.rawOcrText
                },
                localSaltChips = if (!suppressOcrForSearch && result.localSaltChips.isNotEmpty()) {
                    result.localSaltChips
                } else {
                    state.localSaltChips
                },
                isCameraFrozen = if (shouldFreezeAfterForce) true else state.isCameraFrozen,
                isForcedCaptureHeld = if (forcedFoundContent) true else state.isForcedCaptureHeld,
                resolveError = if (isForcedResult && !forcedFoundContent && !suppressOcrForSearch) {
                    "No readable text detected in that frame. Hold closer to the medicine label or search below."
                } else {
                    state.resolveError
                },
                frameWidth = if (result.frameWidth > 0) result.frameWidth else state.frameWidth,
                frameHeight = if (result.frameHeight > 0) result.frameHeight else state.frameHeight,
            )
        }

        if (suppressOcrForSearch) return

        val queryGuess = result.primaryBrandGuess.ifBlank {
            result.localSaltChips.joinToString(" ") { it.canonicalSalt }
        }
        if ((result.quality.isAcceptable || isForcedResult) &&
            queryGuess.length >= 3 &&
            (queryGuess != lastResolvedText || isForcedResult)
        ) {
            lastResolvedText = queryGuess
            triggerDebouncedResolve(query = queryGuess, rawOcrText = result.rawOcrText)
        }
    }

    fun toggleCameraFreeze() {
        _uiState.update { state ->
            val nextFrozen = !state.isCameraFrozen
            state.copy(
                isCameraFrozen = nextFrozen,
                isForcedCaptureHeld = if (!nextFrozen) false else state.isForcedCaptureHeld,
            )
        }
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
            rawOcrText = if (isCamera) state.rawOcrText.takeIf { it.isNotBlank() } else null,
            language = languageStore.currentLanguage.code,
            edgeQualityMetrics = if (isCamera) state.latestQuality?.toMetricsDto() else null,
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
            language = languageStore.currentLanguage.code,
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
                language = languageStore.currentLanguage.code,
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
                language = languageStore.currentLanguage.code,
                edgeQualityMetrics = state.latestQuality?.toMetricsDto(),
            )
        }
        return null
    }

    private fun triggerDebouncedResolve(query: String, rawOcrText: String?) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            delay(280L)
            _uiState.update { it.copy(isResolving = true, resolveError = null) }
            val outcome = scanRepository.resolveDrugCandidatesDetailed(
                query = query,
                rawOcrText = rawOcrText,
            )
            _uiState.update {
                it.copy(
                    resolvedCandidates = outcome.candidates,
                    isResolving = false,
                    resolveError = outcome.error?.userMessage,
                )
            }
        }
    }
}
