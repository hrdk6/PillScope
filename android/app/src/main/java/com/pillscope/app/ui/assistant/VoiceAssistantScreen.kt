package com.pillscope.app.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.voice.VoiceCommand
import com.pillscope.app.voice.VoiceCommandParser
import com.pillscope.app.voice.VoiceNavTarget
import com.pillscope.app.voice.rememberVoiceInputController

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceAssistantViewModel,
    onVoiceNavigate: (VoiceNavTarget) -> Unit,
    onVoiceSwitchLanguage: (AppLanguage) -> Unit,
    onVoiceSearchMedicine: (String) -> Unit,
    onVoiceReadSafetyReport: () -> Unit,
    onVoiceReadSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val autoSpeak by viewModel.autoSpeakAnswers.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()

    var draftInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val handleTranscript: (String, Boolean) -> Unit = { spoken, isVoice ->
        when (val cmd = VoiceCommandParser.parse(spoken)) {
            is VoiceCommand.Navigate -> {
                viewModel.showStatusNotice(spoken)
                onVoiceNavigate(cmd.target)
            }
            is VoiceCommand.SwitchLanguage -> {
                onVoiceSwitchLanguage(cmd.language)
                viewModel.showStatusNotice("${strings.languageLabel}: ${cmd.language.nativeName}")
            }
            VoiceCommand.ReadSafetyReport -> onVoiceReadSafetyReport()
            VoiceCommand.ReadSchedule -> onVoiceReadSchedule()
            is VoiceCommand.SearchMedicine -> onVoiceSearchMedicine(cmd.query)
            is VoiceCommand.AskAiQuestion -> {
                if (cmd.question.isNotBlank()) {
                    viewModel.askQuestion(cmd.question, triggeredByVoice = isVoice)
                }
            }
        }
    }

    val voiceInput = rememberVoiceInputController(
        language = language,
        promptText = strings.listeningNow,
        onResult = { spoken -> handleTranscript(spoken, true) },
    )

    LaunchedEffect(uiState.messages.size, uiState.isThinking) {
        val lastIdx = (uiState.messages.size + if (uiState.isThinking) 1 else 0)
        if (lastIdx > 0) {
            runCatching { listState.animateScrollToItem(lastIdx) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header row with auto-speak & clear chat controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.aiAssistantTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "${activeProfile.name} · ${language.nativeName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = viewModel::toggleAutoSpeak,
                    shape = CircleShape,
                    color = if (autoSpeak) {
                        PillScopeColors.ClinicalTealContainer
                    } else {
                        PillScopeColors.SurfaceTint
                    },
                    border = BorderStroke(
                        1.dp,
                        if (autoSpeak) PillScopeColors.ClinicalTeal else PillScopeColors.SlateBorder,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (autoSpeak) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = strings.autoSpeakLabel,
                            tint = if (autoSpeak) PillScopeColors.ClinicalTealDeep else PillScopeColors.InkSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (speechState.isSpeaking) strings.speakingNow else strings.listen,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoSpeak) PillScopeColors.ClinicalTealDeep else PillScopeColors.InkSecondary,
                        )
                    }
                }
                if (speechState.isSpeaking || speechState.isLoadingCloudAudio) {
                    IconButton(onClick = viewModel::stopSpeaking) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = strings.stopSpeaking,
                            tint = PillScopeColors.CriticalCrimson,
                        )
                    }
                }
                if (uiState.messages.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearConversation) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = strings.clearChat,
                            tint = PillScopeColors.InkSecondary,
                        )
                    }
                }
            }
        }

        // Active medicine context pill (populated from Safety Analysis)
        uiState.activeContext?.let { ctx ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = PillScopeColors.ClinicalTealContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Medication,
                            contentDescription = null,
                            tint = PillScopeColors.ClinicalTealDeep,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = "${strings.activeContextLabel}: ${ctx.medicineName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = PillScopeColors.ClinicalTealDeep,
                            )
                            if (ctx.salts.isNotEmpty()) {
                                Text(
                                    text = ctx.salts.joinToString(" + "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PillScopeColors.ClinicalTeal,
                                )
                            }
                        }
                    }
                    TextButton(onClick = viewModel::clearMedicineContext) {
                        Text(strings.clearContext)
                    }
                }
            }
        }

        // Live voice listening indicator or error banner
        AnimatedVisibility(visible = voiceInput.isListening || voiceInput.errorMessage != null || uiState.statusNotice != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (voiceInput.isListening) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = PillScopeColors.ClinicalTeal,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = voiceInput.partialTranscript.ifBlank { strings.listeningNow },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            TextButton(onClick = voiceInput.stopListening) {
                                Text(
                                    text = strings.stopListening,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }

                voiceInput.errorMessage?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.ModerateAmberSurface,
                        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = PillScopeColors.ModerateAmberDark,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = voiceInput.clearError,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.close,
                                    tint = PillScopeColors.ModerateAmberDark,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                uiState.statusNotice?.let { notice ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.SafeEmeraldSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.labelMedium,
                                color = PillScopeColors.SafeEmeraldDark,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = viewModel::dismissStatusNotice,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.close,
                                    tint = PillScopeColors.SafeEmeraldDark,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Conversation list or empty voice hero card
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.messages.isEmpty()) {
                item(key = "welcome_hero") {
                    VoiceAssistantWelcomeCard(
                        isListening = voiceInput.isListening,
                        rmsDb = voiceInput.rmsDb,
                        onToggleMic = {
                            viewModel.stopSpeaking()
                            if (voiceInput.isListening) {
                                voiceInput.stopListening()
                            } else {
                                voiceInput.startListening()
                            }
                        },
                        onSelectStarter = { question ->
                            viewModel.askQuestion(question, triggeredByVoice = false)
                        },
                    )
                }
            } else {
                items(uiState.messages, key = { it.id }) { msg ->
                    val isSpeakingThis = (speechState.isSpeaking || speechState.isLoadingCloudAudio) &&
                        speechState.activeUtteranceId == msg.id
                    ChatMessageBubble(
                        message = msg,
                        isSpeakingThis = isSpeakingThis,
                        isLoadingAudio = speechState.isLoadingCloudAudio && speechState.activeUtteranceId == msg.id,
                        onToggleSpeak = { viewModel.toggleSpeakMessage(msg) },
                        onFollowupClick = { followup ->
                            viewModel.askQuestion(followup, triggeredByVoice = false)
                        },
                    )
                }
            }

            if (uiState.isThinking) {
                item(key = "thinking_row") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = PillScopeColors.CardSurface,
                        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LoadingIndicator(
                                modifier = Modifier.size(22.dp),
                                color = PillScopeColors.ClinicalTeal,
                            )
                            Text(
                                text = strings.thinkingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.InkSecondary,
                            )
                        }
                    }
                }
            }
        }

        // Bottom Voice + Text Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draftInput,
                onValueChange = { draftInput = it },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                placeholder = { Text(strings.askQuestionPlaceholder) },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val text = draftInput.trim()
                        if (text.isNotEmpty()) {
                            draftInput = ""
                            handleTranscript(text, false)
                        }
                    },
                ),
                trailingIcon = {
                    if (draftInput.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val text = draftInput.trim()
                                draftInput = ""
                                handleTranscript(text, false)
                            },
                            enabled = !uiState.isThinking,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = strings.sendQuestion,
                                tint = PillScopeColors.ClinicalTeal,
                            )
                        }
                    }
                },
            )

            val micScale by animateFloatAsState(
                targetValue = if (voiceInput.isListening) {
                    1.08f + (voiceInput.rmsDb / 48f).coerceIn(0f, 0.22f)
                } else {
                    1f
                },
                label = "micScale",
            )
            Surface(
                onClick = {
                    viewModel.stopSpeaking()
                    if (voiceInput.isListening) {
                        voiceInput.stopListening()
                    } else {
                        voiceInput.startListening()
                    }
                },
                shape = CircleShape,
                color = if (voiceInput.isListening) {
                    PillScopeColors.CriticalBannerContainer
                } else {
                    PillScopeColors.ClinicalTeal
                },
                shadowElevation = 3.dp,
                modifier = Modifier
                    .size(54.dp)
                    .scale(micScale),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (voiceInput.isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (voiceInput.isListening) strings.stopListening else strings.speakToAsk,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceAssistantWelcomeCard(
    isListening: Boolean,
    rmsDb: Float,
    onToggleMic: () -> Unit,
    onSelectStarter: (String) -> Unit,
) {
    val strings = LocalAppStrings.current
    val pulse = rememberInfiniteTransition(label = "voiceOrbPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val haloScale = if (isListening) {
                        pulseScale + (rmsDb / 40f).coerceIn(0f, 0.2f)
                    } else {
                        1.02f
                    }
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(haloScale)
                            .background(
                                color = if (isListening) {
                                    PillScopeColors.CriticalCrimson.copy(alpha = 0.16f)
                                } else {
                                    PillScopeColors.ClinicalTealContainer.copy(alpha = 0.7f)
                                },
                                shape = CircleShape,
                            ),
                    )
                    Surface(
                        onClick = onToggleMic,
                        shape = CircleShape,
                        color = if (isListening) {
                            PillScopeColors.CriticalBannerContainer
                        } else {
                            PillScopeColors.ClinicalTeal
                        },
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                                contentDescription = if (isListening) strings.stopListening else strings.tapToSpeak,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }

                Text(
                    text = if (isListening) strings.listeningNow else strings.tapToSpeak,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isListening) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTealDeep,
                    fontWeight = FontWeight.Bold,
                )

                Text(
                    text = strings.aiWelcomeMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.SurfaceTint,
                ) {
                    Text(
                        text = strings.voiceCommandsHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = PillScopeColors.ClinicalTeal,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = strings.suggestedQuestionsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.InkPrimary,
                )
            }
            val starters = listOf(
                strings.starterQ1,
                strings.starterQ2,
                strings.starterQ3,
                strings.starterQ4,
            )
            starters.forEach { prompt ->
                Surface(
                    onClick = { onSelectStarter(prompt) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CardSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatMessageBubble(
    message: AssistantChatMessage,
    isSpeakingThis: Boolean,
    isLoadingAudio: Boolean,
    onToggleSpeak: () -> Unit,
    onFollowupClick: (String) -> Unit,
) {
    val strings = LocalAppStrings.current

    if (message.isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 290.dp),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 6.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp,
                ),
                color = PillScopeColors.ClinicalTeal,
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp,
            ),
            colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
            border = BorderStroke(
                width = if (message.safetyBannerLocked) 1.5.dp else 1.dp,
                color = if (message.safetyBannerLocked) {
                    PillScopeColors.CriticalCrimson
                } else {
                    PillScopeColors.SlateBorder
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (message.safetyBannerLocked) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.CriticalBannerContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.GppBad,
                                contentDescription = null,
                                tint = PillScopeColors.OnCriticalBanner,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = strings.criticalSafetyAlert,
                                style = MaterialTheme.typography.labelLarge,
                                color = PillScopeColors.OnCriticalBanner,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = onToggleSpeak,
                        modifier = Modifier.heightIn(min = 38.dp),
                        shape = CircleShape,
                        color = if (isSpeakingThis) {
                            PillScopeColors.CriticalCrimsonSurface
                        } else {
                            PillScopeColors.ClinicalTealContainer
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isLoadingAudio) {
                                LoadingIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = PillScopeColors.ClinicalTealDeep,
                                )
                            } else {
                                Icon(
                                    imageVector = if (isSpeakingThis) {
                                        Icons.Default.Stop
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = if (isSpeakingThis) strings.stopSpeaking else strings.listen,
                                    tint = if (isSpeakingThis) {
                                        PillScopeColors.CriticalCrimsonDark
                                    } else {
                                        PillScopeColors.ClinicalTealDeep
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = if (isSpeakingThis) {
                                    strings.stopSpeaking
                                } else {
                                    "${strings.listen} · ${message.language.nativeName}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSpeakingThis) {
                                    PillScopeColors.CriticalCrimsonDark
                                } else {
                                    PillScopeColors.ClinicalTealDeep
                                },
                            )
                        }
                    }

                    if (message.citations.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = PillScopeColors.SafeEmeraldSurface,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = PillScopeColors.SafeEmerald,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "FDA (${message.citations.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PillScopeColors.SafeEmeraldDark,
                                )
                            }
                        }
                    }
                }

                if (message.disclaimer.isNotBlank()) {
                    Text(
                        text = message.disclaimer,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }
        }

        if (message.followups.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.followups.forEach { followup ->
                    SuggestionChip(
                        onClick = { onFollowupClick(followup) },
                        label = {
                            Text(
                                text = followup,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
    }
}
