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
 * PillScope Apothecary & Pharmacopoeia Palette.
 *
 * Grounded in archival mineral paper, deep botanical spruce, oxblood carmine, burnished ochre,
 * and muted slate indigo — zero neon accents in either Light or Dark mode, while preserving
 * WCAG AA/AAA contrast across every clinical alert state.
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
    // Deep Apothecary Spruce & Eucalyptus Wash
    clinicalTeal = Color(0xFF1F4F43),
    clinicalTealDeep = Color(0xFF13342C),
    clinicalTealContainer = Color(0xFFDDE8E3),
    // Mineral Porcelain & Stone Paper Hierarchy
    slateSurface = Color(0xFFF5F6F3),
    cardSurface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFFEAECE7),
    slateBorder = Color(0xFFD4D9D2),
    inkPrimary = Color(0xFF18201D),
    inkSecondary = Color(0xFF535F5A),
    // Oxblood Carmine (authoritative matte danger, non-neon)
    criticalCrimson = Color(0xFF9E1F28),
    criticalCrimsonDark = Color(0xFF6D131A),
    criticalCrimsonSurface = Color(0xFFF8E8E9),
    criticalBannerContainer = Color(0xFF921B24),
    onCriticalBanner = Color(0xFFFFFFFF),
    // Burnished Ochre (earthy caution, non-neon)
    moderateAmber = Color(0xFF9A5A16),
    moderateAmberDark = Color(0xFF693B0B),
    moderateAmberSurface = Color(0xFFF7EFE3),
    // Archival Slate Indigo (clinical references & minor severity)
    minorBlue = Color(0xFF2D537B),
    minorBlueDark = Color(0xFF1E3A57),
    minorBlueSurface = Color(0xFFEBF0F6),
    // Botanical Cypress (verified safe & dose completion)
    safeEmerald = Color(0xFF286448),
    safeEmeraldDark = Color(0xFF1A4531),
    safeEmeraldSurface = Color(0xFFE7F0EB),
    // Matte Anodized Optical Viewfinder
    hudBackdrop = Color(0xFF131715),
    hudAmberGlow = Color(0xFFD6A156),
    hudEmeraldLock = Color(0xFF8FC0A6),
    onHudLock = Color(0xFF10241C),
    isDark = false,
)

private val DarkPalette = PillScopePalette(
    // Muted Mineral Celadon & Pine Obsidian (zero neon cyan/emerald)
    clinicalTeal = Color(0xFF7FB3A2),
    clinicalTealDeep = Color(0xFFC7DDD5),
    clinicalTealContainer = Color(0xFF1E352E),
    // Deep Botanical Charcoal Hierarchy
    slateSurface = Color(0xFF131716),
    cardSurface = Color(0xFF1B211F),
    surfaceTint = Color(0xFF232B28),
    slateBorder = Color(0xFF303B37),
    inkPrimary = Color(0xFFECEFE9),
    inkSecondary = Color(0xFF9CA9A4),
    // Dusty Carmine Rose & Matte Oxblood Banner
    criticalCrimson = Color(0xFFD9787E),
    criticalCrimsonDark = Color(0xFFF3CDD0),
    criticalCrimsonSurface = Color(0xFF351B1E),
    criticalBannerContainer = Color(0xFF8C1E26),
    onCriticalBanner = Color(0xFFFAF6F6),
    // Antique Brass Ochre
    moderateAmber = Color(0xFFD4A05A),
    moderateAmberDark = Color(0xFFEFE0C6),
    moderateAmberSurface = Color(0xFF312617),
    // Calm Steel Periwinkle
    minorBlue = Color(0xFF89ADD2),
    minorBlueDark = Color(0xFFD2E2F2),
    minorBlueSurface = Color(0xFF1C2836),
    // Muted Botanical Sage
    safeEmerald = Color(0xFF7BB295),
    safeEmeraldDark = Color(0xFFCCE3D6),
    safeEmeraldSurface = Color(0xFF192C22),
    // Matte Anodized Optical Viewfinder
    hudBackdrop = Color(0xFF0E1210),
    hudAmberGlow = Color(0xFFD6A156),
    hudEmeraldLock = Color(0xFF8FC0A6),
    onHudLock = Color(0xFF10241C),
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

    val HudBackdrop: Color = Color(0xFF131715)
    val HudAmberGlow: Color = Color(0xFFD6A156)
    val HudEmeraldLock: Color = Color(0xFF8FC0A6)
    val OnHudLock: Color = Color(0xFF10241C)
}

private val ApothecaryLightColorScheme = lightColorScheme(
    primary = LightPalette.clinicalTeal,
    onPrimary = Color.White,
    primaryContainer = LightPalette.clinicalTealContainer,
    onPrimaryContainer = LightPalette.clinicalTealDeep,
    secondary = Color(0xFF3E524C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAE6),
    onSecondaryContainer = Color(0xFF1E2D29),
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
    outlineVariant = Color(0xFFE3E7E1),
)

private val ApothecaryDarkColorScheme = darkColorScheme(
    primary = DarkPalette.clinicalTeal,
    onPrimary = Color(0xFF0E241E),
    primaryContainer = DarkPalette.clinicalTealContainer,
    onPrimaryContainer = DarkPalette.clinicalTealDeep,
    secondary = Color(0xFFA2B8B0),
    onSecondary = Color(0xFF152520),
    secondaryContainer = Color(0xFF24332E),
    onSecondaryContainer = Color(0xFFDDE6E2),
    tertiary = DarkPalette.safeEmerald,
    onTertiary = Color(0xFF0E2419),
    tertiaryContainer = DarkPalette.safeEmeraldSurface,
    onTertiaryContainer = DarkPalette.safeEmeraldDark,
    background = DarkPalette.slateSurface,
    onBackground = DarkPalette.inkPrimary,
    surface = DarkPalette.cardSurface,
    onSurface = DarkPalette.inkPrimary,
    surfaceVariant = DarkPalette.surfaceTint,
    onSurfaceVariant = DarkPalette.inkSecondary,
    error = DarkPalette.criticalCrimson,
    onError = Color(0xFF360E12),
    errorContainer = DarkPalette.criticalCrimsonSurface,
    onErrorContainer = DarkPalette.criticalCrimsonDark,
    outline = DarkPalette.slateBorder,
    outlineVariant = Color(0xFF27312E),
)

/**
 * Deliberate radius hierarchy: tighter nested controls (8-12dp), structured ledger surfaces (16dp),
 * and sculptural framing reserved for optical viewfinders and modal sheets (24-28dp).
 */
private val ApothecaryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Dual-voice Pharmacopoeia Typography:
 * Editorial serif headlines lend calm clinical gravitas and distinction, while clean humanist
 * sans-serif carries interactive UI, multilingual body copy, and tabular clinical metrics.
 */
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
    val colorScheme = if (darkTheme) ApothecaryDarkColorScheme else ApothecaryLightColorScheme
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
