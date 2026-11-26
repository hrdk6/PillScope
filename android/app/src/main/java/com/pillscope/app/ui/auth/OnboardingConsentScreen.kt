package com.pillscope.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pillscope.app.BuildConfig
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.PillScopeColors

@Composable
fun OnboardingConsentScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openUri = rememberSafeUriOpener()
    var acknowledgedDisclaimer by rememberSaveable { mutableStateOf(false) }
    var acknowledgedDataProcessing by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = PillScopeColors.SlateSurface,
        bottomBar = {
            Surface(
                color = PillScopeColors.CardSurface,
                shadowElevation = 6.dp,
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
                            label = "I understand PillScope is informational only and not medical advice.",
                        )
                        ConsentCheckboxRow(
                            checked = acknowledgedDataProcessing,
                            onCheckedChange = { acknowledgedDataProcessing = it },
                            label = "I consent to medication & allergy safety processing as described in the Privacy Policy.",
                        )
                        Button(
                            onClick = onAccept,
                            enabled = acknowledgedDisclaimer && acknowledgedDataProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .height(52.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                text = "Continue to Sign In",
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
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = PillScopeColors.ClinicalTealContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = PillScopeColors.ClinicalTealDeep,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Column {
                    Text(
                        text = "Welcome to PillScope",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = "Review safety & privacy terms before your first scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            DisclosureCard(
                icon = Icons.Default.MedicalInformation,
                title = "Medical disclaimer",
                points = listOf(
                    "Informational medication safety aid — not a substitute for a doctor or pharmacist.",
                    "Never start, stop, or change prescriptions based solely on app results.",
                ),
            )

            DisclosureCard(
                icon = Icons.Default.PrivacyTip,
                title = "On-device camera & safety checks",
                points = listOf(
                    "Camera frames stay on your device; label text is read locally.",
                    "When you run a check, active ingredients, cabinet salts, and allergy alerts are processed by our backend and clinical AI providers to cite official FDA labels.",
                ),
            )

            DisclosureCard(
                icon = Icons.Default.Shield,
                title = "Your privacy controls",
                points = listOf(
                    "Credentials are encrypted via Android Keystore and sent over HTTPS.",
                    "Delete your account and all family profiles at any time from the Profile menu.",
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { openUri(BuildConfig.PRIVACY_POLICY_URL) },
                ) {
                    Text("Privacy Policy")
                }
                TextButton(
                    onClick = { openUri(BuildConfig.ACCOUNT_DELETION_URL) },
                ) {
                    Text("Data Deletion Info")
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = PillScopeColors.InkPrimary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun DisclosureCard(
    icon: ImageVector,
    title: String,
    points: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PillScopeColors.ClinicalTeal,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            points.forEach { point ->
                Text(
                    text = "• $point",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}
