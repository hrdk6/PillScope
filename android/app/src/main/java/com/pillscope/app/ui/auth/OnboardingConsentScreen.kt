package com.pillscope.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pillscope.app.BuildConfig
import com.pillscope.app.ui.theme.PillScopeColors

@Composable
fun OnboardingConsentScreen(
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var acknowledgedDisclaimer by remember { mutableStateOf(false) }
    var acknowledgedDataProcessing by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = PillScopeColors.SlateSurface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        text = "Before you scan, please review safety & privacy terms.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            DisclosureCard(
                icon = Icons.Default.MedicalInformation,
                title = "Medical Disclaimer",
                body = "PillScope is an informational medication safety aid. It does not replace " +
                    "a licensed physician or pharmacist, cannot detect every possible drug interaction, " +
                    "and should never be used to start, stop, or change prescription therapy without professional advice.",
            )

            DisclosureCard(
                icon = Icons.Default.PrivacyTip,
                title = "Health Data & Third-Party AI Processing",
                body = "Camera frames are processed on-device via OCR and are not uploaded. " +
                    "When you run a safety check, your medicine query, resolved active salts, " +
                    "active cabinet ingredients, and triggered allergy or interaction alerts may be " +
                    "processed by our backend and third-party clinical AI providers (such as Google Gemini " +
                    "or OpenAI) to synthesize monograph-cited explanations.",
            )

            DisclosureCard(
                icon = Icons.Default.Shield,
                title = "Your Data Controls",
                body = "Your credentials are stored encrypted using Android Keystore, all cloud traffic " +
                    "uses HTTPS encryption in release builds, and you can permanently delete your account " +
                    "and all health profiles at any time from the Profile menu.",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledgedDisclaimer,
                    onCheckedChange = { acknowledgedDisclaimer = it },
                )
                Text(
                    text = "I understand PillScope is informational only and not medical advice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledgedDataProcessing,
                    onCheckedChange = { acknowledgedDataProcessing = it },
                )
                Text(
                    text = "I consent to medication & allergy safety processing as described in the Privacy Policy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) } },
                ) {
                    Text("Privacy Policy")
                }
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(BuildConfig.ACCOUNT_DELETION_URL) } },
                ) {
                    Text("Data Deletion Info")
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onAccept,
                enabled = acknowledgedDisclaimer && acknowledgedDataProcessing,
                modifier = Modifier
                    .fillMaxWidth()
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

@Composable
private fun DisclosureCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkSecondary,
            )
        }
    }
}
