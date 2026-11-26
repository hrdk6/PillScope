package com.pillscope.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class PillScopePalette(
    val clinicalTeal: Color,
    val clinicalTealDeep: Color,
    val clinicalTealContainer: Color,
    val slateSurface: Color,
    val cardSurface: Color,
    val surfaceTint: Color,
    val slateBorder: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val criticalCrimson: Color,
    val criticalCrimsonDark: Color,
    val criticalCrimsonSurface: Color,
    val moderateAmber: Color,
    val moderateAmberDark: Color,
    val moderateAmberSurface: Color,
    val minorBlue: Color,
    val minorBlueSurface: Color,
    val safeEmerald: Color,
    val safeEmeraldDark: Color,
    val safeEmeraldSurface: Color,
    val hudBackdrop: Color,
    val hudAmberGlow: Color,
    val hudEmeraldLock: Color,
    val isDark: Boolean,
)

private val LightPalette = PillScopePalette(
    clinicalTeal = Color(0xFF0B5D5B),
    clinicalTealDeep = Color(0xFF063B3A),
    clinicalTealContainer = Color(0xFFD4F3EE),
    slateSurface = Color(0xFFF4F7F7),
    cardSurface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFFEDF3F3),
    slateBorder = Color(0xFFD8E3E4),
    inkPrimary = Color(0xFF0F1E22),
    inkSecondary = Color(0xFF4A6066),
    criticalCrimson = Color(0xFFC61D2C),
    criticalCrimsonDark = Color(0xFF7A0D18),
    criticalCrimsonSurface = Color(0xFFFDECEE),
    moderateAmber = Color(0xFFC46A08),
    moderateAmberDark = Color(0xFF7C3F00),
    moderateAmberSurface = Color(0xFFFEF4E4),
    minorBlue = Color(0xFF1D4ED8),
    minorBlueSurface = Color(0xFFEFF6FF),
    safeEmerald = Color(0xFF127A4B),
    safeEmeraldDark = Color(0xFF0A4B2E),
    safeEmeraldSurface = Color(0xFFE6F7EE),
    hudBackdrop = Color(0xFF071316),
    hudAmberGlow = Color(0xFFF59E0B),
    hudEmeraldLock = Color(0xFF10B981),
    isDark = false,
)

private val DarkPalette = PillScopePalette(
    clinicalTeal = Color(0xFF4FD1C5),
    clinicalTealDeep = Color(0xFFB2F5EA),
    clinicalTealContainer = Color(0xFF103E3D),
    slateSurface = Color(0xFF0C1417),
    cardSurface = Color(0xFF142024),
    surfaceTint = Color(0xFF1B2B30),
    slateBorder = Color(0xFF283D43),
    inkPrimary = Color(0xFFEBF4F5),
    inkSecondary = Color(0xFF9EB5BC),
    criticalCrimson = Color(0xFFEF4444),
    criticalCrimsonDark = Color(0xFFFECACA),
    criticalCrimsonSurface = Color(0xFF3F151A),
    moderateAmber = Color(0xFFF59E0B),
    moderateAmberDark = Color(0xFFFDE68A),
    moderateAmberSurface = Color(0xFF3B280D),
    minorBlue = Color(0xFF60A5FA),
    minorBlueSurface = Color(0xFF172554),
    safeEmerald = Color(0xFF34D399),
    safeEmeraldDark = Color(0xFFA7F3D0),
    safeEmeraldSurface = Color(0xFF0E3524),
    hudBackdrop = Color(0xFF050C0E),
    hudAmberGlow = Color(0xFFF59E0B),
    hudEmeraldLock = Color(0xFF10B981),
    isDark = true,
)

val LocalPillScopePalette = staticCompositionLocalOf { LightPalette }

/**
 * Adaptive semantic colors that automatically follow Light/Dark mode inside @Composable scopes,
 * while preserving static fallback values for non-composable callers.
 */
