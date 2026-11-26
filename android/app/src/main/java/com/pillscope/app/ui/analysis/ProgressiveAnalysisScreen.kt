package com.pillscope.app.ui.analysis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.LifestyleTriggerDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.repository.AnalysisStage
import com.pillscope.app.data.repository.CachedScanSummary
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.data.repository.ScanAnalysisState
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.common.DosagePresetChips
import com.pillscope.app.ui.common.formatInteractionSourceLabel
import com.pillscope.app.ui.common.formatMonographLinkLabel
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.common.severityPillColors
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import com.pillscope.app.ui.theme.tactilePressScale
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveAnalysisScreen(
    viewModel: AnalysisViewModel,
    onNavigateToScan: (() -> Unit)? = null,
    onAskAiAboutScan: ((scanId: String?, medicineName: String, resolvedSalts: List<String>) -> Unit)? = null,
    onAddToCabinet: ((displayName: String, canonicalSalts: List<String>, dosageSchedule: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val currentLanguage = LocalAppLanguage.current
    val reducedMotion = rememberReducedMotionEnabled()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val cachedHistory by viewModel.cachedHistory.collectAsStateWithLifecycle()
    val canRetry by viewModel.canRetry.collectAsStateWithLifecycle()
    val isAlreadyInCabinet by viewModel.isCurrentScanSavedInCabinet.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()

    val reportUtteranceId = "report_${state.scanId ?: state.submittedQuery}"
    val summaryUtteranceId = "summary_${state.scanId ?: state.submittedQuery}"
    val isSpeakingReport = (speechState.isSpeaking || speechState.isLoadingCloudAudio) &&
        speechState.activeUtteranceId == reportUtteranceId
    val isSpeakingSummary = (speechState.isSpeaking || speechState.isLoadingCloudAudio) &&
        speechState.activeUtteranceId == summaryUtteranceId

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    var selectedTrigger by remember { mutableStateOf<LifestyleTriggerDto?>(null) }
    var showAddToCabinetDialog by remember { mutableStateOf(false) }
    var draftCabinetName by remember { mutableStateOf("") }
    var draftDosageSchedule by remember { mutableStateOf("") }
    var showAllHistory by remember(activeProfile.id) { mutableStateOf(false) }

    val scanKey = state.scanId ?: "${state.submittedQuery}:${state.resolvedSalts.joinToString(",")}"

    val hasPinnedDanger = state.safetyBannerLocked ||
        state.allergyAlerts.isNotEmpty() ||
        state.duplicateSalts.isNotEmpty() ||
        state.activeRecalls.isNotEmpty()

    val isStreaming = state.stage != AnalysisStage.COMPLETE &&
        state.stage != AnalysisStage.IDLE &&
        state.stage != AnalysisStage.ERROR

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hasPinnedDanger,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Column {
                PinnedCriticalSafetyBanner(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.7f))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Focal centerpiece: Calibrated clinical risk dial + integrated SSE stage ledger
            RiskGaugeHeroSurface(
                state = state,
                isStreaming = isStreaming,
                reducedMotion = reducedMotion,
            )

            if (state.stage == AnalysisStage.IDLE) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CardSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = strings.checkSafety,
                                style = MaterialTheme.typography.titleLarge,
                                color = PillScopeColors.InkPrimary,
                            )
                            Text(
                                text = activeProfile.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = PillScopeColors.ClinicalTeal,
                            )
                        }
                        Text(
                            text = strings.checkAgainstCabinetBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkSecondary,
                        )
                        if (onNavigateToScan != null) {
                            Spacer(Modifier.height(2.dp))
                            Button(
                                onClick = onNavigateToScan,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PillScopeColors.ClinicalTeal,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(strings.scanOrSearchMedicine)
                            }
                        }
                    }
                }
            } else {
                // Voice Readout + Ask AI quick actions
                AnimatedVisibility(
                    visible = state.stage == AnalysisStage.COMPLETE || state.clinicalSummary != null,
                    enter = PillScopeMotion.panelEnter(reducedMotion),
                    exit = PillScopeMotion.panelExit(reducedMotion),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::toggleSpeakCurrentReport,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSpeakingReport) {
                                    PillScopeColors.CriticalCrimson.copy(alpha = 0.6f)
                                } else {
                                    PillScopeColors.SlateBorder
                                },
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isSpeakingReport) {
                                    PillScopeColors.CriticalCrimson
                                } else {
                                    PillScopeColors.InkPrimary
                                },
                            ),
                        ) {
                            Icon(
                                imageVector = if (isSpeakingReport) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (isSpeakingReport) strings.stopSpeaking else strings.readFullReport)
                        }
                        if (onAskAiAboutScan != null) {
                            Button(
                                onClick = {
                                    onAskAiAboutScan(
                                        state.scanId,
                                        state.submittedQuery.ifBlank { state.resolvedSalts.joinToString(" + ") },
                                        state.resolvedSalts,
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PillScopeColors.ClinicalTeal,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(strings.askAiAboutMedicine)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.lifestyleTriggers.isNotEmpty(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.foodAlcoholLifestyle,
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    LifestyleTriggerChipRow(
                        triggers = state.lifestyleTriggers,
                        onSelectTrigger = { selectedTrigger = it },
                    )
                }
            }

            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                state.errorMessage?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = PillScopeColors.ModerateAmberSurface,
                        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "${strings.checkIncomplete}: $err",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.ModerateAmberDark,
                            )
                            if (canRetry) {
                                OutlinedButton(
                                    onClick = viewModel::retryLastAnalysis,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, PillScopeColors.ModerateAmber),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = PillScopeColors.ModerateAmberDark,
                                    ),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(strings.retrySafetyCheck)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.interactions.isNotEmpty(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${strings.interactionFindings} (${state.interactions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    state.interactions.forEach { interaction ->
                        InteractionRiskRailCard(
                            interaction = interaction,
                            reducedMotion = reducedMotion,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.clinicalSummary != null,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                state.clinicalSummary?.let { summary ->
                    val summaryLang = summary.language.takeIf { it.isNotBlank() }?.let(AppLanguage::fromCode)
                        ?: currentLanguage
                    ClinicalSummaryCard(
                        headline = summary.headline,
                        explanation = summary.patientExplanation,
                        nextSteps = summary.actionableNextSteps,
                        abstained = summary.abstained,
                        citationVerified = state.citationVerified,
                        disclaimer = summary.disclaimer,
                        languageLabel = summaryLang.nativeName,
                        isSpeaking = isSpeakingSummary,
                        onToggleSpeak = viewModel::toggleSpeakClinicalSummary,
                    )
                }
            }

            // Place the cabinet save CTA after interaction findings and clinical briefing so
            // users read warnings before adding a flagged medicine to a family profile.
            AnimatedVisibility(
                visible = state.stage == AnalysisStage.COMPLETE &&
                    state.resolvedSalts.isNotEmpty() &&
                    onAddToCabinet != null,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                if (onAddToCabinet != null) {
                    if (isAlreadyInCabinet) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = PillScopeColors.SafeEmeraldSurface,
                            border = BorderStroke(1.dp, PillScopeColors.SafeEmerald.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PillScopeColors.SafeEmerald,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "${strings.inCabinetBadge} (${activeProfile.name})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PillScopeColors.SafeEmeraldDark,
                                )
                            }
                        }
                    } else {
                        val saveInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                draftCabinetName = state.submittedQuery.ifBlank {
                                    state.resolvedSalts.joinToString(" + ")
                                }
                                draftDosageSchedule = ""
                                showAddToCabinetDialog = true
                            },
                            interactionSource = saveInteraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .tactilePressScale(saveInteraction),
                            shape = RoundedCornerShape(12.dp),
                            colors = if (hasPinnedDanger) {
                                ButtonDefaults.buttonColors(
                                    containerColor = PillScopeColors.CriticalBannerContainer,
                                    contentColor = PillScopeColors.OnCriticalBanner,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = PillScopeColors.ClinicalTeal,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                if (hasPinnedDanger) {
                                    "${strings.addToCabinetAnywayAction} (${activeProfile.name})"
                                } else {
                                    "${strings.addToCabinetAction} (${activeProfile.name})"
                                },
                            )
                        }
                    }
                }
            }

            // Unified Official References Accordion Ledger
            AnimatedVisibility(
                visible = state.citations.isNotEmpty(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${strings.officialReferences} (${state.citations.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = PillScopeColors.CardSurface,
                        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            state.citations.forEachIndexed { idx, citation ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = PillScopeColors.SlateBorder.copy(alpha = 0.7f),
                                    )
                                }
                                ExpandableCitationLedgerRow(
                                    citation = citation,
                                    reducedMotion = reducedMotion,
                                )
                            }
                        }
                    }
                }
            }

            // Unified Recent Scans Archival Ledger
            if (cachedHistory.isNotEmpty()) {
                HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.7f))
                val visibleHistory = if (showAllHistory) cachedHistory else cachedHistory.take(10)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = PillScopeColors.ClinicalTeal,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "${strings.recentScans} (${cachedHistory.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = PillScopeColors.InkPrimary,
                        )
                    }
                    if (cachedHistory.size > 10) {
                        TextButton(onClick = { showAllHistory = !showAllHistory }) {
                            Text(
                                text = if (showAllHistory) strings.showFewer else strings.showAll,
                                color = PillScopeColors.ClinicalTeal,
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CardSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        visibleHistory.forEachIndexed { idx, summary ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = PillScopeColors.SlateBorder.copy(alpha = 0.65f),
                                )
                            }
                            CachedScanLedgerRow(
                                summary = summary,
                                isCurrentlyOpen = summary.scanId == state.scanId,
                                onClick = {
                                    viewModel.openCachedReport(summary.scanId)
                                    coroutineScope.launch {
                                        scrollState.animateScrollTo(0)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAddToCabinetDialog && onAddToCabinet != null) {
        val canSave = draftCabinetName.isNotBlank() || state.resolvedSalts.isNotEmpty()
        AlertDialog(
            onDismissRequest = { showAddToCabinetDialog = false },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    if (hasPinnedDanger) {
                        "Critical safety warning"
                    } else {
                        "Add to ${activeProfile.name}'s cabinet"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasPinnedDanger) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PillScopeColors.CriticalCrimsonSurface,
                            border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                text = "This medicine triggered a critical safety alert for ${activeProfile.name} (major interaction, allergy, duplicate ingredient, or FDA recall). Consult a doctor or pharmacist before taking it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PillScopeColors.CriticalCrimsonDark,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draftCabinetName,
                        onValueChange = { draftCabinetName = it },
                        label = { Text("Medication name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Active ingredients: ${state.resolvedSalts.joinToString(" + ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                    OutlinedTextField(
                        value = draftDosageSchedule,
                        onValueChange = { draftDosageSchedule = it },
                        label = { Text("Dosage schedule (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DosagePresetChips(
                        currentValue = draftDosageSchedule,
                        onValueChange = { draftDosageSchedule = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalName = draftCabinetName.trim().ifBlank {
                            state.submittedQuery.ifBlank { state.resolvedSalts.joinToString(" + ") }
                        }
                        showAddToCabinetDialog = false
                        onAddToCabinet(
                            finalName,
                            state.resolvedSalts,
                            draftDosageSchedule.trim().takeIf { it.isNotEmpty() },
                        )
                        viewModel.markScanSaved(
                            scanKey = scanKey,
                            displayName = finalName,
                            resolvedSalts = state.resolvedSalts,
                        )
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (hasPinnedDanger) {
                            PillScopeColors.CriticalCrimson
                        } else {
                            PillScopeColors.ClinicalTeal
                        },
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(if (hasPinnedDanger) "Save anyway" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddToCabinetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    selectedTrigger?.let { trigger ->
        ModalBottomSheet(
            onDismissRequest = { selectedTrigger = null },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            LifestyleTriggerDetailContent(
                trigger = trigger,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun PinnedCriticalSafetyBanner(
    state: ScanAnalysisState,
    modifier: Modifier = Modifier,
) {
    val majorDeterministic = remember(state.deterministicAlerts) {
        state.deterministicAlerts.filter { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
    }
    val majorMlFallback = remember(state.interactions, majorDeterministic) {
        if (majorDeterministic.isEmpty()) {
            state.interactions.filter { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
        } else {
            emptyList()
        }
    }
    val hasAnyLine = state.allergyAlerts.isNotEmpty() ||
        state.duplicateSalts.isNotEmpty() ||
        majorDeterministic.isNotEmpty() ||
        majorMlFallback.isNotEmpty() ||
        state.activeRecalls.isNotEmpty()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = PillScopeColors.CriticalBannerContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.GppBad,
                    contentDescription = "Locked safety alert",
                    tint = PillScopeColors.OnCriticalBanner,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = LocalAppStrings.current.criticalSafetyAlert,
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            HorizontalDivider(color = PillScopeColors.OnCriticalBanner.copy(alpha = 0.22f))

            state.allergyAlerts.forEach { allergy ->
                Text(
                    text = "Allergy match (${allergy.allergen}): ${allergy.reason}",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.duplicateSalts.forEach { dup ->
                Text(
                    text = "Duplicate ingredient: ${dup.canonicalSalt} is already active in ${dup.existingDisplayName}.",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            majorDeterministic.forEach { alert ->
                Text(
                    text = "Major interaction: ${alert.saltA} + ${alert.saltB}. ${alert.management}",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            majorMlFallback.forEach { interaction ->
                Text(
                    text = "Major interaction risk: ${interaction.saltA} + ${interaction.saltB}. ${interaction.management}",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.activeRecalls.forEach { recall ->
                Text(
                    text = "${recall.alertLabel} (${recall.classification}): ${recall.matchedMedicationName}. ${recall.reasonForRecall}",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.activeRecalls.isNotEmpty()) {
                Text(
                    text = "Matched by active ingredient against US FDA recalls. Verify manufacturer and lot number on your package.",
                    color = PillScopeColors.OnCriticalBanner.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (!hasAnyLine) {
                Text(
                    text = "High-risk interaction detected. Review the details below and check with a doctor or pharmacist before taking.",
                    color = PillScopeColors.OnCriticalBanner,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RiskGaugeHeroSurface(
    state: ScanAnalysisState,
    isStreaming: Boolean,
    reducedMotion: Boolean,
) {
    val palette = LocalPillScopePalette.current
    val isUnrecognized = state.stage.ordinal >= AnalysisStage.DETERMINISTIC_READY.ordinal &&
        state.stage != AnalysisStage.ERROR &&
        state.resolvedSalts.isEmpty()

    val targetFraction = when {
        state.stage == AnalysisStage.IDLE -> 0.04f
        state.stage == AnalysisStage.CONNECTING -> 0.28f
        state.stage == AnalysisStage.ERROR -> 0.50f
        isUnrecognized -> 0.50f
        state.safetyBannerLocked || state.overallRisk == RiskLevel.MAJOR -> 0.96f
        state.overallRisk == RiskLevel.MODERATE -> 0.64f
        state.overallRisk == RiskLevel.MINOR -> 0.36f
        else -> 0.14f
    }
    val needleFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (reducedMotion) tween(0) else PillScopeMotion.dialSpring(),
        label = "riskNeedle",
    )
    val riskAccent by animateColorAsState(
        targetValue = when {
            state.stage == AnalysisStage.IDLE -> palette.inkSecondary
            state.stage == AnalysisStage.CONNECTING -> palette.clinicalTeal
            state.stage == AnalysisStage.ERROR -> palette.criticalCrimson
            isUnrecognized -> palette.moderateAmber
            state.overallRisk == RiskLevel.MAJOR -> palette.criticalCrimson
            state.overallRisk == RiskLevel.MODERATE -> palette.moderateAmber
            state.overallRisk == RiskLevel.MINOR -> palette.minorBlue
            else -> palette.safeEmerald
        },
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "riskAccent",
    )
    val riskHeadlineColor by animateColorAsState(
        targetValue = when {
            state.stage == AnalysisStage.IDLE -> palette.inkSecondary
            state.stage == AnalysisStage.CONNECTING -> palette.clinicalTeal
            state.stage == AnalysisStage.ERROR -> palette.criticalCrimson
            isUnrecognized -> palette.moderateAmberDark
            state.overallRisk == RiskLevel.MAJOR -> palette.criticalCrimson
            state.overallRisk == RiskLevel.MODERATE -> palette.moderateAmberDark
            state.overallRisk == RiskLevel.MINOR -> palette.minorBlue
            else -> palette.safeEmerald
        },
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "riskHeadlineColor",
    )
    val strings = LocalAppStrings.current
    val riskHeadline = when {
        state.stage == AnalysisStage.IDLE -> strings.readyToScan
        state.stage == AnalysisStage.CONNECTING -> strings.checkingSafety
        state.stage == AnalysisStage.ERROR -> strings.checkIncomplete
        isUnrecognized -> strings.medicineNotRecognized
        state.overallRisk == RiskLevel.MAJOR -> strings.majorRisk
        state.overallRisk == RiskLevel.MODERATE -> strings.moderateCaution
        state.overallRisk == RiskLevel.MINOR -> strings.minorRisk
        else -> strings.noInteractionsFound
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(PillScopeMotion.settleSpring()),
        shape = RoundedCornerShape(20.dp),
        color = PillScopeColors.CardSurface,
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = state.submittedQuery.ifBlank { strings.noActiveSafetyCheck },
                            style = MaterialTheme.typography.titleLarge,
                            color = PillScopeColors.InkPrimary,
                        )
                        if (state.resolvedSalts.isNotEmpty()) {
                            Text(
                                text = state.resolvedSalts.joinToString(" + "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.ClinicalTeal,
                                fontWeight = FontWeight.Medium,
                            )
                        } else if (isUnrecognized) {
                            Text(
                                text = "No active ingredients could be verified. Try searching by generic name.",
                                style = MaterialTheme.typography.labelMedium,
                                color = PillScopeColors.ModerateAmberDark,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!state.persisted && state.stage != AnalysisStage.IDLE) {
                            Surface(
                                shape = CircleShape,
                                color = PillScopeColors.ModerateAmberSurface,
                            ) {
                                Text(
                                    text = strings.quickCheck,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PillScopeColors.ModerateAmberDark,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                        if (isStreaming) {
                            LoadingIndicator(
                                modifier = Modifier.size(24.dp),
                                color = riskAccent,
                            )
                        }
                    }
                }

                // Calibrated precision apothecary dial stacked cleanly ABOVE the serif headline
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(width = 248.dp, height = 136.dp),
                    ) {
                        val outerPad = 14.dp.toPx()
                        val strokeWidth = 12.dp.toPx()
                        val diameter = size.width - outerPad * 2 - strokeWidth
                        val arcTopLeft = Offset(outerPad + strokeWidth / 2f, outerPad + strokeWidth / 2f)
                        val arcSize = Size(diameter, diameter)
                        val center = Offset(size.width / 2f, arcTopLeft.y + diameter / 2f)

                        // Subtle precision graduation ticks along the outer arc
                        val tickInnerR = diameter / 2f + strokeWidth * 0.72f
                        val tickOuterMajorR = tickInnerR + 6.dp.toPx()
                        val tickOuterMinorR = tickInnerR + 3.5.dp.toPx()
                        val totalTicks = 24
                        for (i in 0..totalTicks) {
                            val frac = i.toFloat() / totalTicks.toFloat()
                            val angleRad = PI * (1.0 + frac.toDouble())
                            val isMajorTick = i % 6 == 0
                            val rOut = if (isMajorTick) tickOuterMajorR else tickOuterMinorR
                            val tickColor = if (frac <= needleFraction + 0.02f && state.stage != AnalysisStage.IDLE) {
                                riskAccent.copy(alpha = if (isMajorTick) 0.78f else 0.42f)
                            } else {
                                palette.slateBorder.copy(alpha = if (isMajorTick) 0.9f else 0.55f)
                            }
                            drawLine(
                                color = tickColor,
                                start = Offset(
                                    center.x + (cos(angleRad) * tickInnerR).toFloat(),
                                    center.y + (sin(angleRad) * tickInnerR).toFloat(),
                                ),
                                end = Offset(
                                    center.x + (cos(angleRad) * rOut).toFloat(),
                                    center.y + (sin(angleRad) * rOut).toFloat(),
                                ),
                                strokeWidth = if (isMajorTick) 1.6.dp.toPx() else 1.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }

                        // Quiet segmented matte scale with crisp gaps so round caps never overlap
                        val segments = listOf(
                            Triple(180f, 38f, palette.safeEmerald.copy(alpha = 0.24f)),
                            Triple(226f, 38f, palette.minorBlue.copy(alpha = 0.24f)),
                            Triple(272f, 38f, palette.moderateAmber.copy(alpha = 0.26f)),
                            Triple(318f, 42f, palette.criticalCrimson.copy(alpha = 0.28f)),
                        )
                        for ((startAngle, sweep, color) in segments) {
                            drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                        }

                        // Active swept risk arc (omitted while idle so the resting dial stays uncluttered)
                        if (state.stage != AnalysisStage.IDLE) {
                            val activeSweep = (needleFraction * 180f).coerceIn(6f, 180f)
                            drawArc(
                                color = riskAccent,
                                startAngle = 180f,
                                sweepAngle = activeSweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                        }

                        // Fine needle & anodized pivot
                        val angleRad = PI * (1.0 + needleFraction.toDouble())
                        val needleLen = (diameter / 2f) - strokeWidth * 0.85f
                        val tip = Offset(
                            x = center.x + (cos(angleRad) * needleLen).toFloat(),
                            y = center.y + (sin(angleRad) * needleLen).toFloat(),
                        )
                        drawLine(
                            color = palette.inkPrimary,
                            start = center,
                            end = tip,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = palette.inkPrimary,
                            radius = 6.5.dp.toPx(),
                            center = center,
                        )
                        drawCircle(
                            color = palette.cardSurface,
                            radius = 2.8.dp.toPx(),
                            center = center,
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AnimatedContent(
                            targetState = riskHeadline,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                            label = "riskVerdictText",
                        ) { headline ->
                            Text(
                                text = headline,
                                style = MaterialTheme.typography.headlineSmall,
                                color = riskHeadlineColor,
                            )
                        }
                        if (state.fromOfflineCache) {
                            Text(
                                text = strings.savedOfflineReport,
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.InkSecondary,
                            )
                        }
                    }
                }
            }

            // Integrated SSE stage verification footer inside the hero instrument
            if (state.stage != AnalysisStage.IDLE) {
                HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.7f))
                IntegratedPipelineFooter(
                    stage = state.stage,
                    isStreaming = isStreaming,
                    safetyLocked = state.safetyBannerLocked,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }
}

@Composable
private fun IntegratedPipelineFooter(
    stage: AnalysisStage,
    isStreaming: Boolean,
    safetyLocked: Boolean,
    reducedMotion: Boolean,
) {
    val strings = LocalAppStrings.current
    val saltResolvedDone = stage.ordinal >= AnalysisStage.DETERMINISTIC_READY.ordinal &&
        stage != AnalysisStage.ERROR
    val mlScoredDone = stage.ordinal >= AnalysisStage.ML_READY.ordinal &&
        stage != AnalysisStage.ERROR
    val monographsDone = stage.ordinal >= AnalysisStage.CITATIONS_READY.ordinal &&
        stage != AnalysisStage.ERROR

    val stepProgress by animateFloatAsState(
        targetValue = when (stage) {
            AnalysisStage.IDLE -> 0f
            AnalysisStage.CONNECTING -> 0.16f
            AnalysisStage.DETERMINISTIC_READY -> 0.40f
            AnalysisStage.ML_READY -> 0.70f
            AnalysisStage.CITATIONS_READY -> 0.88f
            AnalysisStage.SUMMARY_READY, AnalysisStage.COMPLETE -> 1f
            AnalysisStage.ERROR -> 1f
        },
        animationSpec = if (reducedMotion) tween(0) else PillScopeMotion.settleSpring(),
        label = "sseProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PillScopeColors.SlateSurface.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PipelineStepItem(label = strings.stepIngredients, done = saltResolvedDone)
            PipelineStepItem(label = strings.stepInteractions, done = mlScoredDone)
            PipelineStepItem(label = strings.stepFdaSources, done = monographsDone)
        }

        AnimatedVisibility(
            visible = isStreaming,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(PillScopeColors.SurfaceTint),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(stepProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            if (safetyLocked) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTeal,
                        ),
                )
            }
        }
    }
}

@Composable
private fun PipelineStepItem(
    label: String,
    done: Boolean,
) {
    val fg by animateColorAsState(
        targetValue = if (done) PillScopeColors.SafeEmeraldDark else PillScopeColors.InkSecondary,
        animationSpec = tween(PillScopeMotion.FAST_MS),
        label = "stepFg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) PillScopeColors.SafeEmerald else PillScopeColors.InkSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LifestyleTriggerChipRow(
    triggers: List<LifestyleTriggerDto>,
    onSelectTrigger: (LifestyleTriggerDto) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        items(triggers, key = { "${it.type}:${it.sourceSection}" }) { trigger ->
            val (bg, fg, icon) = lifestyleTriggerVisuals(trigger)
            Surface(
                onClick = { onSelectTrigger(trigger) },
                modifier = Modifier.heightIn(min = 42.dp),
                shape = RoundedCornerShape(12.dp),
                color = bg,
                border = BorderStroke(1.dp, fg.copy(alpha = 0.28f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = formatLifestyleTriggerLabel(trigger.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun LifestyleTriggerDetailContent(
    trigger: LifestyleTriggerDto,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, icon) = lifestyleTriggerVisuals(trigger)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = bg) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp),
                )
            }
            Column {
                Text(
                    text = formatLifestyleTriggerLabel(trigger.type),
                    style = MaterialTheme.typography.titleLarge,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "${formatSectionLabel(trigger.sourceSection)} (${trigger.severity.lowercase().replaceFirstChar { it.uppercase() }} priority)",
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = PillScopeColors.SlateSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Text(
                text = trigger.shortNote,
                style = MaterialTheme.typography.bodyLarge,
                color = PillScopeColors.InkPrimary,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun lifestyleTriggerVisuals(trigger: LifestyleTriggerDto): Triple<Color, Color, ImageVector> {
    val icon = when (trigger.type.uppercase()) {
        "ALCOHOL" -> Icons.Default.LocalBar
        "GRAPEFRUIT" -> Icons.Default.Eco
        "DAIRY" -> Icons.Default.LocalDrink
        "CAFFEINE" -> Icons.Default.Coffee
        "EMPTY_STOMACH" -> Icons.Default.Schedule
        "WITH_FOOD" -> Icons.Default.LocalDining
        "DROWSINESS" -> Icons.Default.Bedtime
        "SUN_SENSITIVITY" -> Icons.Default.WbSunny
        else -> Icons.Default.ReportProblem
    }
    return when (trigger.severity.uppercase()) {
        "HIGH" -> Triple(PillScopeColors.CriticalCrimsonSurface, PillScopeColors.CriticalCrimsonDark, icon)
        "MODERATE" -> Triple(PillScopeColors.ModerateAmberSurface, PillScopeColors.ModerateAmberDark, icon)
        else -> Triple(PillScopeColors.ClinicalTealContainer, PillScopeColors.ClinicalTealDeep, icon)
    }
}

private fun formatLifestyleTriggerLabel(rawType: String): String = when (rawType.uppercase()) {
    "ALCOHOL" -> "Alcohol"
    "GRAPEFRUIT" -> "Grapefruit"
    "DAIRY" -> "Dairy"
    "CAFFEINE" -> "Caffeine"
    "EMPTY_STOMACH" -> "Empty Stomach"
    "WITH_FOOD" -> "With Food"
    "DROWSINESS" -> "Drowsiness"
    "SUN_SENSITIVITY" -> "Sun Sensitivity"
    else -> rawType.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun formatSectionLabel(rawSection: String): String = when (rawSection.lowercase()) {
    "warnings" -> "Warnings and precautions"
    "drug_interactions" -> "Drug interactions"
    "dosage_and_administration" -> "Dosage and administration"
    "precautions" -> "Precautions"
    else -> rawSection.replace("_", " ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun InteractionRiskRailCard(
    interaction: InteractionItemDto,
    reducedMotion: Boolean,
) {
    val risk = RiskLevel.fromApi(interaction.severity)
    val accent = when (risk) {
        RiskLevel.MAJOR -> PillScopeColors.CriticalCrimson
        RiskLevel.MODERATE -> PillScopeColors.ModerateAmber
        RiskLevel.MINOR -> PillScopeColors.MinorBlue
        RiskLevel.NONE -> PillScopeColors.SafeEmerald
    }
    val (pillBg, pillFg) = severityPillColors(risk)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = PillScopeColors.CardSurface,
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${interaction.saltA} + ${interaction.saltB}",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    Surface(shape = CircleShape, color = pillBg) {
                        Text(
                            text = interaction.severity.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = pillFg,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = formatInteractionSourceLabel(interaction.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
                interaction.mlMajorProbability?.let { prob ->
                    var revealed by remember(interaction.saltA, interaction.saltB, prob) {
                        mutableStateOf(reducedMotion)
                    }
                    LaunchedEffect(interaction.saltA, interaction.saltB, prob) {
                        revealed = true
                    }
                    val fillFraction by animateFloatAsState(
                        targetValue = if (revealed) prob.toFloat().coerceIn(0f, 1f) else 0f,
                        animationSpec = if (reducedMotion) {
                            tween(0)
                        } else {
                            tween(durationMillis = 520, easing = FastOutSlowInEasing)
                        },
                        label = "mlProbBar",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Estimated major interaction likelihood: ${(prob * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = PillScopeColors.InkSecondary,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(PillScopeColors.SurfaceTint),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fillFraction)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(accent),
                            )
                        }
                    }
                }
                Text(
                    text = interaction.mechanism,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                )
                HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.6f))
                Text(
                    text = "What to do: ${interaction.management}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ClinicalSummaryCard(
    headline: String,
    explanation: String,
    nextSteps: List<String>,
    abstained: Boolean,
    citationVerified: Boolean,
    disclaimer: String,
    languageLabel: String = "",
    isSpeaking: Boolean = false,
    onToggleSpeak: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val badgeColor = if (abstained) PillScopeColors.ModerateAmberDark else PillScopeColors.SafeEmeraldDark
    val badgeBg = if (abstained) PillScopeColors.ModerateAmberSurface else PillScopeColors.SafeEmeraldSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = PillScopeColors.CardSurface,
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = badgeBg,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (abstained) Icons.Default.Warning else Icons.Default.Verified,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = when {
                                abstained -> strings.limitedLabelEvidence
                                citationVerified -> strings.verifiedFdaLabels
                                else -> strings.safetySummary
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeColor,
                        )
                    }
                }
                if (onToggleSpeak != null) {
                    Spacer(Modifier.size(8.dp))
                    Surface(
                        onClick = onToggleSpeak,
                        modifier = Modifier.heightIn(min = 36.dp),
                        shape = CircleShape,
                        color = if (isSpeaking) {
                            PillScopeColors.CriticalCrimsonSurface
                        } else {
                            PillScopeColors.ClinicalTealContainer
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isSpeaking) strings.stopSpeaking else strings.listen,
                                tint = if (isSpeaking) {
                                    PillScopeColors.CriticalCrimsonDark
                                } else {
                                    PillScopeColors.ClinicalTealDeep
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (isSpeaking) {
                                    strings.stopSpeaking
                                } else if (languageLabel.isNotBlank()) {
                                    "${strings.listen} ($languageLabel)"
                                } else {
                                    strings.listen
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSpeaking) {
                                    PillScopeColors.CriticalCrimsonDark
                                } else {
                                    PillScopeColors.ClinicalTealDeep
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                color = PillScopeColors.InkPrimary,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = PillScopeColors.InkPrimary,
            )
            if (nextSteps.isNotEmpty()) {
                HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.7f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nextSteps.forEach { step ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(PillScopeColors.ClinicalTeal),
                            )
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.InkPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (disclaimer.isNotBlank()) {
                Text(
                    text = disclaimer,
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}

@Composable
private fun ExpandableCitationLedgerRow(
    citation: MonographCitationDto,
    reducedMotion: Boolean,
) {
    var expanded by remember(citation.chunkId) { mutableStateOf(false) }
    val openUri = rememberSafeUriOpener()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reducedMotion) tween(0) else PillScopeMotion.settleSpring(),
        label = "citationChevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(PillScopeMotion.settleSpring())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${citation.canonicalSalt.replaceFirstChar { it.uppercase() }} (${formatSectionLabel(citation.sectionTitle)})",
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.ClinicalTeal,
                )
                val relevanceLabel = when {
                    citation.rerankScore >= 0.75 -> "High relevance"
                    citation.rerankScore >= 0.45 -> "Moderate relevance"
                    else -> "Supporting reference"
                }
                Text(
                    text = "Official FDA drug label, $relevanceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse reference" else "Expand reference",
                tint = PillScopeColors.InkSecondary,
                modifier = Modifier.rotate(chevronRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PillScopeColors.SlateSurface,
                ) {
                    Text(
                        text = citation.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkPrimary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                val isWebUrl = citation.sourceUrl.startsWith("https://") ||
                    citation.sourceUrl.startsWith("http://")
                if (isWebUrl) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { openUri(citation.sourceUrl) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = formatMonographLinkLabel(citation.sourceUrl),
                            style = MaterialTheme.typography.labelLarge.copy(
                                textDecoration = TextDecoration.Underline,
                            ),
                            color = PillScopeColors.MinorBlue,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open official source",
                            tint = PillScopeColors.MinorBlue,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CachedScanLedgerRow(
    summary: CachedScanSummary,
    isCurrentlyOpen: Boolean,
    onClick: () -> Unit,
) {
    val (pillBg, pillFg) = severityPillColors(summary.overallRisk)
    val railColor = when (summary.overallRisk) {
        RiskLevel.MAJOR -> PillScopeColors.CriticalCrimson
        RiskLevel.MODERATE -> PillScopeColors.ModerateAmber
        RiskLevel.MINOR -> PillScopeColors.MinorBlue
        RiskLevel.NONE -> PillScopeColors.SafeEmerald
    }
    val rowBg by animateColorAsState(
        targetValue = if (isCurrentlyOpen) {
            PillScopeColors.ClinicalTealContainer.copy(alpha = 0.42f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(PillScopeMotion.FAST_MS),
        label = "historyRowBg",
    )
    val formattedDate = remember(summary.createdAtEpochMs) {
        if (summary.createdAtEpochMs <= 0L) {
            ""
        } else {
            runCatching {
                DateTimeFormatter.ofPattern("MMM d, HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(summary.createdAtEpochMs))
            }.getOrDefault("")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(rowBg)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isCurrentlyOpen) PillScopeColors.ClinicalTeal else railColor.copy(alpha = 0.7f)),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = summary.submittedQuery.ifBlank { summary.resolvedSalts.joinToString(" + ") },
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.InkPrimary,
                )
                if (summary.resolvedSalts.isNotEmpty()) {
                    Text(
                        text = summary.resolvedSalts.joinToString(" + "),
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                }
                if (formattedDate.isNotEmpty()) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }
            Surface(shape = CircleShape, color = pillBg) {
                Text(
                    text = if (summary.overallRisk == RiskLevel.NONE) {
                        LocalAppStrings.current.safeBadge
                    } else {
                        summary.overallRisk.name.lowercase().replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = pillFg,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
