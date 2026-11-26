package com.pillscope.app.ui.cabinet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.ui.theme.PillScopeColors

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun CabinetScreen(
    viewModel: CabinetViewModel,
    modifier: Modifier = Modifier,
) {
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val recalls by viewModel.activeRecalls.collectAsStateWithLifecycle()
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
                contentColor = Color.White,
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
                    text = "Medication cabinet",
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
                    label = { Text("List") },
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
                    label = { Text("Network") },
                )
            }

            if (recalls.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = PillScopeColors.CriticalCrimson,
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
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "US FDA Recall Alert (${recalls.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                            )
                        }
                        recalls.take(2).forEach { alert ->
                            Text(
                                text = "${alert.matchedMedicationName} (${alert.classification}): ${alert.reasonForRecall}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                        Text(
                            text = "Matched by active salt against US FDA recalls — verify lot number on your package.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.88f),
                        )
                    }
                }
            }

            uiState.statusBanner?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (uiState.statusIsError) {
                        PillScopeColors.ModerateAmberSurface
                    } else {
                        PillScopeColors.ClinicalTealContainer
                    },
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.statusIsError) {
                            PillScopeColors.ModerateAmberDark
                        } else {
                            PillScopeColors.ClinicalTealDeep
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            if (uiState.lastDuplicateAlerts.isNotEmpty() || uiState.lastAllergyAlerts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CriticalCrimson,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Safety override triggered",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        uiState.lastAllergyAlerts.forEach { allergy ->
                            Text(
                                text = "• Allergy (${allergy.allergen}): ${allergy.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                        uiState.lastDuplicateAlerts.forEach { dup ->
                            Text(
                                text = "• Duplicate salt ${dup.canonicalSalt} already active in ${dup.existingDisplayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            if (uiState.displayMode == CabinetDisplayMode.NETWORK) {
                CabinetNetworkGraph(
                    graphState = graphState,
                    onSelectNode = viewModel::selectGraphNode,
                    onSelectEdge = viewModel::selectGraphEdge,
                    onMoveNode = viewModel::moveGraphNode,
                    onInspectMedication = viewModel::openMedicationDetail,
                )
            } else if (medications.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Text(
                        text = "This profile has no saved medications yet. Tap + to add active prescriptions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

    if (showAddDialog) {
        AddMedicationDialog(
            suggestions = uiState.catalogSuggestions,
            isSearching = uiState.isSearchingCatalog,
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
            title = { Text("Remove medication?") },
            text = {
                Text("Remove ${med.displayName} from this profile's medication cabinet?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMedication(med.id)
                        pendingDeleteMed = null
                    },
                ) {
                    Text("Remove", color = PillScopeColors.CriticalCrimson)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMed = null }) {
                    Text("Cancel")
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            )
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(
            width = if (medication.hasActiveRecall) 1.5.dp else 1.dp,
            color = if (medication.hasActiveRecall) {
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = medication.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    if (medication.hasActiveRecall) {
                        Surface(
                            shape = CircleShape,
                            color = PillScopeColors.CriticalCrimsonSurface,
                        ) {
                            Text(
                                text = "FDA Recall",
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.CriticalCrimson,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = medication.canonicalSaltKeys.joinToString(" + "),
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
                            append(if (medication.isPendingSync) "Pending sync" else "Synced")
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
                    contentDescription = "Remove medication",
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
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
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
                    text = medication.canonicalSaltKeys.joinToString(" + "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (isLoading) {
                LoadingIndicator(modifier = Modifier.size(22.dp), color = PillScopeColors.ClinicalTeal)
            }
        }

        medication.dosageSchedule?.takeIf { it.isNotBlank() }?.let { sched ->
            Text(
                text = "Dosage schedule: $sched",
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
                            text = "${recall.alertLabel} • ${recall.classification} (${recall.status})",
                            style = MaterialTheme.typography.labelMedium,
                            color = PillScopeColors.CriticalCrimsonDark,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Firm: ${recall.recallingFirm}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PillScopeColors.InkPrimary,
                        )
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
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.clickable {
                                    runCatching { uriHandler.openUri(recall.sourceUrl) }
                                },
                            ) {
                                Text(
                                    text = "View FDA enforcement notice",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    color = PillScopeColors.MinorBlue,
                                )
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = PillScopeColors.MinorBlue,
                                    modifier = Modifier.size(14.dp),
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
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddMedicationDialog(
    suggestions: List<BrandCandidateDto>,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (displayName: String, rawSaltsCsv: String, dosageSchedule: String?, brandMappingId: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var salts by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var selectedBrandId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to cabinet") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        selectedBrandId = null
                        onQueryChanged(it)
                    },
                    label = { Text("Medicine brand or name") },
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
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            name = candidate.brandName
                                            salts = candidate.canonicalSaltKeys.joinToString(", ")
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
                                        text = candidate.canonicalSaltKeys.joinToString(" + "),
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
                    label = { Text("Generic salts (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text("Dosage (e.g. 1 tab twice daily)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                enabled = name.isNotBlank() && salts.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
