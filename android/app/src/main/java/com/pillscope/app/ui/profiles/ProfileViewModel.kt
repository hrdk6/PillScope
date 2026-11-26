package com.pillscope.app.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.ApiCallError
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
    val errorMessage: String? = null,
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
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun refreshProfiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val outcome = profileRepository.refreshProfiles()
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    errorMessage = outcome.error?.takeIf { e -> e !is ApiCallError.Offline }?.userMessage,
                )
            }
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
            val outcome = profileRepository.createProfile(
                name = name,
                relationship = relationship,
                allergies = allergies,
            )
            if (outcome.succeeded && outcome.profile != null) {
                val suffix = if (outcome.syncedWithServer) "" else " (saved offline)"
                _uiState.update {
                    it.copy(
                        statusMessage = "Switched to ${outcome.profile.name}$suffix",
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = outcome.error?.userMessage ?: "Could not create profile.",
                        statusMessage = null,
                    )
                }
            }
        }
    }

    fun editProfile(
        profileId: String,
        name: String,
        relationship: String,
        setAsDefault: Boolean? = null,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val outcome = profileRepository.updateProfileDetails(
                profileId = profileId,
                name = name,
                relationship = relationship,
                setAsDefault = setAsDefault,
            )
            if (outcome.succeeded && outcome.profile != null) {
                val suffix = if (outcome.syncedWithServer) "" else " (pending sync)"
                _uiState.update {
                    it.copy(
                        statusMessage = "Updated ${outcome.profile.name}$suffix",
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = outcome.error?.userMessage ?: "Could not update profile.",
                        statusMessage = null,
                    )
                }
            }
        }
    }

    fun saveAllergies(profileId: String, allergies: List<String>) {
        viewModelScope.launch {
            val outcome = profileRepository.updateProfileAllergies(profileId, allergies)
            if (outcome.succeeded && outcome.profile != null) {
                val suffix = if (outcome.syncedWithServer) "" else " (pending sync)"
                _uiState.update {
                    it.copy(
                        statusMessage = "Saved allergies for ${outcome.profile.name}$suffix",
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = outcome.error?.userMessage ?: "Could not save allergies.",
                        statusMessage = null,
                    )
                }
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val outcome = profileRepository.deleteProfile(profileId)
            if (outcome.succeeded) {
                _uiState.update {
                    it.copy(statusMessage = "Profile deleted.", errorMessage = null)
                }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = outcome.error?.userMessage ?: "Could not delete profile.",
                        statusMessage = null,
                    )
                }
            }
        }
    }
}
