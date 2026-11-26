package com.pillscope.app.ui.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.ui.theme.PillScopeColors

private val CommonAllergenPresets = listOf("Penicillin", "NSAIDs", "Sulfa", "Aspirin", "Cephalosporins")
private val RelationshipOptions = listOf("self" to "Self", "parent" to "Parent", "spouse" to "Spouse", "child" to "Child", "other" to "Other")

@Composable
fun ActiveProfilePill(
    profile: UserProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
) {
    val containerColor = if (darkSurface) {
        Color.Black.copy(alpha = 0.62f)
    } else {
        PillScopeColors.CardSurface
    }
    val contentColor = if (darkSurface) Color.White else PillScopeColors.InkPrimary
    val borderColor = if (darkSurface) {
        Color.White.copy(alpha = 0.24f)
    } else {
        PillScopeColors.SlateBorder
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = if (darkSurface) PillScopeColors.HudEmeraldLock else PillScopeColors.ClinicalTeal,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = profile.name,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            if (profile.allergies.isNotEmpty()) {
                Surface(
                    shape = CircleShape,
                    color = PillScopeColors.CriticalCrimsonSurface,
                ) {
                    Text(
                        text = "${profile.allergies.size} allergy",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.CriticalCrimson,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Switch profile",
                tint = contentColor.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    activeProfile: UserProfile,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: (name: String, relationship: String, allergies: List<String>) -> Unit,
    onSaveAllergies: (profileId: String, allergies: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editableAllergies by remember(activeProfile.id, activeProfile.allergies) {
        mutableStateOf(activeProfile.allergies)
    }
    var customAllergen by remember(activeProfile.id) { mutableStateOf("") }
    var showNewProfileForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newRelationship by remember { mutableStateOf("parent") }
    var newAllergiesInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PillScopeColors.CardSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Family profiles",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = "Cabinets, schedules, and allergies stay isolated per person.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    val selected = profile.id == activeProfile.id
                    Surface(
                        onClick = { onSelectProfile(profile.id) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) {
                            PillScopeColors.ClinicalTealContainer.copy(alpha = 0.55f)
                        } else {
                            PillScopeColors.SlateSurface
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) PillScopeColors.ClinicalTeal else PillScopeColors.SlateBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PillScopeColors.InkPrimary,
                                )
                                Text(
                                    text = buildString {
                                        append(profile.relationship.replaceFirstChar { it.uppercase() })
                                        if (profile.allergies.isNotEmpty()) {
                                            append(", ")
                                            append(profile.allergies.joinToString(", "))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PillScopeColors.InkSecondary,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active profile",
                                    tint = PillScopeColors.ClinicalTeal,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = PillScopeColors.CriticalCrimson,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Drug allergies for ${activeProfile.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                }

                if (editableAllergies.isEmpty()) {
                    Text(
                        text = "No drug allergies listed. Add classes like Penicillin or NSAIDs for automatic red alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        editableAllergies.forEach { allergen ->
                            InputChip(
                                selected = true,
                                onClick = { editableAllergies = editableAllergies - allergen },
                                label = { Text(allergen) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $allergen",
                                        modifier = Modifier.size(15.dp),
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = PillScopeColors.CriticalCrimsonSurface,
                                    selectedLabelColor = PillScopeColors.CriticalCrimsonDark,
                                    selectedTrailingIconColor = PillScopeColors.CriticalCrimson,
                                ),
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CommonAllergenPresets.filter { preset ->
                        editableAllergies.none { it.equals(preset, ignoreCase = true) }
                    }.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { editableAllergies = editableAllergies + preset },
                            label = { Text("+ $preset") },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customAllergen,
                        onValueChange = { customAllergen = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Add allergen or drug class") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = customAllergen.trim()
                            if (trimmed.isNotEmpty() && editableAllergies.none { it.equals(trimmed, ignoreCase = true) }) {
                                editableAllergies = editableAllergies + trimmed
                                customAllergen = ""
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add allergen",
                            tint = PillScopeColors.ClinicalTeal,
                        )
                    }
                }

                Button(
                    onClick = {
                        onSaveAllergies(activeProfile.id, editableAllergies)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Save allergies")
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            if (!showNewProfileForm) {
                OutlinedButton(
                    onClick = { showNewProfileForm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add family profile")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "New profile",
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name (e.g., Mom, Dad)") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RelationshipOptions.forEach { (key, label) ->
                            FilterChip(
                                selected = newRelationship == key,
                                onClick = { newRelationship = key },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newAllergiesInput,
                        onValueChange = { newAllergiesInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Initial allergies (comma separated)") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showNewProfileForm = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                val parsedAllergies = newAllergiesInput
                                    .split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                onCreateProfile(newName.trim(), newRelationship, parsedAllergies)
                                newName = ""
                                newAllergiesInput = ""
                                showNewProfileForm = false
                            },
                            enabled = newName.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Create profile")
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}
