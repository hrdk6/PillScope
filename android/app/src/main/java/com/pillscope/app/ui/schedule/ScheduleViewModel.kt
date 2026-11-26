package com.pillscope.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.ProfileScheduleResponseDto
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.ScheduleAndRecallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduleUiState(
    val isLoading: Boolean = false,
    val statusNotice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val scheduleAndRecallRepository: ScheduleAndRecallRepository,
) : ViewModel() {
    val schedule: StateFlow<ProfileScheduleResponseDto?> = profileRepository.selectedProfileId
        .flatMapLatest { profileId ->
            scheduleAndRecallRepository.observeSchedule(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.selectedProfileId.collect { profileId ->
                refreshScheduleForProfile(profileId)
            }
        }
    }

    fun refreshSchedule() {
        refreshScheduleForProfile(profileRepository.selectedProfileId.value)
    }

    private fun refreshScheduleForProfile(profileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusNotice = null) }
            val result = scheduleAndRecallRepository.refreshSchedule(profileId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusNotice = if (result == null) {
                        "Showing cached offline schedule when available"
                    } else {
                        null
                    },
                )
            }
        }
    }
}
