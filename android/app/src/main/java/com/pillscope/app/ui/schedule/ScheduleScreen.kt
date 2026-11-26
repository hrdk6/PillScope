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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.AppliedSeparationConstraintDto
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.ScheduleSlotGroupDto
import com.pillscope.app.data.remote.dto.ScheduledDoseItemDto
import com.pillscope.app.data.remote.dto.UnresolvableScheduleClashDto
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.theme.PillScopeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    onOpenCabinet: (() -> Unit)? = null,
    isSpeakingSchedule: Boolean = false,
    onReadScheduleAloud: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val recalls by viewModel.activeRecalls.collectAsStateWithLifecycle()
    val activeMedCount by viewModel.activeMedicationCount.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val takenKeys by viewModel.takenDoseKeys.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var pendingRecalledTake by remember {
        mutableStateOf<Triple<ScheduledDoseItemDto, String, List<ProfileRecallAlertItemDto>>?>(null)
    }

    // Refresh adherence state across midnight when returning to the screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val slots = schedule?.slots?.takeIf { it.isNotEmpty() } ?: DefaultEmptySlots
    val allDoses = remember(slots) {
        slots.flatMap { slot -> slot.medications.map { slot.slot to it } }
    }
    val totalDoseCount = allDoses.size
    val takenDoseCount = remember(allDoses, takenKeys) {
        allDoses.count { (slotName, dose) ->
            viewModel.doseKey(dose.medicationId, slotName, dose.doseNumber) in takenKeys
        }
    }
    val todayLabel = remember(takenKeys, language) {
        DateTimeFormatter.ofPattern("EEEE, MMM d", language.locale).format(LocalDate.now())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.dailyScheduleTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onReadScheduleAloud != null) {
                        Surface(
                            onClick = onReadScheduleAloud,
                            shape = CircleShape,
                            color = if (isSpeakingSchedule) {
                                PillScopeColors.CriticalCrimsonSurface
                            } else {
                                PillScopeColors.ClinicalTealContainer
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (isSpeakingSchedule) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = strings.readScheduleAloud,
                                    tint = if (isSpeakingSchedule) {
                                        PillScopeColors.CriticalCrimsonDark
                                    } else {
                                        PillScopeColors.ClinicalTealDeep
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (isSpeakingSchedule) strings.stopSpeaking else strings.readScheduleAloud,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSpeakingSchedule) {
                                        PillScopeColors.CriticalCrimsonDark
                                    } else {
                                        PillScopeColors.ClinicalTealDeep
                                    },
                                )
                            }
                        }
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
            }
            Text(
                text = strings.dailyScheduleSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkSecondary,
            )
        }

        // Daily date & adherence progress header
        if (totalDoseCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = todayLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = PillScopeColors.InkPrimary,
                        )
                        Text(
                            text = "$takenDoseCount of $totalDoseCount ${if (totalDoseCount == 1) "dose" else "doses"} taken",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (takenDoseCount == totalDoseCount) {
                                PillScopeColors.SafeEmerald
                            } else {
                                PillScopeColors.ClinicalTeal
                            },
                        )
                    }
                    LinearProgressIndicator(
                        progress = { takenDoseCount.toFloat() / totalDoseCount.coerceAtLeast(1).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (takenDoseCount == totalDoseCount) {
                            PillScopeColors.SafeEmerald
                        } else {
                            PillScopeColors.ClinicalTeal
                        },
                        trackColor = PillScopeColors.SurfaceTint,
                    )
                }
            }
        }

        uiState.statusNotice?.let { notice ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (uiState.noticeIsError) {
                    PillScopeColors.ModerateAmberSurface
                } else {
                    PillScopeColors.ClinicalTealContainer
                },
                border = BorderStroke(
                    1.dp,
                    if (uiState.noticeIsError) {
                        PillScopeColors.ModerateAmber.copy(alpha = 0.5f)
                    } else {
                        PillScopeColors.ClinicalTeal.copy(alpha = 0.3f)
                    },
                ),
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.noticeIsError) {
                        PillScopeColors.ModerateAmberDark
                    } else {
                        PillScopeColors.ClinicalTealDeep
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        val clashes = schedule?.unresolvableClashes.orEmpty()
        if (clashes.isNotEmpty()) {
            UnresolvableClashesCallout(clashes = clashes)
        }

        if (totalDoseCount == 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (activeMedCount > 0) {
                            strings.setDosagesTitle
                        } else {
                            strings.noDosesScheduledYet
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = if (activeMedCount > 0) {
                            "You have $activeMedCount active ${if (activeMedCount == 1) "medication" else "medications"} in Cabinet without a dosing frequency yet. Open any medicine in Cabinet and add a schedule (such as Once daily or Twice daily) to generate your daily timeline."
                        } else {
                            "Add your active prescriptions in Cabinet to receive an automatically spaced morning-to-bedtime schedule."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                    if (onOpenCabinet != null) {
                        Button(
                            onClick = onOpenCabinet,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Default.Medication, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (activeMedCount > 0) strings.setDosageInCabinet else strings.openCabinet)
                        }
                    }
                }
            }
        } else {
            val appliedSeparations = schedule?.appliedSeparations.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                slots.forEachIndexed { index, slotGroup ->
                    TimelineSlotRow(
                        slotGroup = slotGroup,
                        appliedSeparations = appliedSeparations,
                        activeRecalls = recalls,
                        takenKeys = takenKeys,
                        doseKeyFor = { dose ->
                            viewModel.doseKey(dose.medicationId, slotGroup.slot, dose.doseNumber)
                        },
                        onToggleTaken = { dose, matchedRecalls, currentlyTaken ->
                            if (!currentlyTaken && matchedRecalls.isNotEmpty()) {
                                pendingRecalledTake = Triple(dose, slotGroup.slot, matchedRecalls)
                            } else {
                                viewModel.toggleDoseTaken(dose.medicationId, slotGroup.slot, dose.doseNumber)
                            }
                        },
                        isLast = index == slots.lastIndex,
                    )
                }
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

    pendingRecalledTake?.let { (dose, slotName, matchedRecalls) ->
        val firstRecall = matchedRecalls.first()
        AlertDialog(
            onDismissRequest = { pendingRecalledTake = null },
            title = { Text("Active FDA recall warning") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${dose.displayName} matches an active US FDA ${firstRecall.classification} recall: ${firstRecall.reasonForRecall}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Check the manufacturer and lot number on your bottle or blister pack with a pharmacist before taking this dose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.toggleDoseTaken(dose.medicationId, slotName, dose.doseNumber)
                        pendingRecalledTake = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = PillScopeColors.CriticalCrimson,
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text("Mark taken anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecalledTake = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun UnresolvableClashesCallout(
    clashes: List<UnresolvableScheduleClashDto>,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = PillScopeColors.CriticalBannerContainer,
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
                    tint = PillScopeColors.OnCriticalBanner,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = LocalAppStrings.current.conflictsCannotResolve,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.OnCriticalBanner,
                    fontWeight = FontWeight.Bold,
                )
            }
            clashes.forEach { clash ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${clash.medicationAName} + ${clash.medicationBName} (${clash.saltA} + ${clash.saltB})",
                        style = MaterialTheme.typography.titleSmall,
                        color = PillScopeColors.OnCriticalBanner,
                    )
                    Text(
                        text = clash.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.OnCriticalBanner.copy(alpha = 0.94f),
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
    activeRecalls: List<ProfileRecallAlertItemDto>,
    takenKeys: Set<String>,
    doseKeyFor: (ScheduledDoseItemDto) -> String,
    onToggleTaken: (ScheduledDoseItemDto, List<ProfileRecallAlertItemDto>, Boolean) -> Unit,
    isLast: Boolean,
) {
    val strings = LocalAppStrings.current
    val displayTitle = strings.slotLabel(slotGroup.slot)
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
                        tint = if (hasMeds) MaterialTheme.colorScheme.onPrimary else PillScopeColors.InkSecondary,
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
                        text = strings.noDosesInSlot,
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.InkSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                slotGroup.medications.forEach { dose ->
                    val isTaken = doseKeyFor(dose) in takenKeys
                    val matchedRecalls = remember(dose, activeRecalls) {
                        val doseSalts = dose.canonicalSaltKeys.map { it.trim().lowercase() }.toSet()
                        activeRecalls.filter { recall ->
                            recall.matchedMedicationId == dose.medicationId ||
                                recall.matchedMedicationName.equals(dose.displayName, ignoreCase = true) ||
                                recall.matchedTerms.any { it.trim().lowercase() in doseSalts }
                        }
                    }
                    ScheduledDoseCard(
                        dose = dose,
                        appliedSeparations = appliedSeparations.filter {
                            it.medicationAId == dose.medicationId || it.medicationBId == dose.medicationId
                        },
                        matchedRecalls = matchedRecalls,
                        isTaken = isTaken,
                        onToggleTaken = { onToggleTaken(dose, matchedRecalls, isTaken) },
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
    matchedRecalls: List<ProfileRecallAlertItemDto>,
    isTaken: Boolean,
    onToggleTaken: () -> Unit,
) {
    val hasRecall = matchedRecalls.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isTaken -> PillScopeColors.SafeEmeraldSurface.copy(alpha = 0.55f)
                else -> PillScopeColors.CardSurface
            },
        ),
        border = BorderStroke(
            width = if (hasRecall) 1.5.dp else 1.dp,
            color = when {
                hasRecall -> PillScopeColors.CriticalCrimson
                isTaken -> PillScopeColors.SafeEmerald.copy(alpha = 0.5f)
                else -> PillScopeColors.SlateBorder
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Group medication title, dose count, and active salts tightly on the left so
            // the Take/Taken action button's 48dp touch target never inserts a gap below the title.
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = dose.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = PillScopeColors.InkPrimary,
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
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = dose.canonicalSaltKeys.joinToString(" + "),
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                    )
                }

                Surface(
                    onClick = onToggleTaken,
                    modifier = Modifier.heightIn(min = 38.dp),
                    shape = CircleShape,
                    color = if (isTaken) {
                        PillScopeColors.SafeEmerald
                    } else {
                        PillScopeColors.SurfaceTint
                    },
                    border = if (!isTaken) BorderStroke(1.dp, PillScopeColors.SlateBorder) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (isTaken) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = if (isTaken) "Undo taken for ${dose.displayName}" else "Mark ${dose.displayName} taken",
                            tint = if (isTaken) MaterialTheme.colorScheme.onTertiary else PillScopeColors.InkPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        val strings = LocalAppStrings.current
                        Text(
                            text = if (isTaken) strings.takenLabel else strings.takeAction,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isTaken) MaterialTheme.colorScheme.onTertiary else PillScopeColors.InkPrimary,
                        )
                    }
                }
            }

            if (hasRecall) {
                val recall = matchedRecalls.first()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = PillScopeColors.CriticalCrimsonSurface,
                    border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = PillScopeColors.CriticalCrimsonDark,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "US FDA ${recall.classification} recall — verify lot before taking",
                            style = MaterialTheme.typography.labelMedium,
                            color = PillScopeColors.CriticalCrimsonDark,
                        )
                    }
                }
            }

            dose.frequencyNote?.takeIf { it.isNotBlank() }?.let { note ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PillScopeColors.ModerateAmberSurface,
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.ModerateAmberDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

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
                            String.format(Locale.US, "%.1fh", hours)
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

            if (dose.rationale.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    dose.rationale.forEach { note ->
                        Text(
                            text = if (dose.rationale.size > 1) "• $note" else note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkSecondary,
                        )
                    }
                }
            }
        }
    }
}

private fun slotIconFor(rawSlot: String): ImageVector = when (rawSlot) {
    "Morning (Empty Stomach)" -> Icons.Default.WbTwilight
    "Morning (With Food)" -> Icons.Default.LightMode
    "Afternoon" -> Icons.Default.Schedule
    "Evening" -> Icons.Default.NightsStay
    "Bedtime" -> Icons.Default.Bedtime
    else -> Icons.Default.Schedule
}
