package com.pillscope.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.CachedScanSummary
import com.pillscope.app.data.repository.ScanAnalysisState
import com.pillscope.app.data.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanAnalysisState())
    val state: StateFlow<ScanAnalysisState> = _state.asStateFlow()

    val cachedHistory: StateFlow<List<CachedScanSummary>> = scanRepository
        .observeCachedReports(limit = 30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamJob: Job? = null

    fun startAnalysis(request: ScanAnalyzeRequestDto) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            scanRepository.analyzeMedicineStream(request).collect { updated ->
                _state.value = updated
            }
        }
    }

    fun openCachedReport(scanId: String) {
        streamJob?.cancel()
        viewModelScope.launch {
            scanRepository.loadCachedReport(scanId)?.let { cached ->
                _state.value = cached
            }
        }
    }
}
