package com.pillscope.app.ui.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.repository.AnalysisStage
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.data.repository.ScanAnalysisState
import com.pillscope.app.ui.theme.PillScopeColors
import kotlin.math.roundToInt

@Composable
fun ProgressiveAnalysisScreen(
    viewModel: AnalysisViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderStatusCard(state = state)

        if (state.safetyBannerLocked) {
            LockedCriticalSafetyBanner(state = state)
        }

        if (state.stage != AnalysisStage.COMPLETE &&
            state.stage != AnalysisStage.IDLE &&
            state.stage != AnalysisStage.ERROR
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = if (state.safetyBannerLocked) {
                    PillScopeColors.CriticalCrimson
                } else {
                    PillScopeColors.ClinicalTeal
                },
            )
            Text(
                text = when (state.stage) {
                    AnalysisStage.CONNECTING -> "Running deterministic safety gate..."
                    AnalysisStage.DETERMINISTIC_READY -> "Scoring molecular DDI risk (RDKit + ML)..."
                    AnalysisStage.ML_READY -> "Retrieving & reranking openFDA/DailyMed monographs..."
                    AnalysisStage.CITATIONS_READY -> "Synthesizing citation-verified briefing..."
                    AnalysisStage.SUMMARY_READY -> "Finalizing audit report..."
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = PillScopeColors.InkSecondary,
            )
        }

        state.errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PillScopeColors.ModerateAmberSurface,
                border = BorderStroke(1.dp, PillScopeColors.ModerateAmber),
            ) {
                Text(
                    text = "Stream notice: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ModerateAmber,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.interactions.isNotEmpty()) {
            Text(
                text = "Pairwise Interaction Risks (${state.interactions.size})",
                style = MaterialTheme.typography.titleMedium,
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
                text = "Cited openFDA / DailyMed Monographs (${state.citations.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            state.citations.forEach { citation ->
                ExpandableCitationCard(citation = citation)
            }
        }
    }
}

@Composable
private fun HeaderStatusCard(state: ScanAnalysisState) {
    val (bg, fg) = when (state.overallRisk) {
        RiskLevel.MAJOR -> PillScopeColors.CriticalCrimsonSurface to PillScopeColors.CriticalCrimson
        RiskLevel.MODERATE -> PillScopeColors.ModerateAmberSurface to PillScopeColors.ModerateAmber
        RiskLevel.MINOR -> PillScopeColors.MinorBlueSurface to PillScopeColors.MinorBlue
        RiskLevel.NONE -> PillScopeColors.SafeEmeraldSurface to PillScopeColors.SafeEmerald
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.submittedQuery.ifBlank { "No active scan" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(shape = RoundedCornerShape(10.dp), color = bg) {
                    Text(
                        text = state.overallRisk.name,
                        color = fg,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.resolvedSalts.isNotEmpty()) {
                Text(
                    text = "Salts: ${state.resolvedSalts.joinToString(" + ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.fromOfflineCache) {
                Text(
                    text = "Loaded from offline Room cache",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}

@Composable
private fun LockedCriticalSafetyBanner(state: ScanAnalysisState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CriticalCrimson),
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
                    contentDescription = "Locked critical alert",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "CRITICAL SAFETY ALERT — LOCKED",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            state.duplicateSalts.forEach { dup ->
                Text(
                    text = "• Duplicate active salt '${dup.canonicalSalt}' already active in ${dup.existingDisplayName}.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.deterministicAlerts
                .filter { RiskLevel.fromApi(it.severity) == RiskLevel.MAJOR }
                .forEach { alert ->
                    Text(
                        text = "• MAJOR DDI: ${alert.saltA} + ${alert.saltB} — ${alert.management}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
        }
    }
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
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
                Text(
                    text = "${interaction.severity} · ${interaction.source}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            interaction.mlMajorProbability?.let { prob ->
                Text(
                    text = "ML P(Major): ${(prob * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
                LinearProgressIndicator(
                    progress = { prob.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
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
        shape = RoundedCornerShape(16.dp),
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
                        abstained -> "Monograph Synthesis Abstained (Unindexed Salt)"
                        citationVerified -> "Citation-Verified Clinical Briefing"
                        else -> "Clinical Briefing"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (abstained) PillScopeColors.ModerateAmber else PillScopeColors.SafeEmerald,
                )
            }
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
            Text(text = explanation, style = MaterialTheme.typography.bodyMedium)
            if (nextSteps.isNotEmpty()) {
                HorizontalDivider()
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

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
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
                        text = "${citation.canonicalSalt.uppercase()} · ${citation.sectionTitle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                    Text(
                        text = "LOINC ${citation.loincSectionCode} · Relevance ${(citation.rerankScore * 100).roundToInt()}%",
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
                    HorizontalDivider()
                    Text(text = citation.excerpt, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "SPL Set ID: ${citation.setId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                    Text(
                        text = citation.sourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.MinorBlue,
                    )
                }
            }
        }
    }
}
