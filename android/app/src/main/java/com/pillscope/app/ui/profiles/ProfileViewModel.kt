package com.pillscope.app.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    val profiles: StateFlow<List<UserProfile>> = profileRepository
        .observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfile: StateFlow<UserProfile> = profileRepository
        .observeActiveProfile()
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

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.ensureLocalDefaultProfile()
            refreshProfiles()
        }
    }

    fun refreshProfiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            profileRepository.refreshProfiles()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun selectProfile(profileId: String) {
        profileRepository.selectProfile(profileId)
    }

    fun createProfile(
        name: String,
        relationship: String,
        allergies: List<String>,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val created = profileRepository.createProfile(
                name = name,
                relationship = relationship,
                allergies = allergies,
            )
            _uiState.update { it.copy(statusMessage = "Switched to ${created.name}") }
        }
    }

    fun saveAllergies(profileId: String, allergies: List<String>) {
        viewModelScope.launch {
            val updated = profileRepository.updateProfileAllergies(profileId, allergies)
            if (updated != null) {
                _uiState.update { it.copy(statusMessage = "Saved allergies for ${updated.name}") }
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profileId)
        }
    }
}
