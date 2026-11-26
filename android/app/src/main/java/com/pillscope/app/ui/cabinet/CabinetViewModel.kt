package com.pillscope.app.ui.cabinet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.CabinetRepository
import com.pillscope.app.data.repository.CabinetSyncReport
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CabinetDisplayMode {
    LIST,
    NETWORK,
}

data class CabinetUiState(
    val displayMode: CabinetDisplayMode = CabinetDisplayMode.LIST,
    val isSyncing: Boolean = false,
    val lastSyncReport: CabinetSyncReport? = null,
    val lastDuplicateAlerts: List<DuplicateSaltAlertDto> = emptyList(),
    val lastAllergyAlerts: List<AllergyAlertDto> = emptyList(),
    val statusBanner: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CabinetViewModel @Inject constructor(
    private val cabinetRepository: CabinetRepository,
    private val profileRepository: ProfileRepository,
    private val scanRepository: ScanRepository,
    private val scheduleAndRecallRepository: ScheduleAndRecallRepository,
) : ViewModel() {
    val medications: StateFlow<List<CabinetMedication>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            cabinetRepository.observeAllVisibleMedications(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeRecalls: StateFlow<List<ProfileRecallAlertItemDto>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeRecalls(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val cachedEvidence = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scanRepository.observeCachedEvidenceForProfile(profileId)
        }

    private val cachedSchedule = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeSchedule(profileId)
        }

    private val customNodePositions = MutableStateFlow<Map<String, Pair<Float, Float>>>(emptyMap())
    private val selectedNodeId = MutableStateFlow<String?>(null)
    private val selectedEdgeId = MutableStateFlow<String?>(null)

    val graphState: StateFlow<CabinetGraphState> = combine(
        medications,
        cachedEvidence,
        cachedSchedule,
        customNodePositions,
        combine(selectedNodeId, selectedEdgeId) { nodeId, edgeId -> nodeId to edgeId },
    ) { meds, evidence, schedule, positions, selection ->
        val built = CabinetGraphState.build(
            medications = meds,
            interactions = evidence.interactions,
            citations = evidence.citations,
            unresolvableClashes = schedule?.unresolvableClashes.orEmpty(),
            existingPositions = positions,
        )
        built.copy(
            selectedNodeId = selection.first,
            selectedEdgeId = selection.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CabinetGraphState())

    private val _uiState = MutableStateFlow(CabinetUiState())
    val uiState: StateFlow<CabinetUiState> = _uiState.asStateFlow()

    fun setDisplayMode(mode: CabinetDisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun selectGraphNode(nodeId: String?) {
        val next = if (selectedNodeId.value == nodeId) null else nodeId
        selectedNodeId.value = next
        selectedEdgeId.value = null
    }

    fun selectGraphEdge(edgeId: String?) {
        selectedEdgeId.value = edgeId
    }

    fun moveGraphNode(medicationId: String, normX: Float, normY: Float) {
        customNodePositions.update { current ->
            current + (medicationId to (normX.coerceIn(0.14f, 0.86f) to normY.coerceIn(0.14f, 0.86f)))
        }
    }

    fun syncCabinet() {
        val profileId = profileRepository.selectedProfileId.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, statusBanner = null) }
            val report = cabinetRepository.syncWithBackend(profileId)
            scheduleAndRecallRepository.refreshRecalls(profileId)
            scheduleAndRecallRepository.refreshSchedule(profileId)
            scanRepository.syncRecentScansForProfile(profileId)

            val msg = if (report.isSuccess) {
                "Synced (${report.pulledCount} medications up to date)"
            } else {
                "Offline mode, local changes queued for next sync"
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncReport = report,
                    statusBanner = msg,
                )
            }
        }
    }

    fun addMedication(displayName: String, rawSaltsCsv: String, dosageSchedule: String?) {
        val salts = rawSaltsCsv
            .split(",", "+")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { SaltComponentDto(canonicalSalt = it.lowercase()) }
        if (displayName.isBlank() || salts.isEmpty()) return

        val profileId = profileRepository.selectedProfileId.value
        viewModelScope.launch {
            val result = cabinetRepository.addMedicationOfflineFirst(
                displayName = displayName,
                genericSalts = salts,
                dosageSchedule = dosageSchedule,
                profileId = profileId,
            )
            val banner = if (result.syncedWithServer) {
                "Added ${result.medication.displayName}"
            } else {
                "Saved ${result.medication.displayName} offline (pending sync)"
            }
            _uiState.update {
                it.copy(
                    lastDuplicateAlerts = result.localDuplicateAlerts,
                    lastAllergyAlerts = result.allergyAlerts,
                    statusBanner = banner,
                )
            }
        }
    }

    fun toggleActive(id: String, active: Boolean) {
        viewModelScope.launch {
            cabinetRepository.toggleMedicationActive(id, active)
        }
    }

    fun deleteMedication(id: String) {
        viewModelScope.launch {
            cabinetRepository.deleteMedication(id)
        }
    }
}
