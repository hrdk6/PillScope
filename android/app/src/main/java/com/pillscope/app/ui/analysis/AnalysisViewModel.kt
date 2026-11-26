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

    private val profileRecalls = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeRecalls(profileId)
        }

    val state: StateFlow<ScanAnalysisState> = combine(rawAnalysisState, profileRecalls) { scan, recalls ->
        val saltSet = scan.resolvedSalts.map { it.lowercase() }.toSet()
        val queryLower = scan.submittedQuery.lowercase()
        val matchedRecalls = if (saltSet.isEmpty() && queryLower.isBlank()) {
            emptyList()
        } else {
            recalls.filter { recall ->
                recall.matchedTerms.any { it.lowercase() in saltSet } ||
                    (queryLower.isNotBlank() && recall.matchedMedicationName.lowercase().contains(queryLower))
            }
        }
        scan.copy(activeRecalls = matchedRecalls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanAnalysisState())

    val cachedHistory: StateFlow<List<CachedScanSummary>> = scanRepository
        .observeCachedReports(limit = 30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamJob: Job? = null

    fun startAnalysis(request: ScanAnalyzeRequestDto) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            scanRepository.analyzeMedicineStream(request).collect { updated ->
                rawAnalysisState.value = updated
            }
        }
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
