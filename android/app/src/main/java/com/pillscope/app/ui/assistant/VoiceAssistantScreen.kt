package com.pillscope.app.ui.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import com.pillscope.app.ui.theme.tactilePressScale
import com.pillscope.app.voice.VoiceCommand
import com.pillscope.app.voice.VoiceCommandParser
import com.pillscope.app.voice.VoiceNavTarget
import com.pillscope.app.voice.rememberVoiceInputController
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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
    val reducedMotion = rememberReducedMotionEnabled()
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
        // Editorial header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.aiAssistantTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "${activeProfile.name}, ${language.nativeName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PillScopeColors.ClinicalTeal,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val speakPillBg by animateColorAsState(
                    targetValue = if (autoSpeak) {
                        PillScopeColors.ClinicalTealContainer
                    } else {
                        PillScopeColors.SurfaceTint
                    },
                    animationSpec = tween(PillScopeMotion.FAST_MS),
                    label = "autoSpeakBg",
                )
                Surface(
                    onClick = viewModel::toggleAutoSpeak,
                    shape = CircleShape,
                    color = speakPillBg,
                    border = BorderStroke(
                        1.dp,
                        if (autoSpeak) PillScopeColors.ClinicalTeal.copy(alpha = 0.45f) else PillScopeColors.SlateBorder,
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
                AnimatedVisibility(
                    visible = speechState.isSpeaking || speechState.isLoadingCloudAudio,
                    enter = PillScopeMotion.badgeEnter(reducedMotion),
                    exit = PillScopeMotion.badgeExit(reducedMotion),
                ) {
                    IconButton(onClick = viewModel::stopSpeaking) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = strings.stopSpeaking,
                            tint = PillScopeColors.CriticalCrimson,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = uiState.messages.isNotEmpty(),
                    enter = PillScopeMotion.badgeEnter(reducedMotion),
                    exit = PillScopeMotion.badgeExit(reducedMotion),
                ) {
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

        // Active medicine context strip
        AnimatedVisibility(
            visible = uiState.activeContext != null,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            uiState.activeContext?.let { ctx ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = PillScopeColors.ClinicalTealContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(PillScopeColors.ClinicalTeal),
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
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
                                Text(strings.clearContext, color = PillScopeColors.ClinicalTealDeep)
                            }
                        }
                    }
                }
            }
        }

        // Live voice listening indicator or error / status notice
        AnimatedVisibility(
            visible = voiceInput.isListening || voiceInput.errorMessage != null || uiState.statusNotice != null,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (voiceInput.isListening) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
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
                        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.4f)),
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
                        border = BorderStroke(1.dp, PillScopeColors.SafeEmerald.copy(alpha = 0.35f)),
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

        // Conversation list or acoustic voice hero
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
                    VoiceAssistantWelcomeSurface(
                        isListening = voiceInput.isListening,
                        rmsDb = voiceInput.rmsDb,
                        reducedMotion = reducedMotion,
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
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (uiState.isThinking) {
                item(key = "thinking_row") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PillScopeColors.CardSurface,
                        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                        modifier = Modifier.animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ThinkingDotsIndicator(reducedMotion = reducedMotion)
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
                placeholder = {
                    Text(
                        text = strings.askQuestionPlaceholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                shape = RoundedCornerShape(16.dp),
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

            val micInteraction = remember { MutableInteractionSource() }
            val micBg by animateColorAsState(
                targetValue = if (voiceInput.isListening) {
                    PillScopeColors.CriticalBannerContainer
                } else {
                    PillScopeColors.ClinicalTeal
                },
                animationSpec = tween(PillScopeMotion.FAST_MS),
                label = "composerMicBg",
            )
            val micLiveScale by animateFloatAsState(
                targetValue = if (voiceInput.isListening && !reducedMotion) {
                    1.04f + (voiceInput.rmsDb / 64f).coerceIn(0f, 0.12f)
                } else {
                    1f
                },
                animationSpec = PillScopeMotion.settleSpring(),
                label = "composerMicScale",
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
                interactionSource = micInteraction,
                shape = CircleShape,
                color = micBg,
                modifier = Modifier
                    .size(52.dp)
                    .tactilePressScale(micInteraction, pressedScale = 0.92f)
                    .graphicsLayer {
                        scaleX = micLiveScale
                        scaleY = micLiveScale
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (voiceInput.isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (voiceInput.isListening) strings.stopListening else strings.speakToAsk,
                        tint = if (voiceInput.isListening) {
                            PillScopeColors.OnCriticalBanner
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Focal acoustic voice instrument with live amplitude-reactive equalizer bars
 * and a unified hairline-divided suggested prompts ledger.
 */
@Composable
private fun VoiceAssistantWelcomeSurface(
    isListening: Boolean,
    rmsDb: Float,
    reducedMotion: Boolean,
    onToggleMic: () -> Unit,
    onSelectStarter: (String) -> Unit,
) {
    val strings = LocalAppStrings.current
    val micInteraction = remember { MutableInteractionSource() }
    val orbColor by animateColorAsState(
        targetValue = if (isListening) {
            PillScopeColors.CriticalBannerContainer
        } else {
            PillScopeColors.ClinicalTeal
        },
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "heroMicBg",
    )
    val ringColor by animateColorAsState(
        targetValue = if (isListening) {
            PillScopeColors.CriticalCrimson.copy(alpha = 0.45f)
        } else {
            PillScopeColors.ClinicalTeal.copy(alpha = 0.28f)
        },
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "heroRing",
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = PillScopeColors.CardSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Live 7-bar acoustic waveform equalizer flanking the voice trigger
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AcousticEqualizerBars(
                        isListening = isListening,
                        rmsDb = rmsDb,
                        reducedMotion = reducedMotion,
                        mirror = false,
                        barColor = if (isListening) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTeal,
                    )

                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .tactilePressScale(micInteraction, pressedScale = 0.93f)
                            .border(1.5.dp, ringColor, CircleShape)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            onClick = onToggleMic,
                            interactionSource = micInteraction,
                            shape = CircleShape,
                            color = orbColor,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                                    contentDescription = if (isListening) strings.stopListening else strings.tapToSpeak,
                                    tint = if (isListening) {
                                        PillScopeColors.OnCriticalBanner
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    },
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                    }

                    AcousticEqualizerBars(
                        isListening = isListening,
                        rmsDb = rmsDb,
                        reducedMotion = reducedMotion,
                        mirror = true,
                        barColor = if (isListening) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTeal,
                    )
                }

                AnimatedContent(
                    targetState = isListening,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "voiceHeroHeadline",
                ) { listening ->
                    Text(
                        text = if (listening) strings.listeningNow else strings.tapToSpeak,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (listening) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTealDeep,
                    )
                }

                Text(
                    text = strings.aiWelcomeMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                    textAlign = TextAlign.Center,
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PillScopeColors.SlateSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder.copy(alpha = 0.7f)),
                ) {
                    Text(
                        text = strings.voiceCommandsHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Unified Starter Questions Ledger (hairline-divided rows rather than 4 separate cards)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings.suggestedQuestionsTitle,
                style = MaterialTheme.typography.titleSmall,
                color = PillScopeColors.InkPrimary,
            )
            val starters = listOf(
                strings.starterQ1,
                strings.starterQ2,
                strings.starterQ3,
                strings.starterQ4,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = PillScopeColors.CardSurface,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    starters.forEachIndexed { idx, prompt ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = PillScopeColors.SlateBorder.copy(alpha = 0.65f),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectStarter(prompt) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.InkPrimary,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = PillScopeColors.InkSecondary.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcousticEqualizerBars(
    isListening: Boolean,
    rmsDb: Float,
    reducedMotion: Boolean,
    mirror: Boolean,
    barColor: Color,
) {
    val phase = if (isListening && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "eqPhase")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wavePhase",
        ).value
    } else {
        0f
    }

    val normalizedRms by animateFloatAsState(
        targetValue = if (isListening) ((rmsDb + 2f) / 14f).coerceIn(0.25f, 1f) else 0f,
        animationSpec = tween(110),
        label = "rmsNorm",
    )

    val baseWeights = if (mirror) listOf(0.85f, 0.58f, 0.36f, 0.22f) else listOf(0.22f, 0.36f, 0.58f, 0.85f)

    Canvas(modifier = Modifier.size(width = 34.dp, height = 44.dp)) {
        val barCount = baseWeights.size
        val barWidth = 3.5.dp.toPx()
        val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
        val maxH = size.height
        val minH = 6.dp.toPx()

        for (i in 0 until barCount) {
            val dynamicWave = if (isListening && !reducedMotion) {
                abs(sin(phase + i * 0.95f)).toFloat() * 0.55f + 0.45f
            } else if (isListening) {
                0.75f
            } else {
                0.32f
            }
            val heightFrac = if (isListening) {
                (baseWeights[i] * 0.45f + normalizedRms * dynamicWave * 0.72f).coerceIn(0.18f, 1f)
            } else {
                baseWeights[i] * 0.42f
            }
            val h = (maxH * heightFrac).coerceAtLeast(minH)
            val x = i * (barWidth + gap)
            val top = (maxH - h) / 2f
            drawRoundRect(
                color = barColor.copy(alpha = if (isListening) 0.85f else 0.35f),
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun ThinkingDotsIndicator(reducedMotion: Boolean) {
    val color = PillScopeColors.ClinicalTeal
    val phase = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "thinkingDots")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1050),
                repeatMode = RepeatMode.Restart,
            ),
            label = "dotPhase",
        ).value
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            val alpha = if (reducedMotion) {
                0.75f
            } else {
                (0.32f + 0.68f * ((sin(phase - i * 0.85f) + 1f) / 2f)).toFloat()
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
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
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current

    if (message.isUser) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 290.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 4.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
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
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = PillScopeColors.CardSurface,
            border = BorderStroke(
                width = 1.dp,
                color = if (message.safetyBannerLocked) {
                    PillScopeColors.CriticalCrimson.copy(alpha = 0.55f)
                } else {
                    PillScopeColors.SlateBorder
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight()
                        .background(
                            if (message.safetyBannerLocked) {
                                PillScopeColors.CriticalCrimson
                            } else {
                                PillScopeColors.ClinicalTeal
                            },
                        ),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (message.safetyBannerLocked) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
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
                        style = MaterialTheme.typography.bodyLarge,
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
                                        "${strings.listen} (${message.language.nativeName})"
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
        }

        if (message.followups.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.followups.forEach { followup ->
                    SuggestionChip(
                        onClick = { onFollowupClick(followup) },
                        shape = RoundedCornerShape(10.dp),
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
