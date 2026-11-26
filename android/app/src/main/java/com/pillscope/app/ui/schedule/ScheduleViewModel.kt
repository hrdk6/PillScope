package com.pillscope.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.ProfileScheduleResponseDto
import com.pillscope.app.data.repository.CabinetRepository
import com.pillscope.app.data.repository.DoseAdherenceStore
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduleUiState(
    val isLoading: Boolean = false,
    val statusNotice: String? = null,
    val noticeIsError: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val cabinetRepository: CabinetRepository,
    private val scheduleAndRecallRepository: ScheduleAndRecallRepository,
    private val doseAdherenceStore: DoseAdherenceStore,
) : ViewModel() {
    val activeProfileId: StateFlow<String> = profileRepository.selectedProfileId

    val schedule: StateFlow<ProfileScheduleResponseDto?> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeSchedule(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeRecalls: StateFlow<List<ProfileRecallAlertItemDto>> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeRecalls(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeMedicationCount: StateFlow<Int> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            cabinetRepository.observeActiveMedications(profileId).map { it.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val takenDoseKeys: StateFlow<Set<String>> = doseAdherenceStore.takenKeys

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        val activeCabinetSignature = profileRepository.selectedProfileId
            .flatMapLatest { profileId ->
                cabinetRepository.observeActiveMedications(profileId).map { meds ->
                    profileId to meds.map { "${it.id}:${it.dosageSchedule.orEmpty()}:${it.isActive}" }
                }
            }
        val activeAllergiesSignature = profileRepository.observeActiveProfile()
            .map { it.id to it.allergies }

        viewModelScope.launch {
            combine(activeCabinetSignature, activeAllergiesSignature) { (profileId, medSig), (_, allergies) ->
                Triple(profileId, medSig, allergies)
            }
                .distinctUntilChanged()
                .debounce(250L)
                .collect { (profileId, _, _) ->
                    refreshScheduleForProfile(profileId)
                }
        }
    }

    fun onScreenResumed() {
        doseAdherenceStore.refreshIfDayChanged()
    }

    fun refreshSchedule() {
        doseAdherenceStore.refreshIfDayChanged()
        refreshScheduleForProfile(profileRepository.selectedProfileId.value)
    }

    fun resetForAccountChange() {
        _uiState.value = ScheduleUiState()
    }

    fun doseKey(medicationId: String, slot: String, doseNumber: Int): String =
        doseAdherenceStore.doseKey(
            profileId = profileRepository.selectedProfileId.value,
            medicationId = medicationId,
            slot = slot,
            doseNumber = doseNumber,
        )

    fun toggleDoseTaken(medicationId: String, slot: String, doseNumber: Int) {
        doseAdherenceStore.toggleDoseTaken(
            profileId = profileRepository.selectedProfileId.value,
            medicationId = medicationId,
            slot = slot,
            doseNumber = doseNumber,
        )
    }

    private fun refreshScheduleForProfile(profileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusNotice = null, noticeIsError = false) }
            val result = scheduleAndRecallRepository.refreshScheduleDetailed(profileId)
            val (notice, isError) = when (val err = result.error) {
                null -> null to false
                is ApiCallError.Offline -> {
                    val base = if (result.schedule != null) {
                        "Offline — showing last cached schedule."
                    } else {
                        err.userMessage
                    }
                    base to false
                }
                else -> err.userMessage to true
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusNotice = notice,
                    noticeIsError = isError,
                )
            }
        }
    }
}
