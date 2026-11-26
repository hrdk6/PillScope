package com.pillscope.app.ui.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pillscope.app.BuildConfig
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled

private val CommonAllergenPresets = listOf("Penicillin", "NSAIDs", "Sulfa", "Aspirin", "Cephalosporins")
private val RelationshipOptions = listOf("self" to "Self", "parent" to "Parent", "spouse" to "Spouse", "child" to "Child", "other" to "Other")

private fun parseAllergenInput(raw: String): List<String> = raw
    .split(",", ";")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun formatProfileSubtitle(profile: UserProfile): String {
    val relLabel = profile.relationship.replaceFirstChar { it.uppercase() }
    val showRelationship = !relLabel.equals(profile.name.trim(), ignoreCase = true)
    val allergySummary = if (profile.allergies.isNotEmpty()) {
        "Allergies: ${profile.allergies.joinToString(", ")}"
    } else {
        null
    }
    return when {
        showRelationship && allergySummary != null -> "$relLabel, $allergySummary"
        showRelationship -> relLabel
        allergySummary != null -> allergySummary
        else -> "No drug allergies listed"
    }
}

@Composable
fun ActiveProfilePill(
    profile: UserProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
) {
    val containerColor = if (darkSurface) {
        Color(0xAA101B26)
    } else {
        PillScopeColors.CardSurface
    }
    val contentColor = if (darkSurface) Color(0xFFF0F5FA) else PillScopeColors.InkPrimary
    val borderColor = if (darkSurface) {
        Color.White.copy(alpha = 0.18f)
    } else {
        PillScopeColors.SlateBorder
    }

    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
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
                val count = profile.allergies.size
                Surface(
                    shape = CircleShape,
                    color = PillScopeColors.CriticalCrimsonSurface,
                ) {
                    Text(
                        text = "$count ${if (count == 1) "allergy" else "allergies"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.CriticalCrimsonDark,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    activeProfile: UserProfile,
    userEmail: String?,
    offlineSession: Boolean,
    isOfflineGuest: Boolean = false,
    hasPendingOfflineChanges: Boolean = false,
    statusMessage: String?,
    errorMessage: String?,
    accountActionError: String?,
    isDeletingAccount: Boolean,
    onOpenLanguagePicker: () -> Unit = {},
    onSelectProfile: (String) -> Unit,
    onCreateProfile: (name: String, relationship: String, allergies: List<String>) -> Unit,
    onEditProfile: (profileId: String, name: String, relationship: String, setAsDefault: Boolean?) -> Unit,
    onSaveAllergies: (profileId: String, allergies: List<String>) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSignInFromOfflineGuest: () -> Unit = {},
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val reducedMotion = rememberReducedMotionEnabled()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val openUri = rememberSafeUriOpener()

    var editableAllergies by remember(activeProfile.id, activeProfile.allergies) {
        mutableStateOf(activeProfile.allergies)
    }
    var customAllergen by remember(activeProfile.id) { mutableStateOf("") }
    var showNewProfileForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newRelationship by remember { mutableStateOf("parent") }
    var newAllergiesInput by remember { mutableStateOf("") }

    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var editName by remember { mutableStateOf("") }
    var editRelationship by remember { mutableStateOf("self") }
    var editMakeDefault by remember { mutableStateOf(false) }

    var pendingDeleteProfile by remember { mutableStateOf<UserProfile?>(null) }
    var pendingUnsavedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var deleteAccountConfirmText by remember { mutableStateOf("") }

    val hasUnsavedAllergyChanges = remember(editableAllergies, customAllergen, activeProfile.allergies) {
        val pendingEntries = parseAllergenInput(customAllergen)
        val merged = (editableAllergies + pendingEntries).distinctBy { it.lowercase() }
        merged.map { it.lowercase() } != activeProfile.allergies.map { it.lowercase() }
    }

    val commitAllergenInput = {
        val additions = parseAllergenInput(customAllergen)
        if (additions.isNotEmpty()) {
            editableAllergies = (editableAllergies + additions).distinctBy { it.lowercase() }
            customAllergen = ""
        }
    }

    val commitSaveAllergies = {
        val pendingEntries = parseAllergenInput(customAllergen)
        val finalAllergies = (editableAllergies + pendingEntries)
            .distinctBy { it.lowercase() }
        editableAllergies = finalAllergies
        customAllergen = ""
        onSaveAllergies(activeProfile.id, finalAllergies)
    }

    val guardUnsavedAllergies: (() -> Unit) -> Unit = { action ->
        if (hasUnsavedAllergyChanges) {
            pendingUnsavedAction = action
        } else {
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { guardUnsavedAllergies(onDismiss) },
        sheetState = sheetState,
        containerColor = PillScopeColors.CardSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .animateContentSize(PillScopeMotion.settleSpring())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = strings.familyProfilesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = strings.familyProfilesSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }

            Surface(
                onClick = onOpenLanguagePicker,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = PillScopeColors.ClinicalTealContainer.copy(alpha = 0.48f),
                border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = PillScopeColors.ClinicalTealDeep,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = strings.chooseLanguageTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = PillScopeColors.ClinicalTealDeep,
                            )
                            Text(
                                text = "${language.nativeName} (${language.englishName})",
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.ClinicalTeal,
                            )
                        }
                    }
                    Text(
                        text = strings.languageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ClinicalTealDeep,
                    )
                }
            }

            AnimatedVisibility(
                visible = !statusMessage.isNullOrBlank(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.SafeEmeraldSurface,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = PillScopeColors.SafeEmeraldDark,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            val combinedError = errorMessage ?: accountActionError
            AnimatedVisibility(
                visible = !combinedError.isNullOrBlank(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                combinedError?.takeIf { it.isNotBlank() }?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.CriticalCrimsonSurface,
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = PillScopeColors.CriticalCrimsonDark,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    val selected = profile.id == activeProfile.id
                    val isBeingEdited = editingProfile?.id == profile.id
                    val rowBg by animateColorAsState(
                        targetValue = if (selected) {
                            PillScopeColors.ClinicalTealContainer.copy(alpha = 0.55f)
                        } else {
                            PillScopeColors.SlateSurface
                        },
                        animationSpec = tween(PillScopeMotion.FAST_MS),
                        label = "profileRowBg",
                    )
                    val rowBorder by animateColorAsState(
                        targetValue = if (selected) PillScopeColors.ClinicalTeal else PillScopeColors.SlateBorder,
                        animationSpec = tween(PillScopeMotion.FAST_MS),
                        label = "profileRowBorder",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                if (profile.id != activeProfile.id) {
                                    guardUnsavedAllergies { onSelectProfile(profile.id) }
                                } else {
                                    guardUnsavedAllergies(onDismiss)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = rowBg,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = rowBorder,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = PillScopeColors.InkPrimary,
                                        )
                                        if (profile.isDefault) {
                                            Surface(
                                                shape = CircleShape,
                                                color = PillScopeColors.SurfaceTint,
                                            ) {
                                                Text(
                                                    text = "Default",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = PillScopeColors.InkSecondary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = formatProfileSubtitle(profile),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PillScopeColors.InkSecondary,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (isBeingEdited) {
                                                editingProfile = null
                                            } else {
                                                editingProfile = profile
                                                editName = profile.name
                                                editRelationship = profile.relationship
                                                editMakeDefault = profile.isDefault
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit ${profile.name}",
                                            tint = if (isBeingEdited) PillScopeColors.ClinicalTeal else PillScopeColors.InkSecondary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (!profile.isDefault) {
                                        IconButton(onClick = { pendingDeleteProfile = profile }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete ${profile.name}",
                                                tint = PillScopeColors.CriticalCrimson,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
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

                        AnimatedVisibility(
                            visible = isBeingEdited,
                            enter = PillScopeMotion.panelEnter(reducedMotion),
                            exit = PillScopeMotion.panelExit(reducedMotion),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PillScopeColors.SlateSurface,
                                border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.4f)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Edit ${profile.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = PillScopeColors.InkPrimary,
                                    )
                                    OutlinedTextField(
                                        value = editName,
                                        onValueChange = { editName = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("Profile name") },
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        RelationshipOptions.forEach { (key, label) ->
                                            FilterChip(
                                                selected = editRelationship == key,
                                                onClick = { editRelationship = key },
                                                shape = RoundedCornerShape(10.dp),
                                                label = { Text(label) },
                                            )
                                        }
                                    }
                                    if (!profile.isDefault) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .toggleable(
                                                    value = editMakeDefault,
                                                    role = Role.Checkbox,
                                                    onValueChange = { editMakeDefault = it },
                                                )
                                                .padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = editMakeDefault,
                                                onCheckedChange = null,
                                            )
                                            Text(
                                                text = "Set as default profile",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = PillScopeColors.InkPrimary,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = { editingProfile = null },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text("Cancel")
                                        }
                                        Button(
                                            onClick = {
                                                onEditProfile(
                                                    profile.id,
                                                    editName.trim(),
                                                    editRelationship,
                                                    if (!profile.isDefault && editMakeDefault) true else null,
                                                )
                                                editingProfile = null
                                            },
                                            enabled = editName.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text("Save changes")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = PillScopeColors.CriticalCrimson,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "${strings.drugAllergiesTitle} (${activeProfile.name})",
                            style = MaterialTheme.typography.titleMedium,
                            color = PillScopeColors.InkPrimary,
                        )
                    }
                    AnimatedVisibility(
                        visible = hasUnsavedAllergyChanges,
                        enter = PillScopeMotion.badgeEnter(reducedMotion),
                        exit = PillScopeMotion.badgeExit(reducedMotion),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = PillScopeColors.ModerateAmberSurface,
                        ) {
                            Text(
                                text = strings.unsavedBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = PillScopeColors.ModerateAmberDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
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
                                shape = CircleShape,
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
                                    selectedTrailingIconColor = PillScopeColors.CriticalCrimsonDark,
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
                            shape = RoundedCornerShape(10.dp),
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
                        label = { Text("Add allergen (comma separated)") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitAllergenInput() }),
                        shape = RoundedCornerShape(12.dp),
                    )
                    IconButton(
                        onClick = commitAllergenInput,
                        enabled = customAllergen.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add allergen",
                            tint = if (customAllergen.isNotBlank()) {
                                PillScopeColors.ClinicalTeal
                            } else {
                                PillScopeColors.InkSecondary.copy(alpha = 0.38f)
                            },
                        )
                    }
                }

                Button(
                    onClick = commitSaveAllergies,
                    enabled = hasUnsavedAllergyChanges,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (hasUnsavedAllergyChanges) strings.saveAllergyChanges else strings.allergiesUpToDate)
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            AnimatedVisibility(
                visible = !showNewProfileForm,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                OutlinedButton(
                    onClick = { showNewProfileForm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(strings.addFamilyProfile)
                }
            }

            AnimatedVisibility(
                visible = showNewProfileForm,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.SlateSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                            shape = RoundedCornerShape(12.dp),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RelationshipOptions.forEach { (key, label) ->
                                FilterChip(
                                    selected = newRelationship == key,
                                    onClick = { newRelationship = key },
                                    shape = RoundedCornerShape(10.dp),
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
                            shape = RoundedCornerShape(12.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showNewProfileForm = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val parsedAllergies = parseAllergenInput(newAllergiesInput)
                                    onCreateProfile(newName.trim(), newRelationship, parsedAllergies)
                                    newName = ""
                                    newAllergiesInput = ""
                                    showNewProfileForm = false
                                },
                                enabled = newName.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Create profile")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.accountAndPrivacy,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
                if (isOfflineGuest) {
                    Text(
                        text = "Using on-device offline mode. Sign in to back up family profiles and run full interaction checks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                } else {
                    userEmail?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = buildString {
                                append("Signed in as $email")
                                if (offlineSession) append(" (offline mode)")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkSecondary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUri(BuildConfig.PRIVACY_POLICY_URL) }) {
                        Text("Privacy Policy", color = PillScopeColors.ClinicalTeal)
                    }
                    TextButton(onClick = { openUri(BuildConfig.ACCOUNT_DELETION_URL) }) {
                        Text("Data Deletion Policy", color = PillScopeColors.ClinicalTeal)
                    }
                }

                if (isOfflineGuest) {
                    Button(
                        onClick = onSignInFromOfflineGuest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Sign in or create account")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showSignOutConfirmDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(strings.signOut)
                        }
                        Button(
                            onClick = {
                                deleteAccountConfirmText = ""
                                showDeleteAccountDialog = true
                            },
                            enabled = !isDeletingAccount,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PillScopeColors.CriticalBannerContainer,
                                contentColor = PillScopeColors.OnCriticalBanner,
                            ),
                        ) {
                            if (isDeletingAccount) {
                                LoadingIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = PillScopeColors.OnCriticalBanner,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(strings.deleteAccount)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    pendingUnsavedAction?.let { nextAction ->
        AlertDialog(
            onDismissRequest = { pendingUnsavedAction = null },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Save allergy changes?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text("You have unsaved drug allergy edits for ${activeProfile.name}. Save them before continuing?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        commitSaveAllergies()
                        pendingUnsavedAction = null
                        nextAction()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editableAllergies = activeProfile.allergies
                        customAllergen = ""
                        pendingUnsavedAction = null
                        nextAction()
                    },
                ) {
                    Text("Discard")
                }
            },
        )
    }

    pendingDeleteProfile?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Delete profile?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Delete ${target.name} and all medications, schedules, and scan history saved under this profile? This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(target.id)
                        pendingDeleteProfile = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = PillScopeColors.CriticalCrimson,
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showSignOutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmDialog = false },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Sign out of PillScope?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    if (hasPendingOfflineChanges) {
                        "Warning: You have unsynced offline medication or profile changes on this device. " +
                            "Signing out now will permanently clear local offline data before it is uploaded. " +
                            "Connect online and sync first, or sign out anyway?"
                    } else {
                        "Signing out clears cached medication data from this device. Your synced records remain safely stored in your account."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirmDialog = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (hasPendingOfflineChanges) {
                            PillScopeColors.CriticalCrimson
                        } else {
                            PillScopeColors.ClinicalTeal
                        },
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(if (hasPendingOfflineChanges) "Sign out and discard" else "Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Permanently delete account?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This permanently deletes your PillScope account, all family profiles, medications, " +
                            "allergies, and scan history from our servers and this device. Type DELETE to confirm.",
                    )
                    OutlinedTextField(
                        value = deleteAccountConfirmText,
                        onValueChange = { deleteAccountConfirmText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Type DELETE") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccount()
                    },
                    enabled = deleteAccountConfirmText.trim().equals("DELETE", ignoreCase = true),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = PillScopeColors.CriticalCrimson,
                        disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.38f),
                    ),
                ) {
                    Text("Delete forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
