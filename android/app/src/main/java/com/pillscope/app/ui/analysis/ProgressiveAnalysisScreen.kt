package com.pillscope.app.ui.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
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
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
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
    onAddToCabinet: ((displayName: String, canonicalSalts: List<String>, dosageSchedule: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cachedHistory by viewModel.cachedHistory.collectAsStateWithLifecycle()
    val canRetry by viewModel.canRetry.collectAsStateWithLifecycle()
    val savedScanKeys by viewModel.savedScanKeys.collectAsStateWithLifecycle()
    var selectedTrigger by remember { mutableStateOf<LifestyleTriggerDto?>(null) }
    var showAddToCabinetDialog by remember { mutableStateOf(false) }
    var draftCabinetName by remember { mutableStateOf("") }
    var draftDosageSchedule by remember { mutableStateOf("") }

    val scanKey = state.scanId ?: "${state.submittedQuery}:${state.resolvedSalts.joinToString(",")}"
    val addedForScanKey = scanKey.isNotBlank() && scanKey in savedScanKeys

    val hasPinnedDanger = state.safetyBannerLocked ||
        state.allergyAlerts.isNotEmpty() ||
        state.duplicateSalts.isNotEmpty() ||
        state.activeRecalls.isNotEmpty()

    val isStreaming = state.stage != AnalysisStage.COMPLETE &&
        state.stage != AnalysisStage.IDLE &&
        state.stage != AnalysisStage.ERROR

    Column(modifier = modifier.fillMaxSize()) {
        if (hasPinnedDanger) {
            PinnedCriticalSafetyBanner(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RiskGaugeHeroCard(
                state = state,
                isStreaming = isStreaming,
            )

            if (state.stage == AnalysisStage.COMPLETE &&
                state.resolvedSalts.isNotEmpty() &&
                onAddToCabinet != null
            ) {
                if (addedForScanKey) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = PillScopeColors.SafeEmeraldSurface,
                        border = BorderStroke(1.dp, PillScopeColors.SafeEmerald.copy(alpha = 0.4f)),
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
                                text = "Saved to this profile's medication cabinet",
                                style = MaterialTheme.typography.labelLarge,
                                color = PillScopeColors.SafeEmeraldDark,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            draftCabinetName = state.submittedQuery.ifBlank {
                                state.resolvedSalts.joinToString(" + ")
                            }
                            draftDosageSchedule = ""
                            showAddToCabinetDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = if (hasPinnedDanger) {
                            ButtonDefaults.buttonColors(
                                containerColor = PillScopeColors.CriticalCrimson,
                                contentColor = Color.White,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (hasPinnedDanger) {
                                "Add to Cabinet anyway (review alert)"
                            } else {
                                "Add to Cabinet"
                            },
                        )
                    }
                }
            }

            LiveSsePipelineStrip(
                stage = state.stage,
                isStreaming = isStreaming,
                safetyLocked = state.safetyBannerLocked,
            )

            if (state.lifestyleTriggers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Food, alcohol, and lifestyle guidance",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    LifestyleTriggerChipRow(
                        triggers = state.lifestyleTriggers,
                        onSelectTrigger = { selectedTrigger = it },
                    )
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.ModerateAmberSurface,
                    border = BorderStroke(1.dp, PillScopeColors.ModerateAmber),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Analysis error: $err",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.ModerateAmberDark,
                        )
                        if (canRetry) {
                            OutlinedButton(
                                onClick = viewModel::retryLastAnalysis,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Retry safety analysis")
                            }
                        }
                    }
                }
            }

            if (state.interactions.isNotEmpty()) {
                Text(
                    text = "Pairwise interaction risks (${state.interactions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
                state.interactions.forEach { interaction ->
                    InteractionRiskCard(interaction = interaction)
                }
            }

            state.clinicalSummary?.let { summary ->
                ClinicalSummaryCard(
                    headline = summary.headline,
                    explanation = summary.patientExplanation,
                    nextSteps = summary.actionableNextSteps,
                    abstained = summary.abstained,
                    citationVerified = state.citationVerified,
                    disclaimer = summary.disclaimer,
                )
            }

            if (state.citations.isNotEmpty()) {
                Text(
                    text = "Cited openFDA and DailyMed monographs (${state.citations.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
                state.citations.forEach { citation ->
                    ExpandableCitationCard(citation = citation)
                }
            }

            if (cachedHistory.isNotEmpty()) {
                HorizontalDivider(color = PillScopeColors.SlateBorder)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = PillScopeColors.ClinicalTeal,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Recent scans (${cachedHistory.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cachedHistory.take(10).forEach { summary ->
                        CachedScanHistoryCard(
                            summary = summary,
                            isCurrentlyOpen = summary.scanId == state.scanId,
                            onClick = { viewModel.openCachedReport(summary.scanId) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAddToCabinetDialog && onAddToCabinet != null) {
        AlertDialog(
            onDismissRequest = { showAddToCabinetDialog = false },
            title = {
                Text(if (hasPinnedDanger) "Critical safety warning" else "Add to Cabinet")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasPinnedDanger) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PillScopeColors.CriticalCrimsonSurface,
                        ) {
                            Text(
                                text = "This medication triggered a locked safety alert (major interaction, allergy, duplicate salt, or FDA recall). Consult a doctor or pharmacist before taking it.",
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Active salts: ${state.resolvedSalts.joinToString(" + ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                    OutlinedTextField(
                        value = draftDosageSchedule,
                        onValueChange = { draftDosageSchedule = it },
                        label = { Text("Dosage schedule (optional, e.g. 1 tab twice daily)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAddToCabinetDialog = false
                        onAddToCabinet(
                            draftCabinetName.trim().ifBlank {
                                state.submittedQuery.ifBlank { state.resolvedSalts.joinToString(" + ") }
                            },
                            state.resolvedSalts,
                            draftDosageSchedule.trim().takeIf { it.isNotEmpty() },
                        )
                        viewModel.markScanSaved(scanKey)
                    },
                    enabled = draftCabinetName.isNotBlank() || state.resolvedSalts.isNotEmpty(),
                ) {
                    Text(
                        text = if (hasPinnedDanger) "Save anyway" else "Save",
                        color = if (hasPinnedDanger) PillScopeColors.CriticalCrimson else PillScopeColors.ClinicalTeal,
                    )
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
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            LifestyleTriggerDetailContent(
                trigger = trigger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun PinnedCriticalSafetyBanner(
    state: ScanAnalysisState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = PillScopeColors.CriticalCrimson,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.GppBad,
                    contentDescription = "Locked safety alert",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Critical safety alert locked",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            state.allergyAlerts.forEach { allergy ->
                Text(
                    text = "Allergy match (${allergy.allergen}): ${allergy.reason}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.duplicateSalts.forEach { dup ->
                Text(
                    text = "Duplicate active salt: ${dup.canonicalSalt} is already active in ${dup.existingDisplayName}.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.deterministicAlerts
                .filter { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
                .forEach { alert ->
                    Text(
                        text = "Major interaction: ${alert.saltA} + ${alert.saltB}. ${alert.management}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            state.activeRecalls.forEach { recall ->
                Text(
                    text = "${recall.alertLabel} (${recall.classification}): ${recall.matchedMedicationName} — ${recall.reasonForRecall}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.activeRecalls.isNotEmpty()) {
                Text(
                    text = "US FDA enforcement recall matched by active salt — check your pack manufacturer & lot number with a pharmacist.",
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RiskGaugeHeroCard(
    state: ScanAnalysisState,
    isStreaming: Boolean,
) {
    val palette = LocalPillScopePalette.current
    val isUnrecognized = state.stage.ordinal >= AnalysisStage.DETERMINISTIC_READY.ordinal &&
        state.stage != AnalysisStage.ERROR &&
        state.resolvedSalts.isEmpty()

    val targetFraction = when {
        state.stage == AnalysisStage.IDLE -> 0.05f
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
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
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
        label = "riskAccent",
    )
    val riskHeadline = when {
        state.stage == AnalysisStage.IDLE -> "Ready to scan"
        state.stage == AnalysisStage.CONNECTING -> "Analyzing safety…"
        state.stage == AnalysisStage.ERROR -> "Analysis failed"
        isUnrecognized -> "Medicine not recognized"
        state.overallRisk == RiskLevel.MAJOR -> "Major risk"
        state.overallRisk == RiskLevel.MODERATE -> "Moderate caution"
        state.overallRisk == RiskLevel.MINOR -> "Minor risk"
        else -> "No interactions found"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.submittedQuery.ifBlank { "No active analysis" },
                        style = MaterialTheme.typography.titleLarge,
                        color = PillScopeColors.InkPrimary,
                    )
                    if (state.resolvedSalts.isNotEmpty()) {
                        Text(
                            text = state.resolvedSalts.joinToString(" + "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.ClinicalTeal,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else if (isUnrecognized) {
                        Text(
                            text = "No active salts could be verified — retry with generic name",
                            style = MaterialTheme.typography.labelMedium,
                            color = PillScopeColors.ModerateAmberDark,
                        )
                    }
                }
                if (!state.persisted && state.stage != AnalysisStage.IDLE) {
                    Surface(
                        shape = CircleShape,
                        color = PillScopeColors.ModerateAmberSurface,
                    ) {
                        Text(
                            text = "Sandbox preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = PillScopeColors.ModerateAmberDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                } else if (isStreaming) {
                    LoadingIndicator(
                        modifier = Modifier.size(32.dp),
                        color = riskAccent,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(width = 236.dp, height = 126.dp),
                ) {
                    val strokeWidth = 16.dp.toPx()
                    val diameter = size.width - strokeWidth
                    val arcTopLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                    val arcSize = Size(diameter, diameter)

                    val segments = listOf(
                        Triple(180f, 43f, palette.safeEmerald.copy(alpha = 0.25f)),
                        Triple(225f, 43f, palette.minorBlue.copy(alpha = 0.25f)),
                        Triple(270f, 43f, palette.moderateAmber.copy(alpha = 0.28f)),
                        Triple(315f, 45f, palette.criticalCrimson.copy(alpha = 0.30f)),
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

                    val center = Offset(size.width / 2f, strokeWidth / 2f + diameter / 2f)
                    val angleRad = PI * (1.0 + needleFraction.toDouble())
                    val needleLen = (diameter / 2f) - strokeWidth * 0.9f
                    val tip = Offset(
                        x = center.x + (cos(angleRad) * needleLen).toFloat(),
                        y = center.y + (sin(angleRad) * needleLen).toFloat(),
                    )
                    drawLine(
                        color = palette.inkPrimary,
                        start = center,
                        end = tip,
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = palette.inkPrimary,
                        radius = 7.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = palette.cardSurface,
                        radius = 3.dp.toPx(),
                        center = center,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 2.dp),
                ) {
                    Text(
                        text = riskHeadline,
                        style = MaterialTheme.typography.titleLarge,
                        color = riskAccent,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.fromOfflineCache) {
                        Text(
                            text = "Offline cached report",
                            style = MaterialTheme.typography.labelSmall,
                            color = PillScopeColors.InkSecondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveSsePipelineStrip(
    stage: AnalysisStage,
    isStreaming: Boolean,
    safetyLocked: Boolean,
) {
    val saltResolvedDone = stage.ordinal >= AnalysisStage.DETERMINISTIC_READY.ordinal &&
        stage != AnalysisStage.ERROR
    val mlScoredDone = stage.ordinal >= AnalysisStage.ML_READY.ordinal &&
        stage != AnalysisStage.ERROR
    val monographsDone = stage.ordinal >= AnalysisStage.CITATIONS_READY.ordinal &&
        stage != AnalysisStage.ERROR

    val stepProgress by animateFloatAsState(
        targetValue = when (stage) {
            AnalysisStage.IDLE -> 0f
            AnalysisStage.CONNECTING -> 0.15f
            AnalysisStage.DETERMINISTIC_READY -> 0.38f
            AnalysisStage.ML_READY -> 0.68f
            AnalysisStage.CITATIONS_READY -> 0.88f
            AnalysisStage.SUMMARY_READY, AnalysisStage.COMPLETE -> 1f
            AnalysisStage.ERROR -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sseProgress",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PipelineStepPill(label = "Salt Resolved", active = saltResolvedDone)
                PipelineStepPill(label = "ML Scored", active = mlScoredDone)
                PipelineStepPill(label = "Monographs Cited", active = monographsDone)
            }

            if (isStreaming) {
                LinearWavyProgressIndicator(
                    progress = { stepProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = if (safetyLocked) {
                        PillScopeColors.CriticalCrimson
                    } else {
                        PillScopeColors.ClinicalTeal
                    },
                    trackColor = PillScopeColors.SurfaceTint,
                )
            }
        }
    }
}

@Composable
private fun PipelineStepPill(
    label: String,
    active: Boolean,
) {
    val bg by animateColorAsState(
        targetValue = if (active) PillScopeColors.SafeEmeraldSurface else PillScopeColors.SurfaceTint,
        label = "stepBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) PillScopeColors.SafeEmeraldDark else PillScopeColors.InkSecondary,
        label = "stepFg",
    )

    Surface(
        shape = CircleShape,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (active) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = PillScopeColors.SafeEmerald,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
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
                shape = CircleShape,
                color = bg,
                border = BorderStroke(1.dp, fg.copy(alpha = 0.32f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
        modifier = modifier,
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
                    text = "Source: ${formatSectionLabel(trigger.sourceSection)} (${trigger.severity.lowercase().replaceFirstChar { it.uppercase() }} priority)",
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
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
private fun InteractionRiskCard(interaction: InteractionItemDto) {
    val risk = RiskLevel.fromApi(interaction.severity)
    val accent = when (risk) {
        RiskLevel.MAJOR -> PillScopeColors.CriticalCrimson
        RiskLevel.MODERATE -> PillScopeColors.ModerateAmber
        RiskLevel.MINOR -> PillScopeColors.MinorBlue
        RiskLevel.NONE -> PillScopeColors.SafeEmerald
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${interaction.saltA} + ${interaction.saltB}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = interaction.severity.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            interaction.mlMajorProbability?.let { prob ->
                Text(
                    text = "RDKit ML major probability: ${(prob * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
                LinearProgressIndicator(
                    progress = { prob.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = accent,
                )
            }
            Text(text = interaction.mechanism, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Management: ${interaction.management}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (abstained) Icons.Default.Warning else Icons.Default.Verified,
                    contentDescription = null,
                    tint = if (abstained) PillScopeColors.ModerateAmber else PillScopeColors.SafeEmerald,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        abstained -> "Monograph synthesis abstained"
                        citationVerified -> "Citation-verified clinical briefing"
                        else -> "Clinical briefing"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (abstained) PillScopeColors.ModerateAmber else PillScopeColors.SafeEmerald,
                )
            }
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
            Text(text = explanation, style = MaterialTheme.typography.bodyMedium)
            if (nextSteps.isNotEmpty()) {
                HorizontalDivider(color = PillScopeColors.SlateBorder)
                nextSteps.forEach { step ->
                    Text(
                        text = "• $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkPrimary,
                    )
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
private fun ExpandableCitationCard(citation: MonographCitationDto) {
    var expanded by remember(citation.chunkId) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${citation.canonicalSalt.replaceFirstChar { it.uppercase() }} (${citation.sectionTitle})",
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                    Text(
                        text = "LOINC ${citation.loincSectionCode}, relevance ${(citation.rerankScore * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse monograph" else "Expand monograph",
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(2.dp))
                    HorizontalDivider(color = PillScopeColors.SlateBorder)
                    Text(text = citation.excerpt, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "SPL set ID: ${citation.setId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                    val isWebUrl = citation.sourceUrl.startsWith("https://") ||
                        citation.sourceUrl.startsWith("http://")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = if (isWebUrl) {
                            Modifier.clickable { runCatching { uriHandler.openUri(citation.sourceUrl) } }
                        } else {
                            Modifier
                        },
                    ) {
                        Text(
                            text = citation.sourceUrl,
                            style = MaterialTheme.typography.labelSmall.copy(
                                textDecoration = if (isWebUrl) TextDecoration.Underline else TextDecoration.None,
                            ),
                            color = PillScopeColors.MinorBlue,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isWebUrl) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open source link",
                                tint = PillScopeColors.MinorBlue,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CachedScanHistoryCard(
    summary: CachedScanSummary,
    isCurrentlyOpen: Boolean,
    onClick: () -> Unit,
) {
    val riskColor = when (summary.overallRisk) {
        RiskLevel.MAJOR -> PillScopeColors.CriticalCrimson
        RiskLevel.MODERATE -> PillScopeColors.ModerateAmber
        RiskLevel.MINOR -> PillScopeColors.MinorBlue
        RiskLevel.NONE -> PillScopeColors.SafeEmerald
    }
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

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyOpen) {
                PillScopeColors.ClinicalTealContainer.copy(alpha = 0.45f)
            } else {
                PillScopeColors.CardSurface
            },
        ),
        border = BorderStroke(
            width = if (isCurrentlyOpen) 1.5.dp else 1.dp,
            color = if (isCurrentlyOpen) PillScopeColors.ClinicalTeal else PillScopeColors.SlateBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            Surface(
                shape = CircleShape,
                color = riskColor.copy(alpha = 0.14f),
            ) {
                Text(
                    text = if (summary.overallRisk == RiskLevel.NONE) {
                        "Safe"
                    } else {
                        summary.overallRisk.name.lowercase().replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = riskColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
