package com.pillscope.app.ui.cabinet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

@Composable
fun CabinetScreen(
    viewModel: CabinetViewModel,
    modifier: Modifier = Modifier,
) {
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = PillScopeColors.ClinicalTeal,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add medication")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Medication Cabinet",
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(
                    onClick = viewModel::syncCabinet,
                    enabled = !uiState.isSyncing,
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = "Sync cabinet")
                    }
                }
            }

            uiState.statusBanner?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PillScopeColors.ClinicalTealContainer,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTeal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            if (uiState.lastDuplicateAlerts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PillScopeColors.CriticalCrimson,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "DUPLICATE ACTIVE SALT DETECTED",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                        uiState.lastDuplicateAlerts.forEach { dup ->
                            Text(
                                text = "• ${dup.canonicalSalt} already active in ${dup.existingDisplayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            if (medications.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Text(
                        text = "Your offline medication cabinet is empty. Tap + to add active prescriptions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                        modifier = Modifier.padding(16.dp),
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

@Composable
private fun CabinetMedicationCard(
    medication: CabinetMedication,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = medication.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = medication.canonicalSaltKeys.joinToString(" + "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                )
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
                            medication.dosageSchedule?.let { append(" · $it") }
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
        title = { Text("Add to Medication Cabinet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medicine Name (e.g., Warf 5 Tablet)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = salts,
                    onValueChange = { salts = it },
                    label = { Text("Generic Salts (comma separated)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text("Dosage Schedule (optional)") },
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
