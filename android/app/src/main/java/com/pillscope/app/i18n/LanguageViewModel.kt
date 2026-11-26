package com.pillscope.app.i18n

import androidx.lifecycle.ViewModel
import com.pillscope.app.voice.MultilingualSpeechSpeaker
import com.pillscope.app.voice.SpeechPlaybackState
import com.pillscope.app.voice.SpeechTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val languageStore: LanguagePreferenceStore,
    private val speechSpeaker: MultilingualSpeechSpeaker,
) : ViewModel() {
    val language: StateFlow<AppLanguage> = languageStore.language
    val autoSpeakAnswers: StateFlow<Boolean> = languageStore.autoSpeakAnswers
    val speechRate: StateFlow<Float> = languageStore.speechRate
    val preferCloudNeuralVoice: StateFlow<Boolean> = languageStore.preferCloudNeuralVoice
    val preferCloudVoice: StateFlow<Boolean> = preferCloudNeuralVoice
    val speechState: StateFlow<SpeechPlaybackState> = speechSpeaker.state

    fun setLanguage(language: AppLanguage) {
        speechSpeaker.stop()
        languageStore.setLanguage(language)
    }

    fun setAutoSpeakAnswers(enabled: Boolean) {
        languageStore.setAutoSpeakAnswers(enabled)
        if (!enabled) speechSpeaker.stop()
    }

    fun setSpeechRate(rate: Float) {
        languageStore.setSpeechRate(rate)
    }

    fun setPreferCloudVoice(prefer: Boolean) {
        languageStore.setPreferCloudNeuralVoice(prefer)
    }

    fun speak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = "global_tts",
        tone: SpeechTone = SpeechTone.REASSURING,
    ) {
        speechSpeaker.speak(
            text = text,
            language = language,
            utteranceId = utteranceId,
            tone = tone,
        )
    }

    fun toggleSpeak(
        text: String,
        language: AppLanguage = languageStore.currentLanguage,
        utteranceId: String = "global_tts",
        tone: SpeechTone = SpeechTone.REASSURING,
    ) {
        speechSpeaker.toggleSpeak(
            text = text,
            language = language,
            utteranceId = utteranceId,
            tone = tone,
        )
    }

    fun stopSpeaking() {
        speechSpeaker.stop()
    }
}
