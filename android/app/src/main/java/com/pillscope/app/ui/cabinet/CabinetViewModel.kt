package com.pillscope.app.ui.cabinet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.CabinetRepository
import com.pillscope.app.data.repository.CabinetSyncReport
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import com.pillscope.app.ui.common.normalizeSaltInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val statusIsError: Boolean = false,
    val catalogSuggestions: List<BrandCandidateDto> = emptyList(),
    val isSearchingCatalog: Boolean = false,
    val selectedMedicationDetail: CabinetMedication? = null,
    val isLoadingDetail: Boolean = false,
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

    val activeProfileAllergies: StateFlow<List<AllergyEntryDto>> = profileRepository.observeActiveProfile()
        .combine(profileRepository.selectedProfileId) { profile, _ -> profile.allergyEntries }
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
            analyzedSalts = evidence.analyzedSalts,
        )
        built.copy(
            selectedNodeId = selection.first,
            selectedEdgeId = selection.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CabinetGraphState())

    private val _uiState = MutableStateFlow(CabinetUiState())
    val uiState: StateFlow<CabinetUiState> = _uiState.asStateFlow()

    private var catalogSearchJob: Job? = null
    private var syncJob: Job? = null
    private var autoDismissJob: Job? = null
    private val syncedProfileIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            profileRepository.selectedProfileId
                .collect { profileId ->
                    customNodePositions.value = emptyMap()
                    selectedNodeId.value = null
                    selectedEdgeId.value = null
                    _uiState.update {
                        it.copy(
                            selectedMedicationDetail = null,
                            lastDuplicateAlerts = emptyList(),
                            lastAllergyAlerts = emptyList(),
                            statusBanner = null,
                            statusIsError = false,
                        )
                    }
                    // Automatically sync server profiles on profile switch so newly selected
                    // family profiles immediately display their cabinet, recalls, and scan history.
                    if (profileRepository.isServerProfileId(profileId) && profileId !in syncedProfileIds) {
                        syncCabinetInternal(profileId = profileId, showSuccessBanner = false)
                    }
                }
        }
    }

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
            current + (medicationId to (normX.coerceIn(0.12f, 0.88f) to normY.coerceIn(0.12f, 0.88f)))
        }
    }

    fun resetGraphLayout() {
        customNodePositions.value = emptyMap()
    }

    fun dismissStatusBanner() {
        autoDismissJob?.cancel()
        _uiState.update { it.copy(statusBanner = null, statusIsError = false) }
    }

    fun dismissSafetyAlerts() {
        _uiState.update {
            it.copy(lastDuplicateAlerts = emptyList(), lastAllergyAlerts = emptyList())
        }
    }

    fun buildCabinetInteractionCheckRequest(): ScanAnalyzeRequestDto? {
        val activeMeds = medications.value.filter { it.isActive }
        if (activeMeds.size < 2) return null

        val unanalyzedMedIds = graphState.value.edges
            .filter { it.severity == GraphEdgeSeverity.UNANALYZED }
            .flatMap { listOf(it.sourceId, it.targetId) }
            .toSet()

        val targetMed = activeMeds.firstOrNull { it.id in unanalyzedMedIds } ?: activeMeds.first()
        val currentProfileId = profileRepository.selectedProfileId.value
        val serverProfileId = currentProfileId.takeIf {
            it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
        }
        return ScanAnalyzeRequestDto(
            profileId = serverProfileId,
            persist = true,
            inputMode = "MANUAL_TEXT",
            submittedQuery = targetMed.displayName,
            confirmedSalts = targetMed.canonicalSaltKeys,
        )
    }

    fun searchCatalog(query: String) {
        catalogSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(catalogSuggestions = emptyList(), isSearchingCatalog = false) }
            return
        }
        catalogSearchJob = viewModelScope.launch {
            delay(220L)
            _uiState.update { it.copy(isSearchingCatalog = true) }
            val result = cabinetRepository.searchDrugCatalog(trimmed)
            _uiState.update {
                it.copy(
                    catalogSuggestions = result.getOrDefault(emptyList()),
                    isSearchingCatalog = false,
                )
            }
        }
    }

    fun clearCatalogSuggestions() {
        catalogSearchJob?.cancel()
        _uiState.update { it.copy(catalogSuggestions = emptyList(), isSearchingCatalog = false) }
    }

    fun openMedicationDetail(medicationId: String) {
        val localMatch = medications.value.firstOrNull { it.id == medicationId }
        _uiState.update {
            it.copy(selectedMedicationDetail = localMatch, isLoadingDetail = true)
        }
        viewModelScope.launch {
            val profileId = profileRepository.selectedProfileId.value
            val detailed = cabinetRepository.fetchMedicationDetail(medicationId, profileId)
            _uiState.update {
                it.copy(
                    selectedMedicationDetail = detailed ?: localMatch,
                    isLoadingDetail = false,
                )
            }
        }
    }

    fun closeMedicationDetail() {
        _uiState.update { it.copy(selectedMedicationDetail = null, isLoadingDetail = false) }
    }

    fun updateMedicationDetails(
        id: String,
        displayName: String,
        dosageSchedule: String?,
        rawSaltsCsv: String? = null,
    ) {
        val profileId = profileRepository.selectedProfileId.value
        val parsedSalts = rawSaltsCsv?.let(::normalizeSaltInput)
        viewModelScope.launch {
            val result = cabinetRepository.updateMedicationDetails(
                id = id,
                displayName = displayName,
                dosageSchedule = dosageSchedule,
                genericSalts = parsedSalts,
            )
            if (result.updatedLocally) {
                val refreshed = cabinetRepository.fetchMedicationDetail(id, profileId)
                    ?: medications.value.firstOrNull { it.id == id }
                if (result.syncedWithServer) {
                    scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
                    scheduleAndRecallRepository.refreshRecallsDetailed(profileId)
                }
                val (banner, isError) = when {
                    result.syncedWithServer -> "Updated ${displayName.trim()}" to false
                    result.apiError is ApiCallError.Offline -> "Saved changes offline (pending sync)" to false
                    result.apiError != null -> result.apiError.userMessage to true
                    else -> "Saved changes offline (pending sync)" to false
                }
                _uiState.update {
                    it.copy(
                        selectedMedicationDetail = refreshed,
                        lastDuplicateAlerts = result.duplicateAlerts.ifEmpty { it.lastDuplicateAlerts },
                        lastAllergyAlerts = result.allergyAlerts.ifEmpty { it.lastAllergyAlerts },
                        statusBanner = banner,
                        statusIsError = isError,
                    )
                }
                if (!isError) scheduleBannerAutoDismiss()
            } else if (result.apiError != null) {
                _uiState.update {
                    it.copy(statusBanner = result.apiError.userMessage, statusIsError = true)
                }
            }
        }
    }

    fun resetForAccountChange() {
        catalogSearchJob?.cancel()
        syncJob?.cancel()
        autoDismissJob?.cancel()
        syncedProfileIds.clear()
        customNodePositions.value = emptyMap()
        selectedNodeId.value = null
        selectedEdgeId.value = null
        _uiState.value = CabinetUiState()
    }

    fun syncCabinet() {
        syncCabinetInternal(
            profileId = profileRepository.selectedProfileId.value,
            showSuccessBanner = true,
        )
    }

    private fun syncCabinetInternal(
        profileId: String,
        showSuccessBanner: Boolean,
    ) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, statusBanner = null, statusIsError = false) }
            val report = cabinetRepository.syncWithBackend(profileId)
            scheduleAndRecallRepository.refreshRecallsDetailed(profileId)
            scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
            scanRepository.syncRecentScansForProfile(profileId)

            if (report.isSuccess && profileRepository.isServerProfileId(profileId)) {
                syncedProfileIds.add(profileId)
            }

            val (msg, isError) = when {
                report.isSuccess -> if (showSuccessBanner) {
                    "Synced (${report.pulledCount} ${if (report.pulledCount == 1) "medication" else "medications"} up to date)" to false
                } else {
                    null to false
                }
                report.error is ApiCallError.Offline ->
                    "Offline mode — local changes queued for next sync" to false
                report.error != null -> report.error.userMessage to true
                else -> "Some cabinet changes could not sync; will retry." to true
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncReport = report,
                    statusBanner = msg,
                    statusIsError = isError,
                )
            }
            if (msg != null && !isError) {
                scheduleBannerAutoDismiss()
            }
        }
    }

    private fun scheduleBannerAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = viewModelScope.launch {
            delay(4_000L)
            _uiState.update { state ->
                if (!state.statusIsError) state.copy(statusBanner = null) else state
            }
        }
    }

    fun addMedication(
        displayName: String,
        rawSaltsCsv: String,
        dosageSchedule: String?,
        brandMappingId: String? = null,
    ) {
        val salts = normalizeSaltInput(rawSaltsCsv)
        if (displayName.isBlank() || salts.isEmpty()) return

        addMedicationInternal(
            displayName = displayName,
            salts = salts,
            dosageSchedule = dosageSchedule,
            brandMappingId = brandMappingId,
        )
    }

    fun addMedicationFromScan(
        displayName: String,
        canonicalSalts: List<String>,
        dosageSchedule: String? = null,
    ) {
        val salts = canonicalSalts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { SaltComponentDto(canonicalSalt = it) }
        if (displayName.isBlank() || salts.isEmpty()) return

        addMedicationInternal(
            displayName = displayName,
            salts = salts,
            dosageSchedule = dosageSchedule,
            brandMappingId = null,
        )
    }

    private fun addMedicationInternal(
        displayName: String,
        salts: List<SaltComponentDto>,
        dosageSchedule: String?,
        brandMappingId: String?,
    ) {
        val profileId = profileRepository.selectedProfileId.value
        viewModelScope.launch {
            val activeAllergies = profileRepository.observeActiveProfile().first().allergyEntries
            val result = cabinetRepository.addMedicationOfflineFirst(
                displayName = displayName,
                genericSalts = salts,
                brandMappingId = brandMappingId,
                dosageSchedule = dosageSchedule,
                profileId = profileId,
                profileAllergies = activeAllergies,
            )
            if (result.syncedWithServer) {
                scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
                scheduleAndRecallRepository.refreshRecallsDetailed(profileId)
            }
            val (banner, isError) = when {
                result.syncedWithServer -> "Added ${result.medication.displayName}" to false
                result.apiError is ApiCallError.Offline ->
                    "Saved ${result.medication.displayName} offline (pending sync)" to false
                result.apiError != null ->
                    "Saved locally (${result.apiError.userMessage})" to true
                else -> "Saved ${result.medication.displayName} offline (pending sync)" to false
            }
            _uiState.update {
                it.copy(
                    lastDuplicateAlerts = result.localDuplicateAlerts,
                    lastAllergyAlerts = result.allergyAlerts,
                    statusBanner = banner,
                    statusIsError = isError,
                )
            }
            if (!isError) scheduleBannerAutoDismiss()
        }
    }

    fun toggleActive(id: String, active: Boolean) {
        val profileId = profileRepository.selectedProfileId.value
        viewModelScope.launch {
            val activeAllergies = profileRepository.observeActiveProfile().first().allergyEntries
            val result = cabinetRepository.toggleMedicationActiveDetailed(
                id = id,
                isActive = active,
                profileAllergies = activeAllergies,
            )
            if (result.syncedWithServer) {
                scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
                scheduleAndRecallRepository.refreshRecallsDetailed(profileId)
            }
            val errBanner = result.apiError?.takeIf { it !is ApiCallError.Offline }?.userMessage
            val updatedDetail = _uiState.value.selectedMedicationDetail?.let { detail ->
                if (detail.id == id) detail.copy(isActive = active) else detail
            }
            _uiState.update { state ->
                state.copy(
                    selectedMedicationDetail = updatedDetail,
                    lastDuplicateAlerts = if (active) result.duplicateAlerts else emptyList(),
                    lastAllergyAlerts = if (active) result.allergyAlerts else emptyList(),
                    statusBanner = errBanner ?: state.statusBanner,
                    statusIsError = errBanner != null,
                )
            }
        }
    }

    fun deleteMedication(id: String) {
        val profileId = profileRepository.selectedProfileId.value
        viewModelScope.launch {
            val result = cabinetRepository.deleteMedicationDetailed(id)
            if (result.syncedWithServer) {
                scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
                scheduleAndRecallRepository.refreshRecallsDetailed(profileId)
            }
            if (selectedNodeId.value == id) selectedNodeId.value = null
            _uiState.update { state ->
                val errBanner = result.apiError?.takeIf { it !is ApiCallError.Offline }?.userMessage
                state.copy(
                    selectedMedicationDetail = if (state.selectedMedicationDetail?.id == id) null else state.selectedMedicationDetail,
                    lastDuplicateAlerts = emptyList(),
                    lastAllergyAlerts = emptyList(),
                    statusBanner = errBanner ?: state.statusBanner,
                    statusIsError = errBanner != null,
                )
            }
        }
    }
}
