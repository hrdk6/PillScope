package com.pillscope.app.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.AppliedSeparationConstraintDto
import com.pillscope.app.data.remote.dto.ScheduleSlotGroupDto
import com.pillscope.app.data.remote.dto.ScheduledDoseItemDto
import com.pillscope.app.data.remote.dto.UnresolvableScheduleClashDto
import com.pillscope.app.ui.theme.PillScopeColors

private val DefaultEmptySlots: List<ScheduleSlotGroupDto> = listOf(
    ScheduleSlotGroupDto(slot = "Morning (Empty Stomach)", recommendedTime = "07:00"),
    ScheduleSlotGroupDto(slot = "Morning (With Food)", recommendedTime = "08:00"),
    ScheduleSlotGroupDto(slot = "Afternoon", recommendedTime = "13:00"),
    ScheduleSlotGroupDto(slot = "Evening", recommendedTime = "18:30"),
    ScheduleSlotGroupDto(slot = "Bedtime", recommendedTime = "22:00"),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    modifier: Modifier = Modifier,
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val slots = schedule?.slots?.takeIf { it.isNotEmpty() } ?: DefaultEmptySlots

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Conflict-free daily schedule",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "Doses separated automatically for optimal absorption.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }
            IconButton(
                onClick = viewModel::refreshSchedule,
                enabled = !uiState.isLoading,
            ) {
                if (uiState.isLoading) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PillScopeColors.ClinicalTeal,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh schedule",
                        tint = PillScopeColors.ClinicalTeal,
                    )
                }
            }
        }

        val clashes = schedule?.unresolvableClashes.orEmpty()
        if (clashes.isNotEmpty()) {
            UnresolvableClashesCallout(clashes = clashes)
        }

        val appliedSeparations = schedule?.appliedSeparations.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            slots.forEachIndexed { index, slotGroup ->
                TimelineSlotRow(
                    slotGroup = slotGroup,
                    appliedSeparations = appliedSeparations,
                    isLast = index == slots.lastIndex,
                )
            }
        }

        schedule?.disclaimer?.takeIf { it.isNotBlank() }?.let { disclaimer ->
            Text(
                text = disclaimer,
                style = MaterialTheme.typography.labelSmall,
                color = PillScopeColors.InkSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun UnresolvableClashesCallout(
    clashes: List<UnresolvableScheduleClashDto>,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = PillScopeColors.CriticalCrimson,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.GppBad,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Clashes timing cannot resolve",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            clashes.forEach { clash ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${clash.medicationAName} + ${clash.medicationBName} (${clash.saltA} + ${clash.saltB})",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = clash.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.94f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineSlotRow(
    slotGroup: ScheduleSlotGroupDto,
    appliedSeparations: List<AppliedSeparationConstraintDto>,
    isLast: Boolean,
) {
    val displayTitle = formatSlotTitle(slotGroup.slot)
    val slotIcon = slotIconFor(slotGroup.slot)
    val hasMeds = slotGroup.medications.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (hasMeds) PillScopeColors.ClinicalTeal else PillScopeColors.SurfaceTint,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = slotIcon,
                        contentDescription = null,
                        tint = if (hasMeds) Color.White else PillScopeColors.InkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = slotGroup.recommendedTime,
                style = MaterialTheme.typography.labelSmall,
                color = PillScopeColors.InkSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(PillScopeColors.SlateBorder),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )

            if (!hasMeds) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.SurfaceTint.copy(alpha = 0.7f),
                ) {
                    Text(
                        text = "No doses scheduled",
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.InkSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                slotGroup.medications.forEach { dose ->
                    ScheduledDoseCard(
                        dose = dose,
                        appliedSeparations = appliedSeparations.filter {
                            it.medicationAId == dose.medicationId || it.medicationBId == dose.medicationId
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledDoseCard(
    dose: ScheduledDoseItemDto,
    appliedSeparations: List<AppliedSeparationConstraintDto>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dose.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (dose.totalDailyDoses > 1) {
                    Surface(
                        shape = CircleShape,
                        color = PillScopeColors.SurfaceTint,
                    ) {
                        Text(
                            text = "Dose ${dose.doseNumber}/${dose.totalDailyDoses}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PillScopeColors.InkSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                text = dose.canonicalSaltKeys.joinToString(" + "),
                style = MaterialTheme.typography.labelMedium,
                color = PillScopeColors.ClinicalTeal,
            )

            if (appliedSeparations.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    appliedSeparations.forEach { sep ->
                        val otherName = if (sep.medicationAId == dose.medicationId) {
                            sep.medicationBName
                        } else {
                            sep.medicationAName
                        }
                        val hours = sep.actualSeparationMinutes / 60.0
                        val formattedHours = if (hours % 1.0 == 0.0) {
                            "${hours.toInt()}h"
                        } else {
                            String.format("%.1fh", hours)
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (sep.satisfied) {
                                PillScopeColors.ClinicalTealContainer
                            } else {
                                PillScopeColors.CriticalCrimsonSurface
                            },
                        ) {
                            Text(
                                text = "Spaced $formattedHours from $otherName",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sep.satisfied) {
                                    PillScopeColors.ClinicalTealDeep
                                } else {
                                    PillScopeColors.CriticalCrimsonDark
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            dose.rationale.firstOrNull()?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}

private fun formatSlotTitle(rawSlot: String): String = when (rawSlot) {
    "Morning (Empty Stomach)" -> "Morning - Empty Stomach"
    "Morning (With Food)" -> "Morning - With Food"
    else -> rawSlot
}

private fun slotIconFor(rawSlot: String): ImageVector = when (rawSlot) {
    "Morning (Empty Stomach)" -> Icons.Default.WbTwilight
    "Morning (With Food)" -> Icons.Default.LightMode
    "Afternoon" -> Icons.Default.Schedule
    "Evening" -> Icons.Default.NightsStay
    "Bedtime" -> Icons.Default.Bedtime
    else -> Icons.Default.Schedule
}