object PillScopeColors {
    val ClinicalTeal: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.clinicalTeal
    val ClinicalTealDeep: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.clinicalTealDeep
    val ClinicalTealContainer: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.clinicalTealContainer
    val SlateSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.slateSurface
    val CardSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.cardSurface
    val SurfaceTint: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.surfaceTint
    val SlateBorder: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.slateBorder
    val InkPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.inkPrimary
    val InkSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.inkSecondary

    val CriticalCrimson: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.criticalCrimson
    val CriticalCrimsonDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.criticalCrimsonDark
    val CriticalCrimsonSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.criticalCrimsonSurface

    val ModerateAmber: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmber
    val ModerateAmberDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmberDark
    val ModerateAmberSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmberSurface

    val MinorBlue: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.minorBlue
    val MinorBlueSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.minorBlueSurface

    val SafeEmerald: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmerald
    val SafeEmeraldDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmeraldDark
    val SafeEmeraldSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmeraldSurface

    val HudBackdrop: Color = Color(0xFF071316)
    val HudAmberGlow: Color = Color(0xFFF59E0B)
    val HudEmeraldLock: Color = Color(0xFF10B981)
}

private val ClinicalLightColorScheme = lightColorScheme(
    primary = LightPalette.clinicalTeal,
    onPrimary = Color.White,
    primaryContainer = LightPalette.clinicalTealContainer,
    onPrimaryContainer = LightPalette.clinicalTealDeep,
    secondary = Color(0xFF28474E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBEE),
    onSecondaryContainer = Color(0xFF132E34),
    tertiary = LightPalette.safeEmerald,
    onTertiary = Color.White,
    tertiaryContainer = LightPalette.safeEmeraldSurface,
    onTertiaryContainer = LightPalette.safeEmeraldDark,
    background = LightPalette.slateSurface,
    onBackground = LightPalette.inkPrimary,
    surface = LightPalette.cardSurface,
    onSurface = LightPalette.inkPrimary,
    surfaceVariant = LightPalette.surfaceTint,
    onSurfaceVariant = LightPalette.inkSecondary,
    error = LightPalette.criticalCrimson,
    onError = Color.White,
    errorContainer = LightPalette.criticalCrimsonSurface,
    onErrorContainer = LightPalette.criticalCrimsonDark,
    outline = LightPalette.slateBorder,
    outlineVariant = Color(0xFFE6EEEE),
)

private val ClinicalDarkColorScheme = darkColorScheme(
    primary = DarkPalette.clinicalTeal,
    onPrimary = Color(0xFF042625),
    primaryContainer = DarkPalette.clinicalTealContainer,
    onPrimaryContainer = DarkPalette.clinicalTealDeep,
    secondary = Color(0xFF93C5CC),
    onSecondary = Color(0xFF0A2429),
    secondaryContainer = Color(0xFF1D353A),
    onSecondaryContainer = Color(0xFFDCEBEE),
    tertiary = DarkPalette.safeEmerald,
    onTertiary = Color(0xFF052E1C),
    tertiaryContainer = DarkPalette.safeEmeraldSurface,
    onTertiaryContainer = DarkPalette.safeEmeraldDark,
    background = DarkPalette.slateSurface,
    onBackground = DarkPalette.inkPrimary,
    surface = DarkPalette.cardSurface,
    onSurface = DarkPalette.inkPrimary,
    surfaceVariant = DarkPalette.surfaceTint,
    onSurfaceVariant = DarkPalette.inkSecondary,
    error = DarkPalette.criticalCrimson,
    onError = Color.White,
    errorContainer = DarkPalette.criticalCrimsonSurface,
    onErrorContainer = DarkPalette.criticalCrimsonDark,
    outline = DarkPalette.slateBorder,
    outlineVariant = Color(0xFF1E3035),
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
fun PillScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = if (darkTheme) ClinicalDarkColorScheme else ClinicalLightColorScheme
    CompositionLocalProvider(LocalPillScopePalette provides palette) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ExpressiveClinicalShapes,
            typography = ClinicalTypography,
            content = content,
        )
    }
}
