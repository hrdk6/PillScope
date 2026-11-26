package com.pillscope.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.CachedScanSummary
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanAnalysisState
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val profileRepository: ProfileRepository,
    private val scheduleAndRecallRepository: ScheduleAndRecallRepository,
) : ViewModel() {
    private val rawAnalysisState = MutableStateFlow(ScanAnalysisState())
    private val _lastRequest = MutableStateFlow<ScanAnalyzeRequestDto?>(null)
    val canRetry: StateFlow<Boolean> = _lastRequest
        .combine(rawAnalysisState) { req, _ -> req != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val profileRecalls = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeRecalls(profileId)
        }

    val state: StateFlow<ScanAnalysisState> = combine(rawAnalysisState, profileRecalls) { scan, recalls ->
        val saltSet = scan.resolvedSalts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val queryLower = scan.submittedQuery.trim().lowercase()
        val matchedProfileRecalls = if (saltSet.isEmpty() && queryLower.isBlank()) {
            emptyList()
        } else {
            recalls.filter { recall ->
                recall.matchedTerms.any { it.trim().lowercase() in saltSet } ||
                    (queryLower.isNotBlank() && recall.matchedMedicationName.trim().lowercase() == queryLower)
            }
        }
        val mergedRecalls = (scan.activeRecalls + matchedProfileRecalls)
            .distinctBy { it.recallNumber.ifBlank { "${it.recallId}:${it.matchedMedicationName}" } }
        scan.copy(activeRecalls = mergedRecalls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanAnalysisState())

    val cachedHistory: StateFlow<List<CachedScanSummary>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scanRepository.observeCachedReports(profileId = profileId, limit = 30)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamJob: Job? = null

    fun startAnalysis(request: ScanAnalyzeRequestDto) {
        _lastRequest.value = request
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            scanRepository.analyzeMedicineStream(request).collect { updated ->
                rawAnalysisState.value = updated
            }
        }
    }

    fun retryLastAnalysis() {
        val req = _lastRequest.value ?: return
        startAnalysis(req)
    }

    fun openCachedReport(scanId: String) {
        streamJob?.cancel()
        viewModelScope.launch {
            scanRepository.loadCachedReport(scanId)?.let { cached ->
                rawAnalysisState.value = cached
            }
        }
    }
}
