package com.pillscope.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PillScopeColors {
    val ClinicalTeal = Color(0xFF0F766E)
    val ClinicalTealContainer = Color(0xFFCCFBF1)
    val SlateSurface = Color(0xFFF8FAFC)
    val CardSurface = Color(0xFFFFFFFF)
    val SlateBorder = Color(0xFFE2E8F0)
    val InkPrimary = Color(0xFF0F172A)
    val InkSecondary = Color(0xFF475569)

    val CriticalCrimson = Color(0xFFB91C1C)
    val CriticalCrimsonDark = Color(0xFF7F1D1D)
    val CriticalCrimsonSurface = Color(0xFFFEF2F2)

    val ModerateAmber = Color(0xFFB45309)
    val ModerateAmberSurface = Color(0xFFFFFBEB)

    val MinorBlue = Color(0xFF1D4ED8)
    val MinorBlueSurface = Color(0xFFEFF6FF)

    val SafeEmerald = Color(0xFF15803D)
    val SafeEmeraldSurface = Color(0xFFF0FDF4)
}

private val LightColors = lightColorScheme(
    primary = PillScopeColors.ClinicalTeal,
    onPrimary = Color.White,
    primaryContainer = PillScopeColors.ClinicalTealContainer,
    onPrimaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF334155),
    onSecondary = Color.White,
    background = PillScopeColors.SlateSurface,
    onBackground = PillScopeColors.InkPrimary,
    surface = PillScopeColors.CardSurface,
    onSurface = PillScopeColors.InkPrimary,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = PillScopeColors.InkSecondary,
    error = PillScopeColors.CriticalCrimson,
    onError = Color.White,
    errorContainer = PillScopeColors.CriticalCrimsonSurface,
    onErrorContainer = PillScopeColors.CriticalCrimsonDark,
    outline = PillScopeColors.SlateBorder,
)

private val ClinicalTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun PillScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = ClinicalTypography,
        content = content,
    )
}
