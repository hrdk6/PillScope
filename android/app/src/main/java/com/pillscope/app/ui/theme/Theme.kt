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

/**
 * PillScope Coastal Ceramic & Viridian Palette.
 *
 * Clean, fresh, eye-pleasing clinical colors with zero neon glare and zero muddy grey wash:
 * crisp alabaster-porcelain light surfaces, deep twilight-slate dark surfaces, rich Aegean
 * viridian, clear cerulean, warm honey marigold, fresh leaf emerald, and authoritative ruby.
 */
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
    val criticalBannerContainer: Color,
    val onCriticalBanner: Color,
    val moderateAmber: Color,
    val moderateAmberDark: Color,
    val moderateAmberSurface: Color,
    val minorBlue: Color,
    val minorBlueDark: Color,
    val minorBlueSurface: Color,
    val safeEmerald: Color,
    val safeEmeraldDark: Color,
    val safeEmeraldSurface: Color,
    val hudBackdrop: Color,
    val hudAmberGlow: Color,
    val hudEmeraldLock: Color,
    val onHudLock: Color,
    val isDark: Boolean,
)

private val LightPalette = PillScopePalette(
    // Rich Aegean Viridian & Fresh Seafoam Wash
    clinicalTeal = Color(0xFF0D7A6F),
    clinicalTealDeep = Color(0xFF0A5851),
    clinicalTealContainer = Color(0xFFDDF3F0),
    // Airy Porcelain-Sky Canvas & Crisp White Surfaces
    slateSurface = Color(0xFFF6F9FB),
    cardSurface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFFECF2F7),
    slateBorder = Color(0xFFD8E2EC),
    inkPrimary = Color(0xFF102A43),
    inkSecondary = Color(0xFF486581),
    // Authoritative Ruby Rose (vivid & clear, non-neon)
    criticalCrimson = Color(0xFFC82838),
    criticalCrimsonDark = Color(0xFF8A1724),
    criticalCrimsonSurface = Color(0xFFFDECEE),
    criticalBannerContainer = Color(0xFFBE2334),
    onCriticalBanner = Color(0xFFFFFFFF),
    // Warm Honey Marigold
    moderateAmber = Color(0xFFC97A10),
    moderateAmberDark = Color(0xFF7F4806),
    moderateAmberSurface = Color(0xFFFFF5E4),
    // Clear Mediterranean Cerulean
    minorBlue = Color(0xFF2563A6),
    minorBlueDark = Color(0xFF194475),
    minorBlueSurface = Color(0xFFE8F2FC),
    // Fresh Botanical Leaf Green
    safeEmerald = Color(0xFF1E8255),
    safeEmeraldDark = Color(0xFF125436),
    safeEmeraldSurface = Color(0xFFE4F6EC),
    // Matte Twilight Optical Viewfinder
    hudBackdrop = Color(0xFF101B26),
    hudAmberGlow = Color(0xFFE6AC50),
    hudEmeraldLock = Color(0xFF58BA98),
    onHudLock = Color(0xFF07271E),
    isDark = false,
)

private val DarkPalette = PillScopePalette(
    // Luminous Seafoam Viridian & Deep Oceanic Pine (pleasing jewel clarity, non-neon)
    clinicalTeal = Color(0xFF46B5A7),
    clinicalTealDeep = Color(0xFFB5EAE2),
    clinicalTealContainer = Color(0xFF153D3D),
    // Rich Twilight Indigo-Slate Canvas & Elevation
    slateSurface = Color(0xFF0F1722),
    cardSurface = Color(0xFF172231),
    surfaceTint = Color(0xFF202F42),
    slateBorder = Color(0xFF2C3F56),
    inkPrimary = Color(0xFFF0F5FA),
    inkSecondary = Color(0xFF9FB3C8),
    // Warm Coral Ruby & Deep Ruby Banner
    criticalCrimson = Color(0xFFEE6B78),
    criticalCrimsonDark = Color(0xFFFFD3D8),
    criticalCrimsonSurface = Color(0xFF3C1B24),
    criticalBannerContainer = Color(0xFFB52536),
    onCriticalBanner = Color(0xFFFFFFFF),
    // Warm Golden Honey
    moderateAmber = Color(0xFFE8AE52),
    moderateAmberDark = Color(0xFFFCE5BE),
    moderateAmberSurface = Color(0xFF392B17),
    // Soft Sky Cerulean
    minorBlue = Color(0xFF6EA8E5),
    minorBlueDark = Color(0xFFCFE4FA),
    minorBlueSurface = Color(0xFF192B40),
    // Fresh Mint-Leaf Green
    safeEmerald = Color(0xFF59C092),
    safeEmeraldDark = Color(0xFFC7F0DC),
    safeEmeraldSurface = Color(0xFF153227),
    // Matte Twilight Optical Viewfinder
    hudBackdrop = Color(0xFF0B121B),
    hudAmberGlow = Color(0xFFE6AC50),
    hudEmeraldLock = Color(0xFF58BA98),
    onHudLock = Color(0xFF07271E),
    isDark = true,
)

