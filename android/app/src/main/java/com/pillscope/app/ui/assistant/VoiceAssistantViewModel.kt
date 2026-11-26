package com.pillscope.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pillscope.app.data.remote.dto.AssistantTurnDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.repository.AssistantRepository
import com.pillscope.app.data.repository.ProfileRepository
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LanguagePreferenceStore
import com.pillscope.app.voice.MultilingualSpeechSpeaker
import com.pillscope.app.voice.SpeechPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AssistantChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val spokenText: String = text,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val overallRisk: RiskLevel = RiskLevel.NONE,
    val safetyBannerLocked: Boolean = false,
    val citations: List<MonographCitationDto> = emptyList(),
    val followups: List<String> = emptyList(),
    val disclaimer: String = "",
    val isOfflineFallback: Boolean = false,
)

data class AssistantMedicineContext(
    val scanId: String? = null,
    val medicineName: String,
    val salts: List<String> = emptyList(),
)

data class VoiceAssistantUiState(
    val messages: List<AssistantChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val activeContext: AssistantMedicineContext? = null,
    val statusNotice: String? = null,
)

@HiltViewModel
class VoiceAssistantViewModel @Inject constructor(
    private val assistantRepository: AssistantRepository,
    private val profileRepository: ProfileRepository,
    private val languageStore: LanguagePreferenceStore,
    private val speechSpeaker: MultilingualSpeechSpeaker,
) : ViewModel() {
    val activeProfile: StateFlow<UserProfile> = profileRepository.observeActiveProfile()
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

    val language: StateFlow<AppLanguage> = languageStore.language
    val autoSpeakAnswers: StateFlow<Boolean> = languageStore.autoSpeakAnswers
    val speechState: StateFlow<SpeechPlaybackState> = speechSpeaker.state

    private val _uiState = MutableStateFlow(VoiceAssistantUiState())
    val uiState: StateFlow<VoiceAssistantUiState> = _uiState.asStateFlow()

    fun setMedicineContext(
        scanId: String?,
        medicineName: String,
        salts: List<String>,
    ) {
        if (medicineName.isBlank() && salts.isEmpty()) return
        _uiState.update {
            it.copy(
                activeContext = AssistantMedicineContext(
                    scanId = scanId,
                    medicineName = medicineName.ifBlank { salts.joinToString(" + ") },
                    salts = salts,
                ),
            )
        }
    }

    fun clearMedicineContext() {
        _uiState.update { it.copy(activeContext = null) }
    }

    fun clearConversation() {
        speechSpeaker.stop()
        _uiState.update { it.copy(messages = emptyList(), statusNotice = null) }
    }

    fun resetForAccountChange() {
        speechSpeaker.stop()
        _uiState.value = VoiceAssistantUiState()
    }

    fun toggleAutoSpeak() {
        val next = !languageStore.autoSpeakAnswers.value
        languageStore.setAutoSpeakAnswers(next)
        if (!next) speechSpeaker.stop()
    }

    fun toggleSpeakMessage(message: AssistantChatMessage) {
        speechSpeaker.toggleSpeak(
            text = message.spokenText.ifBlank { message.text },
            language = message.language,
            utteranceId = message.id,
        )
    }

    fun stopSpeaking() {
        speechSpeaker.stop()
    }

    fun dismissStatusNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun showStatusNotice(message: String) {
        _uiState.update { it.copy(statusNotice = message) }
    }

    fun askQuestion(rawQuestion: String, triggeredByVoice: Boolean = false) {
        val question = rawQuestion.trim()
        if (question.isEmpty() || _uiState.value.isThinking) return

        val chosenLanguage = languageStore.currentLanguage
        val userMsg = AssistantChatMessage(
            isUser = true,
            text = question,
            language = chosenLanguage,
        )
        val historyTurns = _uiState.value.messages.takeLast(6).map { msg ->
            AssistantTurnDto(
                role = if (msg.isUser) "user" else "assistant",
                text = msg.text.take(800),
            )
        }
        val ctx = _uiState.value.activeContext

        speechSpeaker.stop()
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                isThinking = true,
                statusNotice = null,
            )
        }

        viewModelScope.launch {
            val profile = runCatching { profileRepository.observeActiveProfile().first() }
                .getOrDefault(activeProfile.value)

            val outcome = assistantRepository.askQuestion(
                question = question,
                language = chosenLanguage,
                profile = profile,
                scanId = ctx?.scanId,
                contextMedicineName = ctx?.medicineName,
                contextSalts = ctx?.salts.orEmpty(),
                history = historyTurns,
            )
            val resp = outcome.response
            val replyLanguage = AppLanguage.fromCode(resp.language)
            val assistantMsg = AssistantChatMessage(
                isUser = false,
                text = resp.answerText,
                spokenText = resp.spokenText.ifBlank { resp.answerText },
                language = replyLanguage,
                overallRisk = RiskLevel.fromApi(resp.overallRisk),
                safetyBannerLocked = resp.safetyBannerLocked,
                citations = resp.citations,
                followups = resp.suggestedFollowups,
                disclaimer = resp.disclaimer,
                isOfflineFallback = outcome.usedOfflineFallback,
            )

            _uiState.update {
                it.copy(
                    messages = it.messages + assistantMsg,
                    isThinking = false,
                )
            }

            if (languageStore.autoSpeakAnswers.value || triggeredByVoice) {
                speechSpeaker.speak(
                    text = assistantMsg.spokenText,
                    language = replyLanguage,
                    utteranceId = assistantMsg.id,
                )
            }
        }
    }
}
