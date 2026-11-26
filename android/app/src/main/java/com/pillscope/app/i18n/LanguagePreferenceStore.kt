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
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(loadSavedLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _autoSpeakAnswers = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SPEAK, true),
    )
    val autoSpeakAnswers: StateFlow<Boolean> = _autoSpeakAnswers.asStateFlow()

    private val _speechRate = MutableStateFlow(
        prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
    )
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _preferCloudNeuralVoice = MutableStateFlow(
        prefs.getBoolean(KEY_PREFER_CLOUD_NEURAL_VOICE, DEFAULT_PREFER_CLOUD_NEURAL_VOICE),
    )
    val preferCloudNeuralVoice: StateFlow<Boolean> = _preferCloudNeuralVoice.asStateFlow()
    val preferCloudVoice: StateFlow<Boolean> = preferCloudNeuralVoice

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
        val clamped = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        _speechRate.value = clamped
        prefs.edit().putFloat(KEY_SPEECH_RATE, clamped).apply()
    }

    fun setPreferCloudNeuralVoice(prefer: Boolean) {
        _preferCloudNeuralVoice.value = prefer
        prefs.edit().putBoolean(KEY_PREFER_CLOUD_NEURAL_VOICE, prefer).apply()
    }

    fun setPreferCloudVoice(prefer: Boolean) {
        setPreferCloudNeuralVoice(prefer)
    }

    private fun loadSavedLanguage(): AppLanguage {
        val saved = prefs.getString(KEY_LANGUAGE_CODE, null)
        return if (!saved.isNullOrBlank()) {
            AppLanguage.fromCode(saved)
        } else {
            AppLanguage.fromSystemLocale()
        }
    }

    companion object {
        const val DEFAULT_SPEECH_RATE = 0.94f
        const val DEFAULT_PREFER_CLOUD_NEURAL_VOICE = true
        const val MIN_SPEECH_RATE = 0.75f
        const val MAX_SPEECH_RATE = 1.35f
        private const val PREFS_NAME = "pillscope_language_voice_prefs"
        private const val KEY_LANGUAGE_CODE = "selected_language_code_v1"
        private const val KEY_AUTO_SPEAK = "auto_speak_answers_v1"
        private const val KEY_SPEECH_RATE = "speech_rate_v2"
        private const val KEY_PREFER_CLOUD_NEURAL_VOICE = "prefer_cloud_neural_voice_v2"
    }
}
