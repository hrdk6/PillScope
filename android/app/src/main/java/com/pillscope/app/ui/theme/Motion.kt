package com.pillscope.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Checks Android's system-wide reduced-motion setting (`ANIMATOR_DURATION_SCALE == 0f`)
 * so ambient and decorative animations settle immediately for accessibility.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Disciplined clinical motion language: crisp optical snaps for instrument states,
 * damped springs for tactile responses, and gentle settles for streaming clinical data.
 */
object PillScopeMotion {
    const val FAST_MS = 160
    const val STANDARD_MS = 240
    const val CALM_MS = 320

    fun <T> settleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> tactileSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> dialSpring() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessLow,
    )

    fun panelEnter(reducedMotion: Boolean = false): EnterTransition = if (reducedMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(STANDARD_MS, easing = FastOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(STANDARD_MS, easing = FastOutSlowInEasing),
            )
    }

    fun panelExit(reducedMotion: Boolean = false): ExitTransition = if (reducedMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(FAST_MS, easing = FastOutSlowInEasing)) +
            shrinkVertically(
                animationSpec = tween(FAST_MS, easing = FastOutSlowInEasing),
            )
    }

    fun badgeEnter(reducedMotion: Boolean = false): EnterTransition = if (reducedMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(FAST_MS)) + scaleIn(
            initialScale = 0.72f,
            animationSpec = tactileSpring(),
        )
    }

    fun badgeExit(reducedMotion: Boolean = false): ExitTransition = if (reducedMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(FAST_MS)) + scaleOut(targetScale = 0.72f, animationSpec = tween(FAST_MS))
    }

    fun screenTransition(reducedMotion: Boolean = false): ContentTransform = if (reducedMotion) {
        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    } else {
        (fadeIn(tween(STANDARD_MS, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.985f, animationSpec = tween(STANDARD_MS, easing = FastOutSlowInEasing)))
            .togetherWith(fadeOut(tween(FAST_MS, easing = FastOutSlowInEasing)))
    }

    fun numericFlip(reducedMotion: Boolean = false): ContentTransform = if (reducedMotion) {
        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    } else {
        (fadeIn(tween(FAST_MS)) + slideInVertically(tween(FAST_MS)) { it / 3 })
            .togetherWith(fadeOut(tween(120)))
    }
}

/**
 * Tactile press-scale feedback for primary interactive instruments (shutter, mic, dose check).
 */
fun Modifier.tactilePressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
): Modifier = composed {
    val reducedMotion = rememberReducedMotionEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed && !reducedMotion) pressedScale else 1f,
        animationSpec = PillScopeMotion.tactileSpring(),
        label = "tactilePressScale",
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Orchestrated single-shot entrance used sparingly for focal hero elements and progressive reveals.
 */
@Composable
fun OrchestratedReveal(
    delayMillis: Long = 0L,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    var visible by remember { mutableStateOf(reducedMotion) }

    LaunchedEffect(reducedMotion, delayMillis) {
        if (reducedMotion) {
            visible = true
        } else {
            if (delayMillis > 0L) delay(delayMillis)
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) {
            EnterTransition.None
        } else {
            fadeIn(tween(PillScopeMotion.CALM_MS, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(PillScopeMotion.CALM_MS, easing = FastOutSlowInEasing),
                    initialOffsetY = { (it * 0.08f).toInt().coerceAtMost(24) },
                )
        },
        exit = ExitTransition.None,
    ) {
        content()
    }
}

/**
 * Bespoke PillScope apothecary optical emblem: a calibrated reticle ring framing a two-tone
 * capsule silhouette in muted botanical spruce and porcelain tones (zero neon).
 */
@Composable
fun PillScopeEmblem(
    modifier: Modifier = Modifier,
    ringColor: Color = PillScopeColors.ClinicalTeal,
    capsulePrimary: Color = PillScopeColors.ClinicalTealDeep,
    capsuleSecondary: Color = PillScopeColors.ClinicalTealContainer,
    tickColor: Color = PillScopeColors.ClinicalTeal.copy(alpha = 0.72f),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val minDim = minOf(w, h)
        val center = Offset(w / 2f, h / 2f)
        val outerRadius = minDim * 0.38f
        val ringStroke = (minDim * 0.055f).coerceAtLeast(1.8.dp.toPx())

        // Precision optical reticle circle
        drawCircle(
            color = ringColor,
            radius = outerRadius,
            center = center,
            style = Stroke(width = ringStroke),
        )

        // Four cardinal optical calibration ticks
        val tickInner = outerRadius - ringStroke * 0.1f
        val tickOuter = minDim * 0.48f
        val tickStroke = (minDim * 0.045f).coerceAtLeast(1.5.dp.toPx())
        val tickAngles = listOf(
            Offset(0f, -1f),
            Offset(0f, 1f),
            Offset(-1f, 0f),
            Offset(1f, 0f),
        )
        tickAngles.forEach { dir ->
            drawLine(
                color = tickColor,
                start = Offset(center.x + dir.x * tickInner, center.y + dir.y * tickInner),
                end = Offset(center.x + dir.x * tickOuter, center.y + dir.y * tickOuter),
                strokeWidth = tickStroke,
                cap = StrokeCap.Round,
            )
        }

        // Tilted two-tone apothecary capsule centered inside the optical ring
        val capsuleW = minDim * 0.42f
        val capsuleH = minDim * 0.21f
        val radius = capsuleH / 2f
        rotate(degrees = -42f, pivot = center) {
            val left = center.x - capsuleW / 2f
            val top = center.y - capsuleH / 2f
            drawRoundRect(
                color = capsuleSecondary,
                topLeft = Offset(left, top),
                size = Size(capsuleW, capsuleH),
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = capsulePrimary,
                topLeft = Offset(left, top),
                size = Size(capsuleW * 0.54f, capsuleH),
                cornerRadius = CornerRadius(radius, radius),
            )
            // Crisp hairline outline so the porcelain half reads cleanly on light surfaces
            drawRoundRect(
                color = capsulePrimary,
                topLeft = Offset(left, top),
                size = Size(capsuleW, capsuleH),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = (minDim * 0.032f).coerceAtLeast(1.2.dp.toPx())),
            )
        }
    }
}
