package com.pillscope.app.data.repository

import com.pillscope.app.data.local.PillScopeDatabase
import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.AuthTokenProvider
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.SessionEventBus
import com.pillscope.app.data.remote.dto.LoginRequestDto
import com.pillscope.app.data.remote.dto.LogoutRequestDto
import com.pillscope.app.data.remote.dto.RegisterRequestDto
import com.pillscope.app.data.remote.dto.UserResponseDto
import com.pillscope.app.data.remote.toApiCallError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthSessionState {
    data object Loading : AuthSessionState
    data object NeedsConsent : AuthSessionState
    data class Unauthenticated(val notice: String? = null) : AuthSessionState
    data class Authenticated(
        val user: UserResponseDto,
        val offlineResumed: Boolean = false,
    ) : AuthSessionState
}

sealed interface AuthActionResult {
    data class Success(val user: UserResponseDto) : AuthActionResult
    data class Failure(val error: ApiCallError) : AuthActionResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: PillScopeApi,
    private val tokenProvider: AuthTokenProvider,
    private val database: PillScopeDatabase,
    private val json: Json,
    private val sessionEventBus: SessionEventBus,
) {
    private val _sessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Loading)
    val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    val sessionExpiredEvents = sessionEventBus.sessionExpired

    suspend fun restoreSession() {
        if (!tokenProvider.isConsentAccepted()) {
            _sessionState.value = AuthSessionState.NeedsConsent
            return
        }
        val accessToken = tokenProvider.getAccessToken()
        val refreshToken = tokenProvider.getRefreshToken()
        if (accessToken.isNullOrBlank() && refreshToken.isNullOrBlank()) {
            _sessionState.value = AuthSessionState.Unauthenticated()
            return
        }

        val remoteMe = runCatching { api.getMe() }
        remoteMe.fold(
            onSuccess = { user ->
                tokenProvider.saveUserSummary(user.email, user.displayName)
                _sessionState.value = AuthSessionState.Authenticated(user = user)
            },
            onFailure = { exc ->
                when (val err = exc.toApiCallError(json)) {
                    is ApiCallError.Unauthorized -> {
                        tokenProvider.clearTokens()
                        _sessionState.value = AuthSessionState.Unauthenticated(err.userMessage)
                    }
                    is ApiCallError.Offline -> {
                        val cachedEmail = tokenProvider.getCachedUserEmail()
                        if (!cachedEmail.isNullOrBlank()) {
                            _sessionState.value = AuthSessionState.Authenticated(
                                user = UserResponseDto(
                                    id = "offline-session",
                                    email = cachedEmail,
                                    displayName = tokenProvider.getCachedUserDisplayName(),
                                    isActive = true,
                                ),
                                offlineResumed = true,
                            )
                        } else {
                            _sessionState.value = AuthSessionState.Unauthenticated(err.userMessage)
                        }
                    }
                    else -> {
                        val cachedEmail = tokenProvider.getCachedUserEmail()
                        if (!cachedEmail.isNullOrBlank()) {
                            _sessionState.value = AuthSessionState.Authenticated(
                                user = UserResponseDto(
                                    id = "offline-session",
                                    email = cachedEmail,
                                    displayName = tokenProvider.getCachedUserDisplayName(),
                                    isActive = true,
                                ),
                                offlineResumed = true,
                            )
                        } else {
                            _sessionState.value = AuthSessionState.Unauthenticated(err.userMessage)
                        }
                    }
                }
            },
        )
    }

    fun acceptConsent() {
        tokenProvider.setConsentAccepted(true)
        val hasTokens = !tokenProvider.getAccessToken().isNullOrBlank() ||
            !tokenProvider.getRefreshToken().isNullOrBlank()
        _sessionState.value = if (hasTokens) {
            AuthSessionState.Loading
        } else {
            AuthSessionState.Unauthenticated()
        }
    }

    suspend fun login(email: String, password: String): AuthActionResult {
        val result = runCatching {
            api.login(LoginRequestDto(email = email.trim(), password = password))
        }
        return result.fold(
            onSuccess = { auth ->
                tokenProvider.saveTokens(
                    accessToken = auth.tokens.accessToken,
                    refreshToken = auth.tokens.refreshToken,
                )
                tokenProvider.saveUserSummary(auth.user.email, auth.user.displayName)
                _sessionState.value = AuthSessionState.Authenticated(auth.user)
                AuthActionResult.Success(auth.user)
            },
            onFailure = { exc ->
                AuthActionResult.Failure(exc.toApiCallError(json))
            },
        )
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
    ): AuthActionResult {
        val result = runCatching {
            api.register(
                RegisterRequestDto(
                    email = email.trim(),
                    password = password,
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        return result.fold(
            onSuccess = { auth ->
                tokenProvider.saveTokens(
                    accessToken = auth.tokens.accessToken,
                    refreshToken = auth.tokens.refreshToken,
                )
                tokenProvider.saveUserSummary(auth.user.email, auth.user.displayName)
                _sessionState.value = AuthSessionState.Authenticated(auth.user)
                AuthActionResult.Success(auth.user)
            },
            onFailure = { exc ->
                AuthActionResult.Failure(exc.toApiCallError(json))
            },
        )
    }

    suspend fun logout() {
        val refreshToken = tokenProvider.getRefreshToken()
        runCatching {
            api.logout(LogoutRequestDto(refreshToken = refreshToken))
        }
        clearLocalSessionData()
        _sessionState.value = AuthSessionState.Unauthenticated("Signed out.")
    }

    suspend fun deleteAccount(): ApiCallError? {
        val result = runCatching {
            val response = api.deleteMe()
            if (!response.isSuccessful && response.code() != 404) {
                throw retrofit2.HttpException(response)
            }
        }
        return result.fold(
            onSuccess = {
                clearLocalSessionData()
                _sessionState.value = AuthSessionState.Unauthenticated(
                    "Your account and all stored medication data have been permanently deleted.",
                )
                null
            },
            onFailure = { exc ->
                exc.toApiCallError(json)
            },
        )
    }

    suspend fun handleSessionExpired(reason: String) {
        tokenProvider.clearTokens()
        _sessionState.value = AuthSessionState.Unauthenticated(reason)
    }

    private suspend fun clearLocalSessionData() {
        tokenProvider.clearTokens()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
        }
    }
}
