package com.pillscope.app.ui.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pillscope.app.BuildConfig
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.common.LanguagePillButton
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.OrchestratedReveal
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeEmblem
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.tactilePressScale

@Composable
fun OnboardingConsentScreen(
    onAccept: () -> Unit,
    onOpenLanguagePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val openUri = rememberSafeUriOpener()
    var acknowledgedDisclaimer by rememberSaveable { mutableStateOf(false) }
    var acknowledgedDataProcessing by rememberSaveable { mutableStateOf(false) }
    val bothAccepted = acknowledgedDisclaimer && acknowledgedDataProcessing
    val ctaInteraction = remember { MutableInteractionSource() }

    Scaffold(
        modifier = modifier,
        containerColor = PillScopeColors.SlateSurface,
        bottomBar = {
            Surface(
                color = PillScopeColors.CardSurface,
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    HorizontalDivider(color = PillScopeColors.SlateBorder)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConsentCheckboxRow(
                            checked = acknowledgedDisclaimer,
                            onCheckedChange = { acknowledgedDisclaimer = it },
                            label = strings.consentDisclaimerCheckbox,
                        )
                        ConsentCheckboxRow(
                            checked = acknowledgedDataProcessing,
                            onCheckedChange = { acknowledgedDataProcessing = it },
                            label = strings.consentDataCheckbox,
                        )
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = onAccept,
                            enabled = bothAccepted,
                            interactionSource = ctaInteraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .tactilePressScale(ctaInteraction, enabled = bothAccepted),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PillScopeColors.ClinicalTeal,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = PillScopeColors.SurfaceTint,
                                disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.55f),
                            ),
                        ) {
                            Text(
                                text = strings.continueToSignIn,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillScopeEmblem(modifier = Modifier.size(44.dp))
                LanguagePillButton(onClick = onOpenLanguagePicker)
            }

            OrchestratedReveal {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.welcomeTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = strings.welcomeSubtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            // Unified clinical disclosure ledger — structured editorial rows separated by hairlines
            // rather than three identical stacked cards.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = PillScopeColors.CardSurface,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DisclosureLedgerRow(
                        icon = Icons.Default.MedicalInformation,
                        iconContainer = PillScopeColors.CriticalCrimsonSurface,
                        iconTint = PillScopeColors.CriticalCrimson,
                        title = strings.medicalDisclaimerTitle,
                        points = listOf(
                            strings.medicalDisclaimerPoint1,
                            strings.medicalDisclaimerPoint2,
                        ),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = PillScopeColors.SlateBorder.copy(alpha = 0.7f),
                    )
                    DisclosureLedgerRow(
                        icon = Icons.Default.PrivacyTip,
                        iconContainer = PillScopeColors.ClinicalTealContainer,
                        iconTint = PillScopeColors.ClinicalTealDeep,
                        title = strings.onDeviceCameraTitle,
                        points = listOf(
                            strings.onDeviceCameraPoint1,
                            strings.onDeviceCameraPoint2,
                        ),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = PillScopeColors.SlateBorder.copy(alpha = 0.7f),
                    )
                    DisclosureLedgerRow(
                        icon = Icons.Default.Shield,
                        iconContainer = PillScopeColors.MinorBlueSurface,
                        iconTint = PillScopeColors.MinorBlue,
                        title = strings.privacyControlsTitle,
                        points = listOf(
                            strings.privacyControlsPoint1,
                            strings.privacyControlsPoint2,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { openUri(BuildConfig.PRIVACY_POLICY_URL) }) {
                    Text(
                        text = strings.privacyPolicy,
                        color = PillScopeColors.ClinicalTeal,
                    )
                }
                TextButton(onClick = { openUri(BuildConfig.ACCOUNT_DELETION_URL) }) {
                    Text(
                        text = strings.dataDeletionInfo,
                        color = PillScopeColors.ClinicalTeal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    val rowBg by animateColorAsState(
        targetValue = if (checked) {
            PillScopeColors.ClinicalTealContainer.copy(alpha = 0.48f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(PillScopeMotion.FAST_MS),
        label = "consentRowBg",
    )
    val rowBorder by animateColorAsState(
        targetValue = if (checked) {
            PillScopeColors.ClinicalTeal.copy(alpha = 0.35f)
        } else {
            PillScopeColors.SlateBorder.copy(alpha = 0.6f)
        },
        animationSpec = tween(PillScopeMotion.FAST_MS),
        label = "consentRowBorder",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        shape = RoundedCornerShape(12.dp),
        color = rowBg,
        border = BorderStroke(1.dp, rowBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = PillScopeColors.ClinicalTeal,
                    uncheckedColor = PillScopeColors.InkSecondary,
                ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkPrimary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun DisclosureLedgerRow(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    points: List<String>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = iconContainer,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            points.forEach { point ->
                Text(
                    text = point,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}
