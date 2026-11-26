package com.pillscope.app.ui.common

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import com.pillscope.app.ui.theme.PillScopeColors

private val StrengthSuffixRegex = Regex(
    """(?:\s*\(\s*(?<pval>\d+(?:\.\d+)?)\s*(?<punit>mg|mcg|ug|g|gm|ml|iu|%)\s*\)|\s+(?<val>\d+(?:\.\d+)?)\s*(?<unit>mg|mcg|ug|g|gm|ml|iu|%))\s*$""",
    RegexOption.IGNORE_CASE,
)

val DosageSchedulePresets: List<String> = listOf(
    "Once daily",
    "Twice daily",
    "Morning",
    "Bedtime",
    "With food",
    "Empty stomach",
)

@Composable
fun rememberSafeUriOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    return remember(uriHandler, context) {
        { url: String ->
            val trimmed = url.trim()
            if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                runCatching { uriHandler.openUri(trimmed) }.onFailure {
                    Toast.makeText(
                        context,
                        "Unable to open link. Check that a web browser is installed.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    "This reference link is not a valid web address.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/**
 * Parses comma- or plus-separated generic salt entries entered by a user, separating optional
 * embedded strength tokens (e.g. "acetaminophen 650mg" or "Paracetamol (500 mg)") into
 * structured strength metadata while keeping `canonicalSalt` clean for DDI/allergy matching.
 */
fun normalizeSaltInput(rawSaltsCsv: String): List<SaltComponentDto> {
    if (rawSaltsCsv.isBlank()) return emptyList()
    val seen = linkedSetOf<String>()
    val parsed = mutableListOf<SaltComponentDto>()
    for (segment in rawSaltsCsv.split(",", "+")) {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) continue
        val strengthMatch = StrengthSuffixRegex.find(trimmed)
        val rawName = if (strengthMatch != null) {
            trimmed.substring(0, strengthMatch.range.first).trim()
        } else {
            trimmed
        }
        val valueStr = strengthMatch?.groups?.get("pval")?.value ?: strengthMatch?.groups?.get("val")?.value
        val unitStr = (strengthMatch?.groups?.get("punit")?.value ?: strengthMatch?.groups?.get("unit")?.value)
            ?.lowercase()
        val strengthText = if (valueStr != null && unitStr != null) "$valueStr$unitStr" else null
        val strengthMg = if (valueStr != null && unitStr == "mg") valueStr.toDoubleOrNull() else null

        val canonical = OnDeviceSaltChipExtractor.normalizeCanonicalSalt(rawName)
            .ifEmpty { rawName.lowercase() }
        if (canonical.isEmpty() || !seen.add(canonical)) continue
        parsed.add(
            SaltComponentDto(
                canonicalSalt = canonical,
                strengthMg = strengthMg,
                strengthText = strengthText,
            ),
        )
    }
    return parsed
}

fun formatSaltComponents(
    genericSalts: List<SaltComponentDto>,
    fallbackKeys: List<String> = emptyList(),
): String {
    if (genericSalts.isNotEmpty()) {
        val parts = genericSalts.mapNotNull { salt ->
            val name = salt.canonicalSalt.trim()
            if (name.isEmpty()) return@mapNotNull null
            val strength = salt.strengthText?.trim()?.takeIf { it.isNotEmpty() }
                ?: salt.strengthMg?.let { mg ->
                    if (mg % 1.0 == 0.0) "${mg.toInt()}mg" else "${mg}mg"
                }
            if (strength != null) "$name $strength" else name
        }
        if (parts.isNotEmpty()) return parts.joinToString(" + ")
    }
    return fallbackKeys.joinToString(" + ")
}

fun formatMonographLinkLabel(url: String): String {
    val lowered = url.lowercase()
    return when {
        "dailymed.nlm.nih.gov" in lowered -> "Read official label on DailyMed"
        "fda.gov" in lowered -> "View official FDA source"
        "nih.gov" in lowered || "ncbi.nlm.nih.gov" in lowered -> "Read NIH drug reference"
        else -> "Read official source"
    }
}

fun formatInteractionSourceLabel(rawSource: String): String = when (rawSource.trim().lowercase()) {
    "deterministic_db", "curated_ddi" -> "Verified clinical interaction rule"
    "ddinter", "ddinter_2" -> "Clinical drug interaction database"
    "hybrid" -> "Clinical rule & risk prediction"
    "ml_rdkit", "ml_model", "rdkit_ml" -> "AI-predicted interaction risk"
    "safe_check" -> "No known interaction found"
    else -> "Clinical safety check"
}

@Composable
fun severityPillColors(risk: RiskLevel): Pair<Color, Color> = when (risk) {
    RiskLevel.MAJOR -> PillScopeColors.CriticalCrimsonSurface to PillScopeColors.CriticalCrimsonDark
    RiskLevel.MODERATE -> PillScopeColors.ModerateAmberSurface to PillScopeColors.ModerateAmberDark
    RiskLevel.MINOR -> PillScopeColors.MinorBlueSurface to PillScopeColors.MinorBlueDark
    RiskLevel.NONE -> PillScopeColors.SafeEmeraldSurface to PillScopeColors.SafeEmeraldDark
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DosagePresetChips(
    currentValue: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DosageSchedulePresets.forEach { preset ->
            val selected = currentValue.contains(preset, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = {
                    val trimmed = currentValue.trim()
                    val next = when {
                        selected -> {
                            trimmed.split(",")
                                .map { it.trim() }
                                .filterNot { it.equals(preset, ignoreCase = true) }
                                .joinToString(", ")
                        }
                        trimmed.isEmpty() -> preset
                        else -> "$trimmed, $preset"
                    }
                    onValueChange(next)
                },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PillScopeColors.ClinicalTealContainer,
                    selectedLabelColor = PillScopeColors.ClinicalTealDeep,
                ),
                label = { Text(preset) },
            )
        }
    }
}
