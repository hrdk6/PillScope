package com.pillscope.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.CabinetRepository
import com.pillscope.app.data.repository.CachedScanSummary
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanAnalysisState
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import com.pillscope.app.data.repository.UserProfile
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
    private val cabinetRepository: CabinetRepository,
    private val scheduleAndRecallRepository: ScheduleAndRecallRepository,
) : ViewModel() {
    private val rawAnalysisState = MutableStateFlow(ScanAnalysisState())
    private val _lastRequest = MutableStateFlow<ScanAnalyzeRequestDto?>(null)
    // Maps scanKey -> (savedDisplayNameLower, savedCanonicalSaltSet) so deletion from Cabinet
    // immediately clears the "Saved to cabinet" badge.
    private val _savedEntries = MutableStateFlow<Map<String, Pair<String, Set<String>>>>(emptyMap())

    val activeProfile: StateFlow<UserProfile> = profileRepository.observeActiveProfile()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserProfile(
                id = "default-local-profile",
                userId = "",
                name = "Self",
                relationship = "self",
                allergies = emptyList(),
                notes = null,
                isDefault = true,
                updatedAtEpochMs = 0L,
            ),
        )

    private val profileMedications: StateFlow<List<CabinetMedication>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            cabinetRepository.observeAllVisibleMedications(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val isCurrentScanSavedInCabinet: StateFlow<Boolean> = combine(
        state,
        profileMedications,
        _savedEntries,
    ) { scan, meds, savedMap ->
        val scanSalts = scan.resolvedSalts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (scanSalts.isEmpty()) return@combine false
        val scanKey = scan.scanId ?: "${scan.submittedQuery}:${scan.resolvedSalts.joinToString(",")}"
        val savedEntry = savedMap[scanKey]
        if (savedEntry != null) {
            val (savedName, savedSalts) = savedEntry
            meds.any { med ->
                val medSalts = med.canonicalSaltKeys.map { it.trim().lowercase() }.toSet()
                med.displayName.trim().lowercase() == savedName || medSalts == savedSalts
            }
        } else {
            val queryLower = scan.submittedQuery.trim().lowercase()
            queryLower.isNotEmpty() && meds.any { med ->
                val medSalts = med.canonicalSaltKeys.map { it.trim().lowercase() }.toSet()
                med.displayName.trim().lowercase() == queryLower && medSalts == scanSalts
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val cachedHistory: StateFlow<List<CachedScanSummary>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scanRepository.observeCachedReports(profileId = profileId, limit = 30)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamJob: Job? = null
    private var lastObservedProfileId: String? = null

    init {
        viewModelScope.launch {
            profileRepository.selectedProfileId
                .collect { profileId ->
                    val previous = lastObservedProfileId
                    lastObservedProfileId = profileId
                    if (previous != null && previous != profileId) {
                        clearActiveReportForProfileSwitch()
                    }
                }
        }
    }

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

    fun markScanSaved(scanKey: String, displayName: String, resolvedSalts: List<String>) {
        if (scanKey.isNotBlank()) {
            val saltSet = resolvedSalts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            _savedEntries.value = _savedEntries.value + (scanKey to (displayName.trim().lowercase() to saltSet))
        }
    }

    fun clearActiveReportForProfileSwitch() {
        streamJob?.cancel()
        rawAnalysisState.value = ScanAnalysisState()
        _lastRequest.value = null
        _savedEntries.value = emptyMap()
    }

    fun resetForAccountChange() {
        streamJob?.cancel()
        rawAnalysisState.value = ScanAnalysisState()
        _lastRequest.value = null
        _savedEntries.value = emptyMap()
    }
}
