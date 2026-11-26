package com.pillscope.app.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pillscope.app.data.remote.dto.RefreshRequestDto
import com.pillscope.app.data.remote.dto.TokenResponseDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface AuthTokenProvider {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String, refreshToken: String)
    fun clearTokens()

    fun isConsentAccepted(): Boolean = true
    fun setConsentAccepted(accepted: Boolean) = Unit

    fun getCachedUserEmail(): String? = null
    fun getCachedUserDisplayName(): String? = null
    fun saveUserSummary(email: String, displayName: String?) = Unit
}

@Singleton
class SessionEventBus @Inject constructor() {
    private val _sessionExpired = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val sessionExpired: SharedFlow<String> = _sessionExpired.asSharedFlow()

    fun notifySessionExpired(reason: String = "Your session expired. Please sign in again.") {
        _sessionExpired.tryEmit(reason)
    }
}

open class EncryptedAuthTokenStore(context: Context) : AuthTokenProvider {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getAccessToken(): String? = decrypt(prefs.getString(KEY_ACCESS, null))

    override fun getRefreshToken(): String? = decrypt(prefs.getString(KEY_REFRESH, null))

    override fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS, encrypt(accessToken))
            .putString(KEY_REFRESH, encrypt(refreshToken))
            .apply()
    }

    override fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .apply()
    }

    override fun isConsentAccepted(): Boolean = prefs.getBoolean(KEY_CONSENT_ACCEPTED, false)

    override fun setConsentAccepted(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_CONSENT_ACCEPTED, accepted).apply()
    }

    override fun getCachedUserEmail(): String? = decrypt(prefs.getString(KEY_USER_EMAIL, null))

    override fun getCachedUserDisplayName(): String? = decrypt(prefs.getString(KEY_USER_NAME, null))

    override fun saveUserSummary(email: String, displayName: String?) {
        prefs.edit()
            .putString(KEY_USER_EMAIL, encrypt(email))
            .putString(KEY_USER_NAME, displayName?.let(::encrypt))
            .apply()
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
    }.getOrNull()

    private fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateKey() ?: return "$PLAIN_PREFIX$plaintext"
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$ENC_PREFIX$ivB64:$ctB64"
        }.getOrElse { "$PLAIN_PREFIX$plaintext" }
    }

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        if (stored.startsWith(PLAIN_PREFIX)) {
            return stored.removePrefix(PLAIN_PREFIX)
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            // Backward compatibility with legacy unencrypted entries
            return stored
        }
        val secretKey = getOrCreateKey() ?: return null
        return runCatching {
            val payload = stored.removePrefix(ENC_PREFIX)
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "pillscope_secure_auth_prefs"
        const val KEY_ACCESS = "access_token_enc"
        const val KEY_REFRESH = "refresh_token_enc"
        const val KEY_CONSENT_ACCEPTED = "consent_accepted_v1"
        const val KEY_USER_EMAIL = "user_email_enc"
        const val KEY_USER_NAME = "user_name_enc"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pillscope_auth_aes256_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val ENC_PREFIX = "enc:v1:"
        const val PLAIN_PREFIX = "plain:v1:"
    }
}

class SharedPrefsAuthTokenStore(context: Context) : EncryptedAuthTokenStore(context)

class BearerAuthInterceptor(
    private val tokenProvider: AuthTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (
            path.endsWith("/api/v1/auth/login") ||
            path.endsWith("/api/v1/auth/register") ||
            path.endsWith("/api/v1/auth/refresh")
        ) {
            return chain.proceed(original)
        }
        val token = tokenProvider.getAccessToken()
        if (token.isNullOrBlank() || original.header("Authorization") != null) {
            return chain.proceed(original)
        }
        val authorized = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }
}

class TokenRefreshAuthenticator(
    private val tokenProvider: AuthTokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val sessionEventBus: SessionEventBus,
    private val refreshClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) : Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val requestPath = response.request.url.encodedPath
        if (
            requestPath.endsWith("/api/v1/auth/login") ||
            requestPath.endsWith("/api/v1/auth/register") ||
            requestPath.endsWith("/api/v1/auth/refresh")
        ) {
            return null
        }

        if (responseCount(response) >= 2) {
            tokenProvider.clearTokens()
            sessionEventBus.notifySessionExpired()
            return null
        }

        val failedAccessToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(refreshLock) {
            val latestAccess = tokenProvider.getAccessToken()
            if (!latestAccess.isNullOrBlank() && latestAccess != failedAccessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $latestAccess")
                    .build()
            }

            val refreshToken = tokenProvider.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                tokenProvider.clearTokens()
                sessionEventBus.notifySessionExpired("Please sign in to continue.")
                return null
            }

            val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val bodyJson = json.encodeToString(RefreshRequestDto(refreshToken = refreshToken))
            val refreshReq = Request.Builder()
                .url("${normalizedBase}api/v1/auth/refresh")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val refreshedTokens = runCatching {
                refreshClient.newCall(refreshReq).execute().use { refreshResp ->
                    if (!refreshResp.isSuccessful) return@use null
                    val raw = refreshResp.body?.string().orEmpty()
                    if (raw.isBlank()) return@use null
                    json.decodeFromString<TokenResponseDto>(raw)
                }
            }.getOrNull()

            if (refreshedTokens == null) {
                tokenProvider.clearTokens()
                sessionEventBus.notifySessionExpired("Your session expired. Please sign in again.")
                return null
            }

            tokenProvider.saveTokens(
                accessToken = refreshedTokens.accessToken,
                refreshToken = refreshedTokens.refreshToken,
            )
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshedTokens.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