val LocalPillScopePalette = staticCompositionLocalOf { LightPalette }

/**
 * Adaptive semantic colors that automatically follow Light/Dark mode inside @Composable scopes,
 * while preserving matte optical fallback values for non-composable callers.
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
    val CriticalBannerContainer: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.criticalBannerContainer
    val OnCriticalBanner: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.onCriticalBanner

    val ModerateAmber: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmber
    val ModerateAmberDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmberDark
    val ModerateAmberSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.moderateAmberSurface

    val MinorBlue: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.minorBlue
    val MinorBlueDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.minorBlueDark
    val MinorBlueSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.minorBlueSurface

    val SafeEmerald: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmerald
    val SafeEmeraldDark: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmeraldDark
    val SafeEmeraldSurface: Color
        @Composable @ReadOnlyComposable get() = LocalPillScopePalette.current.safeEmeraldSurface

    val HudBackdrop: Color = Color(0xFF101B26)
    val HudAmberGlow: Color = Color(0xFFE6AC50)
    val HudEmeraldLock: Color = Color(0xFF58BA98)
    val OnHudLock: Color = Color(0xFF07271E)
}

private val CoastalLightColorScheme = lightColorScheme(
    primary = LightPalette.clinicalTeal,
    onPrimary = Color.White,
    primaryContainer = LightPalette.clinicalTealContainer,
    onPrimaryContainer = LightPalette.clinicalTealDeep,
    secondary = Color(0xFF2E5E88),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EFF9),
    onSecondaryContainer = Color(0xFF173B5C),
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
    outlineVariant = Color(0xFFE4ECF3),
)

private val CoastalDarkColorScheme = darkColorScheme(
    primary = DarkPalette.clinicalTeal,
    onPrimary = Color(0xFF062925),
    primaryContainer = DarkPalette.clinicalTealContainer,
    onPrimaryContainer = DarkPalette.clinicalTealDeep,
    secondary = Color(0xFF7CB2E2),
    onSecondary = Color(0xFF0D253B),
    secondaryContainer = Color(0xFF1D344A),
    onSecondaryContainer = Color(0xFFD6E9FA),
    tertiary = DarkPalette.safeEmerald,
    onTertiary = Color(0xFF072A1C),
    tertiaryContainer = DarkPalette.safeEmeraldSurface,
    onTertiaryContainer = DarkPalette.safeEmeraldDark,
    background = DarkPalette.slateSurface,
    onBackground = DarkPalette.inkPrimary,
    surface = DarkPalette.cardSurface,
    onSurface = DarkPalette.inkPrimary,
    surfaceVariant = DarkPalette.surfaceTint,
    onSurfaceVariant = DarkPalette.inkSecondary,
    error = DarkPalette.criticalCrimson,
    onError = Color(0xFF3E0B13),
    errorContainer = DarkPalette.criticalCrimsonSurface,
    onErrorContainer = DarkPalette.criticalCrimsonDark,
    outline = DarkPalette.slateBorder,
    outlineVariant = Color(0xFF233447),
)

private val ApothecaryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val PharmacopoeiaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
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
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
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
    val colorScheme = if (darkTheme) CoastalDarkColorScheme else CoastalLightColorScheme
    CompositionLocalProvider(LocalPillScopePalette provides palette) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.standard(),
            shapes = ApothecaryShapes,
            typography = PharmacopoeiaTypography,
            content = content,
        )
    }
}
