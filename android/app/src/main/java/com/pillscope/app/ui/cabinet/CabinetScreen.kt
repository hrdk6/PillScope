package com.pillscope.app.ui.cabinet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    }
                }
            }

            uiState.statusBanner?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PillScopeColors.ClinicalTealContainer,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTealDeep,
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
                            onToggleActive = { active -> viewModel.toggleActive(med.id, active) },
                            onDelete = { viewModel.deleteMedication(med.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMedicationDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, salts, schedule ->
                viewModel.addMedication(name, salts, schedule)
                showAddDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CabinetMedicationCard(
    medication: CabinetMedication,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
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

@Composable
private fun AddMedicationDialog(
    onDismiss: () -> Unit,
    onConfirm: (displayName: String, rawSaltsCsv: String, dosageSchedule: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var salts by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to cabinet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medicine name (e.g., Warf 5 Tablet)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = salts,
                    onValueChange = { salts = it },
                    label = { Text("Generic salts (comma separated)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text("Dosage schedule (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, salts, schedule.takeIf { it.isNotBlank() }) },
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
