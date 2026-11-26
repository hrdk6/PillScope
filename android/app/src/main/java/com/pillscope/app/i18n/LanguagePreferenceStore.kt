package com.pillscope.app.i18n

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguagePreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(loadSavedLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _autoSpeakAnswers = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SPEAK, true),
    )
    val autoSpeakAnswers: StateFlow<Boolean> = _autoSpeakAnswers.asStateFlow()

    private val _speechRate = MutableStateFlow(
        prefs.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.75f, 1.35f),
    )
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _preferCloudVoice = MutableStateFlow(
        prefs.getBoolean(KEY_PREFER_CLOUD_VOICE, false),
    )
    val preferCloudVoice: StateFlow<Boolean> = _preferCloudVoice.asStateFlow()

    val currentLanguage: AppLanguage
        get() = _language.value

    fun setLanguage(language: AppLanguage) {
        if (_language.value == language && prefs.contains(KEY_LANGUAGE_CODE)) return
        _language.value = language
        prefs.edit().putString(KEY_LANGUAGE_CODE, language.code).apply()
    }

    fun setAutoSpeakAnswers(enabled: Boolean) {
        _autoSpeakAnswers.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply()
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.75f, 1.35f)
        _speechRate.value = clamped
        prefs.edit().putFloat(KEY_SPEECH_RATE, clamped).apply()
    }

    fun setPreferCloudVoice(prefer: Boolean) {
        _preferCloudVoice.value = prefer
        prefs.edit().putBoolean(KEY_PREFER_CLOUD_VOICE, prefer).apply()
    }

    private fun loadSavedLanguage(): AppLanguage {
        val saved = prefs.getString(KEY_LANGUAGE_CODE, null)
        return if (!saved.isNullOrBlank()) {
            AppLanguage.fromCode(saved)
        } else {
            AppLanguage.fromSystemLocale()
        }
    }

    private companion object {
        const val PREFS_NAME = "pillscope_language_voice_prefs"
        const val KEY_LANGUAGE_CODE = "selected_language_code_v1"
        const val KEY_AUTO_SPEAK = "auto_speak_answers_v1"
        const val KEY_SPEECH_RATE = "speech_rate_v1"
        const val KEY_PREFER_CLOUD_VOICE = "prefer_cloud_voice_v1"
    }
}
