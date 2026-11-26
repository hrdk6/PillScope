package com.pillscope.app.ui.cabinet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.OfflineAllergyChecker
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.common.DosagePresetChips
import com.pillscope.app.ui.common.formatSaltComponents
import com.pillscope.app.ui.common.normalizeSaltInput
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.PillScopeColors

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun CabinetScreen(
    viewModel: CabinetViewModel,
    onRunCabinetInteractionCheck: ((ScanAnalyzeRequestDto) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val recalls by viewModel.activeRecalls.collectAsStateWithLifecycle()
    val activeAllergies by viewModel.activeProfileAllergies.collectAsStateWithLifecycle()
    val graphState by viewModel.graphState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteMed by remember { mutableStateOf<CabinetMedication?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = PillScopeColors.SlateSurface,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = PillScopeColors.ClinicalTeal,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add medication")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.medicationCabinetTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                )
                IconButton(
                    onClick = viewModel::syncCabinet,
                    enabled = !uiState.isSyncing,
                ) {
                    if (uiState.isSyncing) {
                        LoadingIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PillScopeColors.ClinicalTeal,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync cabinet",
                            tint = PillScopeColors.ClinicalTeal,
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = uiState.displayMode == CabinetDisplayMode.LIST,
                    onClick = { viewModel.setDisplayMode(CabinetDisplayMode.LIST) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ViewAgenda,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(strings.listView) },
                )
                SegmentedButton(
                    selected = uiState.displayMode == CabinetDisplayMode.NETWORK,
                    onClick = { viewModel.setDisplayMode(CabinetDisplayMode.NETWORK) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(strings.networkView) },
                )
            }

            if (uiState.displayMode == CabinetDisplayMode.NETWORK) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CabinetAlertBanners(
                        recalls = recalls,
                        statusBanner = uiState.statusBanner,
                        statusIsError = uiState.statusIsError,
                        duplicateAlerts = uiState.lastDuplicateAlerts,
                        allergyAlerts = uiState.lastAllergyAlerts,
                        onDismissStatus = viewModel::dismissStatusBanner,
                        onDismissSafetyAlerts = viewModel::dismissSafetyAlerts,
                    )
                    CabinetNetworkGraph(
                        graphState = graphState,
                        onSelectNode = viewModel::selectGraphNode,
                        onSelectEdge = viewModel::selectGraphEdge,
                        onMoveNode = viewModel::moveGraphNode,
                        onResetLayout = viewModel::resetGraphLayout,
                        onInspectMedication = viewModel::openMedicationDetail,
                        onCheckCabinetInteractions = if (onRunCabinetInteractionCheck != null) {
                            {
                                viewModel.buildCabinetInteractionCheckRequest()
                                    ?.let(onRunCabinetInteractionCheck)
                            }
                        } else {
                            null
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (recalls.isNotEmpty() ||
                        uiState.statusBanner != null ||
                        uiState.lastDuplicateAlerts.isNotEmpty() ||
                        uiState.lastAllergyAlerts.isNotEmpty()
                    ) {
                        item(key = "cabinet_banners") {
                            CabinetAlertBanners(
                                recalls = recalls,
                                statusBanner = uiState.statusBanner,
                                statusIsError = uiState.statusIsError,
                                duplicateAlerts = uiState.lastDuplicateAlerts,
                                allergyAlerts = uiState.lastAllergyAlerts,
                                onDismissStatus = viewModel::dismissStatusBanner,
                                onDismissSafetyAlerts = viewModel::dismissSafetyAlerts,
                            )
                        }
                    }

                    if (medications.isEmpty()) {
                        item(key = "cabinet_empty") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                            ) {
                                Text(
                                    text = strings.emptyCabinetMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PillScopeColors.InkSecondary,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        }
                    } else {
                        items(medications, key = { it.id }) { med ->
                            CabinetMedicationCard(
                                medication = med,
                                onClick = { viewModel.openMedicationDetail(med.id) },
                                onToggleActive = { active -> viewModel.toggleActive(med.id, active) },
                                onDelete = { pendingDeleteMed = med },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMedicationDialog(
            suggestions = uiState.catalogSuggestions,
            isSearching = uiState.isSearchingCatalog,
            existingMedications = medications,
            profileAllergies = activeAllergies,
            onQueryChanged = viewModel::searchCatalog,
            onDismiss = {
                viewModel.clearCatalogSuggestions()
                showAddDialog = false
            },
            onConfirm = { name, salts, schedule, brandMappingId ->
                viewModel.clearCatalogSuggestions()
                viewModel.addMedication(name, salts, schedule, brandMappingId)
                showAddDialog = false
            },
        )
    }

    pendingDeleteMed?.let { med ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMed = null },
            title = { Text(strings.removeMedicationTitle) },
            text = {
                Text("${strings.removeFromCabinet}: ${med.displayName}?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMedication(med.id)
                        pendingDeleteMed = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = PillScopeColors.CriticalCrimson,
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(strings.remove)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMed = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }

    uiState.selectedMedicationDetail?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeMedicationDetail,
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            MedicationDetailBottomSheetContent(
                medication = detail,
                isLoading = uiState.isLoadingDetail,
                onSaveEdits = { newName, newSaltsCsv, newSchedule ->
                    viewModel.updateMedicationDetails(detail.id, newName, newSchedule, newSaltsCsv)
                },
                onToggleActive = { active ->
                    viewModel.toggleActive(detail.id, active)
                },
                onRequestDelete = {
                    pendingDeleteMed = detail
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun CabinetAlertBanners(
    recalls: List<ProfileRecallAlertItemDto>,
    statusBanner: String?,
    statusIsError: Boolean,
    duplicateAlerts: List<DuplicateSaltAlertDto>,
    allergyAlerts: List<AllergyAlertDto>,
    onDismissStatus: () -> Unit,
    onDismissSafetyAlerts: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recalls.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = PillScopeColors.CriticalBannerContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = PillScopeColors.OnCriticalBanner,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "US FDA Recall Alert (${recalls.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = PillScopeColors.OnCriticalBanner,
                        )
                    }
                    recalls.take(2).forEach { alert ->
                        Text(
                            text = "${alert.matchedMedicationName} (${alert.classification}): ${alert.reasonForRecall}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.OnCriticalBanner,
                        )
                    }
                    Text(
                        text = "Matched by active ingredient against US FDA recalls — verify lot number on your package.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.OnCriticalBanner.copy(alpha = 0.9f),
                    )
                }
            }
        }

        statusBanner?.let { msg ->
            val bg = if (statusIsError) {
                PillScopeColors.ModerateAmberSurface
            } else {
                PillScopeColors.ClinicalTealContainer
            }
            val fg = if (statusIsError) {
                PillScopeColors.ModerateAmberDark
            } else {
                PillScopeColors.ClinicalTealDeep
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = bg,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissStatus,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss status banner",
                            tint = fg,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        if (duplicateAlerts.isNotEmpty() || allergyAlerts.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PillScopeColors.CriticalBannerContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Safety override triggered",
                            style = MaterialTheme.typography.titleSmall,
                            color = PillScopeColors.OnCriticalBanner,
                        )
                        IconButton(
                            onClick = onDismissSafetyAlerts,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss safety warning",
                                tint = PillScopeColors.OnCriticalBanner,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    allergyAlerts.forEach { allergy ->
                        Text(
                            text = "• Allergy (${allergy.allergen}): ${allergy.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.OnCriticalBanner,
                        )
                    }
                    duplicateAlerts.forEach { dup ->
                        Text(
                            text = "• Duplicate ingredient ${dup.canonicalSalt} already active in ${dup.existingDisplayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.OnCriticalBanner,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinetMedicationCard(
    medication: CabinetMedication,
    onClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val isPaused = !medication.isActive
    val saltText = remember(medication.genericSalts, medication.canonicalSaltKeys) {
        formatSaltComponents(medication.genericSalts, medication.canonicalSaltKeys)
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaused) PillScopeColors.SurfaceTint else PillScopeColors.CardSurface,
        ),
        border = BorderStroke(
            width = if (medication.hasActiveRecall && !isPaused) 1.5.dp else 1.dp,
            color = if (medication.hasActiveRecall && !isPaused) {
                PillScopeColors.CriticalCrimson
            } else {
                PillScopeColors.SlateBorder
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (isPaused) 0.72f else 1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = medication.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    if (isPaused) {
                        Surface(
                            shape = CircleShape,
                            color = PillScopeColors.SlateBorder.copy(alpha = 0.65f),
                        ) {
                            Text(
                                text = strings.pausedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.InkPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (medication.hasActiveRecall) {
                        Surface(
                            shape = CircleShape,
                            color = PillScopeColors.CriticalCrimsonSurface,
                        ) {
                            Text(
                                text = "FDA Recall",
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.CriticalCrimsonDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = saltText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                )
                if (medication.lifestyleTriggers.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        medication.lifestyleTriggers.take(3).forEach { trigger ->
                            Surface(
                                shape = CircleShape,
                                color = PillScopeColors.ModerateAmberSurface,
                            ) {
                                Text(
                                    text = trigger.type.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PillScopeColors.ModerateAmberDark,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (medication.isPendingSync) {
                            Icons.Default.CloudQueue
                        } else {
                            Icons.Default.CloudDone
                        },
                        contentDescription = null,
                        tint = if (medication.isPendingSync) {
                            PillScopeColors.ModerateAmber
                        } else {
                            PillScopeColors.SafeEmerald
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = buildString {
                            append(if (medication.isPendingSync) strings.pendingSyncLabel else strings.syncedLabel)
                            medication.dosageSchedule?.let { append(", $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            Switch(checked = medication.isActive, onCheckedChange = onToggleActive)
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove ${medication.displayName}",
                    tint = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MedicationDetailBottomSheetContent(
    medication: CabinetMedication,
    isLoading: Boolean,
    onSaveEdits: (displayName: String, rawSaltsCsv: String?, dosageSchedule: String?) -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openUri = rememberSafeUriOpener()
    var isEditing by remember(medication.id) { mutableStateOf(false) }
    var editedName by remember(medication.id, medication.displayName) { mutableStateOf(medication.displayName) }
    val initialSaltsCsv = remember(medication.id, medication.genericSalts, medication.canonicalSaltKeys) {
        if (medication.genericSalts.isNotEmpty()) {
            medication.genericSalts.joinToString(", ") { salt ->
                val strength = salt.strengthText?.takeIf { it.isNotBlank() }
                    ?: salt.strengthMg?.let { mg -> if (mg % 1.0 == 0.0) "${mg.toInt()}mg" else "${mg}mg" }
                if (strength != null) "${salt.canonicalSalt} $strength" else salt.canonicalSalt
            }
        } else {
            medication.canonicalSaltKeys.joinToString(", ")
        }
    }
    var editedSaltsCsv by remember(medication.id, initialSaltsCsv) { mutableStateOf(initialSaltsCsv) }
    var editedSchedule by remember(medication.id, medication.dosageSchedule) {
        mutableStateOf(medication.dosageSchedule.orEmpty())
    }

    val normalizedDraftSalts = remember(editedSaltsCsv) { normalizeSaltInput(editedSaltsCsv) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = medication.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = formatSaltComponents(medication.genericSalts, medication.canonicalSaltKeys),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoading) {
                    LoadingIndicator(modifier = Modifier.size(22.dp), color = PillScopeColors.ClinicalTeal)
                }
                IconButton(onClick = { isEditing = !isEditing }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit medication details",
                        tint = PillScopeColors.ClinicalTeal,
                    )
                }
            }
        }

        // Active / Paused control directly accessible from both List and Network views
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = PillScopeColors.SlateSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (medication.isActive) "Active in regimen" else "Paused",
                        style = MaterialTheme.typography.titleSmall,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = if (medication.isActive) {
                            "Included in interaction checks and daily schedule."
                        } else {
                            "Excluded from interaction checks and daily schedule."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
                Switch(
                    checked = medication.isActive,
                    onCheckedChange = onToggleActive,
                )
            }
        }

        if (isEditing) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PillScopeColors.SlateSurface,
                border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Medication name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editedSaltsCsv,
                        onValueChange = { editedSaltsCsv = it },
                        label = { Text("Active ingredients (comma separated)") },
                        supportingText = { Text("Strengths like 500mg are extracted automatically.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editedSchedule,
                        onValueChange = { editedSchedule = it },
                        label = { Text("Dosage schedule (e.g. Twice daily)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DosagePresetChips(
                        currentValue = editedSchedule,
                        onValueChange = { editedSchedule = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                editedName = medication.displayName
                                editedSaltsCsv = initialSaltsCsv
                                editedSchedule = medication.dosageSchedule.orEmpty()
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                val saltsChanged = editedSaltsCsv.trim() != initialSaltsCsv.trim()
                                onSaveEdits(
                                    editedName.trim(),
                                    if (saltsChanged) editedSaltsCsv else null,
                                    editedSchedule.trim().takeIf { it.isNotEmpty() },
                                )
                                isEditing = false
                            },
                            enabled = editedName.isNotBlank() && normalizedDraftSalts.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        } else {
            Text(
                text = "Dosage schedule: ${medication.dosageSchedule?.takeIf { it.isNotBlank() } ?: "Not set (tap edit)"}",
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkSecondary,
            )
        }

        if (medication.activeRecalls.isNotEmpty()) {
            HorizontalDivider(color = PillScopeColors.SlateBorder)
            Text(
                text = "US FDA Recall Alerts (${medication.activeRecalls.size})",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.CriticalCrimson,
            )
            medication.activeRecalls.forEach { recall ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.CriticalCrimsonSurface,
                    border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${recall.classification} recall (${recall.status})",
                            style = MaterialTheme.typography.labelMedium,
                            color = PillScopeColors.CriticalCrimsonDark,
                            fontWeight = FontWeight.SemiBold,
                        )
                        recall.recallingFirm?.takeIf { it.isNotBlank() }?.let { firm ->
                            Text(
                                text = "Manufacturer: $firm",
                                style = MaterialTheme.typography.bodySmall,
                                color = PillScopeColors.InkPrimary,
                            )
                        }
                        Text(
                            text = recall.reasonForRecall,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkPrimary,
                        )
                        val isWebUrl = recall.sourceUrl.startsWith("https://") ||
                            recall.sourceUrl.startsWith("http://")
                        if (isWebUrl) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { openUri(recall.sourceUrl) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = "View FDA enforcement notice",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    color = PillScopeColors.MinorBlue,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open FDA enforcement notice",
                                    tint = PillScopeColors.MinorBlue,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (medication.lifestyleTriggers.isNotEmpty()) {
            HorizontalDivider(color = PillScopeColors.SlateBorder)
            Text(
                text = "Food, alcohol, and lifestyle precautions",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            medication.lifestyleTriggers.forEach { trigger ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.SlateSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${trigger.type.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }} (${trigger.severity.lowercase()})",
                            style = MaterialTheme.typography.labelLarge,
                            color = PillScopeColors.ClinicalTealDeep,
                        )
                        Text(
                            text = trigger.shortNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkPrimary,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = PillScopeColors.SlateBorder)

        OutlinedButton(
            onClick = onRequestDelete,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.55f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = PillScopeColors.CriticalCrimson,
            ),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Remove from cabinet")
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMedicationDialog(
    suggestions: List<BrandCandidateDto>,
    isSearching: Boolean,
    existingMedications: List<CabinetMedication>,
    profileAllergies: List<AllergyEntryDto>,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (displayName: String, rawSaltsCsv: String, dosageSchedule: String?, brandMappingId: String?) -> Unit,
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    var salts by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var selectedBrandId by remember { mutableStateOf<String?>(null) }

    val parsedSalts = remember(salts) {
        normalizeSaltInput(salts).map { it.canonicalSalt }
    }
    val inlineAllergyAlerts = remember(parsedSalts, profileAllergies) {
        OfflineAllergyChecker.detectConflicts(parsedSalts, profileAllergies)
    }
    val inlineDuplicateMatches = remember(parsedSalts, existingMedications) {
        if (parsedSalts.isEmpty()) {
            emptyList()
        } else {
            val active = existingMedications.filter { it.isActive }
            parsedSalts.flatMap { salt ->
                active.filter { med -> med.canonicalSaltKeys.any { it.equals(salt, ignoreCase = true) } }
                    .map { med -> salt to med.displayName }
            }
        }
    }
    val hasInlineWarning = inlineAllergyAlerts.isNotEmpty() || inlineDuplicateMatches.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addToCabinetTitle) },
        text = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        selectedBrandId = null
                        onQueryChanged(it)
                    },
                    label = { Text(strings.medicineBrandOrName) },
                    trailingIcon = {
                        if (isSearching) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = PillScopeColors.ClinicalTeal,
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty() && selectedBrandId == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.SlateSurface,
                        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 156.dp),
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            suggestions.take(4).forEachIndexed { idx, candidate ->
                                if (idx > 0) HorizontalDivider(color = PillScopeColors.SlateBorder)
                                val displaySalts = formatSaltComponents(
                                    candidate.genericSalts,
                                    candidate.canonicalSaltKeys,
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            name = candidate.brandName
                                            salts = if (candidate.genericSalts.isNotEmpty()) {
                                                candidate.genericSalts.joinToString(", ") { s ->
                                                    val str = s.strengthText?.takeIf { it.isNotBlank() }
                                                        ?: s.strengthMg?.let { mg ->
                                                            if (mg % 1.0 == 0.0) "${mg.toInt()}mg" else "${mg}mg"
                                                        }
                                                    if (str != null) "${s.canonicalSalt} $str" else s.canonicalSalt
                                                }
                                            } else {
                                                candidate.canonicalSaltKeys.joinToString(", ")
                                            }
                                            selectedBrandId = candidate.brandMappingId
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = candidate.brandName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = PillScopeColors.InkPrimary,
                                    )
                                    Text(
                                        text = displaySalts,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PillScopeColors.ClinicalTeal,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = salts,
                    onValueChange = { salts = it },
                    label = { Text(strings.activeIngredientsCsv) },
                    supportingText = { Text("e.g. acetaminophen 650mg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text(strings.dosageScheduleOptional) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DosagePresetChips(
                    currentValue = schedule,
                    onValueChange = { schedule = it },
                )
                if (hasInlineWarning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.CriticalCrimsonSurface,
                        border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Safety warning before saving",
                                style = MaterialTheme.typography.labelLarge,
                                color = PillScopeColors.CriticalCrimsonDark,
                            )
                            inlineAllergyAlerts.forEach { alert ->
                                Text(
                                    text = "• Allergy (${alert.allergen}): ${alert.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PillScopeColors.CriticalCrimsonDark,
                                )
                            }
                            inlineDuplicateMatches.forEach { (salt, medName) ->
                                Text(
                                    text = "• Duplicate ingredient '$salt' already active in $medName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PillScopeColors.CriticalCrimsonDark,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name,
                        salts,
                        schedule.takeIf { it.isNotBlank() },
                        selectedBrandId,
                    )
                },
                enabled = name.isNotBlank() && parsedSalts.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (hasInlineWarning) {
                        PillScopeColors.CriticalCrimson
                    } else {
                        PillScopeColors.ClinicalTeal
                    },
                    disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                ),
            ) {
                Text(if (hasInlineWarning) strings.saveAnyway else strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}
