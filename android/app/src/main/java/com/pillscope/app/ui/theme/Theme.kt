package com.pillscope.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PillScopeColors {
    val ClinicalTeal = Color(0xFF0B5D5B)
    val ClinicalTealDeep = Color(0xFF063B3A)
    val ClinicalTealContainer = Color(0xFFD4F3EE)
    val SlateSurface = Color(0xFFF4F7F7)
    val CardSurface = Color(0xFFFFFFFF)
    val SurfaceTint = Color(0xFFEDF3F3)
    val SlateBorder = Color(0xFFD8E3E4)
    val InkPrimary = Color(0xFF0F1E22)
    val InkSecondary = Color(0xFF4A6066)

    val CriticalCrimson = Color(0xFFC61D2C)
    val CriticalCrimsonDark = Color(0xFF7A0D18)
    val CriticalCrimsonSurface = Color(0xFFFDECEE)

    val ModerateAmber = Color(0xFFC46A08)
    val ModerateAmberDark = Color(0xFF7C3F00)
    val ModerateAmberSurface = Color(0xFFFEF4E4)

    val MinorBlue = Color(0xFF1D4ED8)
    val MinorBlueSurface = Color(0xFFEFF6FF)

    val SafeEmerald = Color(0xFF127A4B)
    val SafeEmeraldDark = Color(0xFF0A4B2E)
    val SafeEmeraldSurface = Color(0xFFE6F7EE)

    val HudBackdrop = Color(0xFF071316)
    val HudAmberGlow = Color(0xFFF59E0B)
    val HudEmeraldLock = Color(0xFF10B981)
}

private val ClinicalLightColorScheme = lightColorScheme(
    primary = PillScopeColors.ClinicalTeal,
    onPrimary = Color.White,
    primaryContainer = PillScopeColors.ClinicalTealContainer,
    onPrimaryContainer = PillScopeColors.ClinicalTealDeep,
    secondary = Color(0xFF28474E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBEE),
    onSecondaryContainer = Color(0xFF132E34),
    tertiary = PillScopeColors.SafeEmerald,
    onTertiary = Color.White,
    tertiaryContainer = PillScopeColors.SafeEmeraldSurface,
    onTertiaryContainer = PillScopeColors.SafeEmeraldDark,
    background = PillScopeColors.SlateSurface,
    onBackground = PillScopeColors.InkPrimary,
    surface = PillScopeColors.CardSurface,
    onSurface = PillScopeColors.InkPrimary,
    surfaceVariant = PillScopeColors.SurfaceTint,
    onSurfaceVariant = PillScopeColors.InkSecondary,
    error = PillScopeColors.CriticalCrimson,
    onError = Color.White,
    errorContainer = PillScopeColors.CriticalCrimsonSurface,
    onErrorContainer = PillScopeColors.CriticalCrimsonDark,
    outline = PillScopeColors.SlateBorder,
    outlineVariant = Color(0xFFE6EEEE),
)

private val ExpressiveClinicalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val ClinicalTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = "tnum",
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PillScopeTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = ClinicalLightColorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveClinicalShapes,
        typography = ClinicalTypography,
        content = content,
    )
}
