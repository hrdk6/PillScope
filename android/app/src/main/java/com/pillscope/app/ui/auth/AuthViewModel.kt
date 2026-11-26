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
    val emailError: String? = null,
    val passwordError: String? = null,
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

    @Volatile
    private var lastInitializedSessionUserId: String? = null

    init {
        viewModelScope.launch {
            authRepository.restoreSession()
        }
        viewModelScope.launch {
            authRepository.sessionExpiredEvents.collect { reason ->
                lastInitializedSessionUserId = null
                authRepository.handleSessionExpired(reason)
            }
        }
    }

    /**
     * Returns true only when [userId] represents a newly entered account session rather than
     * an Activity recreation (e.g., rotation or Dark Mode toggle) for the same signed-in user.
     */
    fun shouldInitializeSessionFor(userId: String): Boolean {
        if (lastInitializedSessionUserId == userId) return false
        lastInitializedSessionUserId = userId
        return true
    }

    fun acceptOnboardingConsent() {
        authRepository.acceptConsent()
        viewModelScope.launch {
            authRepository.restoreSession()
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                emailError = null,
                passwordError = null,
                errorMessage = null,
                accountActionError = null,
            )
        }
    }

    fun clearEmailError() {
        _uiState.update { it.copy(emailError = null, errorMessage = null) }
    }

    fun clearPasswordError() {
        _uiState.update { it.copy(passwordError = null, errorMessage = null) }
    }

    fun refreshPendingOfflineStatus() {
        viewModelScope.launch {
            val hasPending = authRepository.hasPendingOfflineChanges()
            _uiState.update { it.copy(hasPendingOfflineChanges = hasPending) }
        }
    }

    fun signIn(email: String, password: String) {
        val trimmedEmail = email.trim()
        val emailErr = if (trimmedEmail.isBlank() || !trimmedEmail.contains("@") || !trimmedEmail.substringAfter("@").contains(".")) {
            "Enter a valid email address."
        } else {
            null
        }
        val passwordErr = if (password.isBlank()) "Enter your password." else null
        if (emailErr != null || passwordErr != null) {
            _uiState.update {
                it.copy(
                    emailError = emailErr,
                    passwordError = passwordErr,
                    errorMessage = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    emailError = null,
                    passwordError = null,
                    errorMessage = null,
                )
            }
            when (val res = authRepository.login(trimmedEmail, password)) {
                is AuthActionResult.Success -> {
                    lastInitializedSessionUserId = null
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                }
                is AuthActionResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    fun register(email: String, password: String, displayName: String?) {
        val trimmedEmail = email.trim()
        val emailErr = if (trimmedEmail.isBlank() || !trimmedEmail.contains("@") || !trimmedEmail.substringAfter("@").contains(".")) {
            "Enter a valid email address."
        } else {
            null
        }
        val passwordErr = if (password.length < 8) {
            "Password must be at least 8 characters."
        } else {
            null
        }
        if (emailErr != null || passwordErr != null) {
            _uiState.update {
                it.copy(
                    emailError = emailErr,
                    passwordError = passwordErr,
                    errorMessage = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    emailError = null,
                    passwordError = null,
                    errorMessage = null,
                )
            }
            when (val res = authRepository.register(trimmedEmail, password, displayName)) {
                is AuthActionResult.Success -> {
                    lastInitializedSessionUserId = null
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                }
                is AuthActionResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    fun continueOfflineGuest() {
        viewModelScope.launch {
            lastInitializedSessionUserId = null
            authRepository.enterOfflineGuestMode()
        }
    }

    fun exitOfflineGuestToSignIn() {
        lastInitializedSessionUserId = null
        authRepository.exitOfflineGuestModeToSignIn()
    }

    fun signOut() {
        viewModelScope.launch {
            lastInitializedSessionUserId = null
            authRepository.logout()
        }
    }

    fun deleteAccount(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true, accountActionError = null) }
            val error = authRepository.deleteAccount()
            if (error == null) {
                lastInitializedSessionUserId = null
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
