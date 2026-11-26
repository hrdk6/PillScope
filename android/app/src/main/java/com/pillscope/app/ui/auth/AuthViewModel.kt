package com.pillscope.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.repository.AuthActionResult
import com.pillscope.app.data.repository.AuthRepository
import com.pillscope.app.data.repository.AuthSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val accountActionError: String? = null,
    val isDeletingAccount: Boolean = false,
    val hasPendingOfflineChanges: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val sessionState: StateFlow<AuthSessionState> = authRepository.sessionState

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.restoreSession()
        }
        viewModelScope.launch {
            authRepository.sessionExpiredEvents.collect { reason ->
                authRepository.handleSessionExpired(reason)
            }
        }
    }

    fun acceptOnboardingConsent() {
        authRepository.acceptConsent()
        viewModelScope.launch {
            authRepository.restoreSession()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, accountActionError = null) }
    }

    fun refreshPendingOfflineStatus() {
        viewModelScope.launch {
            val hasPending = authRepository.hasPendingOfflineChanges()
            _uiState.update { it.copy(hasPendingOfflineChanges = hasPending) }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || !email.contains("@")) {
            _uiState.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your password.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            when (val res = authRepository.login(email, password)) {
                is AuthActionResult.Success -> _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                is AuthActionResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    fun register(email: String, password: String, displayName: String?) {
        if (email.isBlank() || !email.contains("@")) {
            _uiState.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (password.length < 8) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 8 characters.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            when (val res = authRepository.register(email, password, displayName)) {
                is AuthActionResult.Success -> _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                is AuthActionResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun deleteAccount(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true, accountActionError = null) }
            val error = authRepository.deleteAccount()
            if (error == null) {
                _uiState.update { it.copy(isDeletingAccount = false, accountActionError = null) }
                onComplete()
            } else {
                _uiState.update {
                    it.copy(isDeletingAccount = false, accountActionError = error.userMessage)
                }
            }
        }
    }
}
