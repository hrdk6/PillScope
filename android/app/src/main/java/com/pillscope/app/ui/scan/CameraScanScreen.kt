package com.pillscope.app.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.MedicineLabelAnalyzer
import com.pillscope.app.ml.NormalizedCropRect
import com.pillscope.app.ml.OcrTextBlockBox
import com.pillscope.app.ui.common.LanguagePillButton
import com.pillscope.app.ui.common.formatSaltComponents
import com.pillscope.app.ui.profiles.ActiveProfilePill
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeEmblem
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import com.pillscope.app.ui.theme.tactilePressScale
import com.pillscope.app.voice.VoiceCommand
import com.pillscope.app.voice.VoiceCommandParser
import com.pillscope.app.voice.VoiceNavTarget
import com.pillscope.app.voice.rememberVoiceInputController
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    activeProfile: UserProfile,
    onOpenProfileSwitcher: () -> Unit,
    onOpenLanguagePicker: () -> Unit = {},
    onVoiceNavigate: (VoiceNavTarget) -> Unit = {},
    onVoiceAskAi: (String) -> Unit = {},
    onAnalyzeRequest: (ScanAnalyzeRequestDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val voiceSearch = rememberVoiceInputController(
        language = language,
        promptText = strings.voiceSearchTooltip,
        onResult = { spoken ->
            val trimmed = spoken.trim()
            if (trimmed.isEmpty()) return@rememberVoiceInputController
            when (val cmd = VoiceCommandParser.parse(trimmed)) {
                is VoiceCommand.Navigate -> onVoiceNavigate(cmd.target)
                is VoiceCommand.SearchMedicine -> viewModel.onManualQueryChange(cmd.query)
                else -> {
                    val wordCount = trimmed.split(Regex("\\s+")).size
                    if (wordCount <= 3 && !trimmed.contains("?")) {
                        viewModel.onManualQueryChange(trimmed)
                    } else {
                        onVoiceAskAi(trimmed)
                    }
                }
            }
        },
    )

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (torchEnabled) {
                        torchEnabled = false
                        runCatching { boundCamera?.cameraControl?.enableTorch(false) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var previousAcceptable by remember { mutableStateOf(false) }
    val currentAcceptable = state.latestQuality?.isAcceptable ?: false

    LaunchedEffect(currentAcceptable) {
        if (currentAcceptable && !previousAcceptable) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        previousAcceptable = currentAcceptable
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequested = true
        hasCameraPermission = granted
    }

    val activity = remember(context) { context.findActivity() }
    val isPermanentlyDenied = permissionRequested && !hasCameraPermission && (
        activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.CAMERA,
        )
        )

    val hasMatchesBelow = state.resolvedCandidates.isNotEmpty() || state.localSaltChips.isNotEmpty()
    val viewfinderHeight by animateDpAsState(
        targetValue = if (hasMatchesBelow) 272.dp else 318.dp,
        animationSpec = PillScopeMotion.settleSpring(),
        label = "viewfinderHeight",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hero Optical Viewfinder — anodized matte housing with precision reticle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewfinderHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(PillScopeColors.HudBackdrop)
                .border(
                    width = 1.dp,
                    color = PillScopeColors.SlateBorder.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(24.dp),
                ),
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithAnalyzer(
                    viewModel = viewModel,
                    torchEnabled = torchEnabled,
                    onCameraBound = { camera ->
                        boundCamera = camera
                        hasFlashUnit = camera?.cameraInfo?.hasFlashUnit() == true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                OpticalViewfinderHudOverlay(
                    cropBox = state.latestQuality?.cropBox ?: NormalizedCropRect.DEFAULT_LABEL_BOX,
                    ocrTextBlocks = state.ocrTextBlocks,
                    isAcceptable = currentAcceptable || state.isForcedCaptureHeld,
                    isCameraFrozen = state.isCameraFrozen,
                    reducedMotion = reducedMotion,
                    frameWidth = state.frameWidth,
                    frameHeight = state.frameHeight,
                    modifier = Modifier.fillMaxSize(),
                )
                // Quiet top and bottom optical gradient scrims for crisp legibility over bright foil
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xAA0C100E),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xC20C100E),
                                ),
                            ),
                        ),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 28.dp, end = 28.dp, top = 64.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PillScopeEmblem(
                        modifier = Modifier.size(50.dp),
                        ringColor = Color(0xFFB8D4C8),
                        capsulePrimary = Color(0xFF8FC0A6),
                        capsuleSecondary = Color(0xFFF5F6F3),
                        tickColor = Color(0x99F5F6F3),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (isPermanentlyDenied) {
                            strings.cameraPermissionDisabled
                        } else {
                            strings.cameraPermissionRationale
                        },
                        color = Color(0xFFECEFE9),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    val permButtonColors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE7EFEA),
                        contentColor = Color(0xFF13342C),
                    )
                    if (isPermanentlyDenied) {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                runCatching { context.startActivity(intent) }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = permButtonColors,
                        ) {
                            Text(strings.openAppSettings)
                        }
                    } else {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            shape = RoundedCornerShape(12.dp),
                            colors = permButtonColors,
                        ) {
                            Text(strings.enableCamera)
                        }
                    }
                }
            }

            // Top bar inside viewfinder: camera instruments left, language + active profile right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCameraPermission && hasFlashUnit) {
                        val torchTint by animateColorAsState(
                            targetValue = if (torchEnabled) PillScopeColors.HudAmberGlow else Color(0xFFF5F6F3),
                            animationSpec = tween(PillScopeMotion.FAST_MS),
                            label = "torchTint",
                        )
                        IconButton(
                            onClick = {
                                val next = !torchEnabled
                                torchEnabled = next
                                boundCamera?.cameraControl?.enableTorch(next)
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(0x99131715), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = if (torchEnabled) "Turn off flashlight" else "Turn on flashlight",
                                tint = torchTint,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }

                    if (hasCameraPermission) {
                        IconButton(
                            onClick = viewModel::toggleCameraFreeze,
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(0x99131715), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (state.isCameraFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (state.isCameraFrozen) "Resume camera" else "Freeze frame",
                                tint = Color(0xFFF5F6F3),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguagePillButton(
                        onClick = onOpenLanguagePicker,
                        darkSurface = true,
                    )
                    ActiveProfilePill(
                        profile = activeProfile,
                        onClick = onOpenProfileSwitcher,
                        darkSurface = true,
                    )
                }
            }

            // Bottom HUD controls gated strictly on camera permission
            if (hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QualityHudStatusPill(
                            quality = state.latestQuality,
                            isForcedCaptureHeld = state.isForcedCaptureHeld,
                            isCameraFrozen = state.isCameraFrozen,
                        )
                        AnimatedVisibility(
                            visible = state.latestQuality != null && !currentAcceptable && !state.isForcedCaptureHeld,
                            enter = PillScopeMotion.badgeEnter(reducedMotion),
                            exit = PillScopeMotion.badgeExit(reducedMotion),
                        ) {
                            Surface(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.requestForcedOcrCapture()
                                },
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = CircleShape,
                                color = Color(0xFFE7EFEA),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    Text(
                                        text = strings.forceScan,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF13342C),
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xAA131715),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            ) {
                                Text(
                                    text = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                                        "Quick check for ${activeProfile.name}"
                                    } else {
                                        "Checking against ${activeProfile.name}'s cabinet"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFECEFE9),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            AnimatedVisibility(
                                visible = state.resolvedCandidates.isNotEmpty(),
                                enter = PillScopeMotion.badgeEnter(reducedMotion),
                                exit = PillScopeMotion.badgeExit(reducedMotion),
                            ) {
                                Surface(
                                    onClick = {
                                        coroutineScope.launch {
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    },
                                    shape = CircleShape,
                                    color = PillScopeColors.HudEmeraldLock,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val count = state.resolvedCandidates.size
                                        Text(
                                            text = "$count ${if (count == 1) "match" else "matches"} below",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PillScopeColors.OnHudLock,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = PillScopeColors.OnHudLock,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }

                        val canCapture = state.resolvedCandidates.isNotEmpty() ||
                            state.localSaltChips.isNotEmpty() ||
                            state.manualQuery.isNotBlank() ||
                            state.rawOcrText.isNotBlank()
                        val shutterInteraction = remember { MutableInteractionSource() }
                        val shutterInnerColor by animateColorAsState(
                            targetValue = when {
                                !canCapture -> Color(0x66131715)
                                currentAcceptable || state.isForcedCaptureHeld -> PillScopeColors.HudEmeraldLock
                                else -> Color(0xFFE7EFEA)
                            },
                            animationSpec = tween(PillScopeMotion.STANDARD_MS),
                            label = "shutterInner",
                        )
                        val shutterRingColor by animateColorAsState(
                            targetValue = when {
                                !canCapture -> Color.White.copy(alpha = 0.24f)
                                currentAcceptable || state.isForcedCaptureHeld -> PillScopeColors.HudEmeraldLock
                                else -> Color.White.copy(alpha = 0.85f)
                            },
                            animationSpec = tween(PillScopeMotion.STANDARD_MS),
                            label = "shutterRing",
                        )
                        val shutterIconTint by animateColorAsState(
                            targetValue = when {
                                !canCapture -> Color.White.copy(alpha = 0.36f)
                                else -> PillScopeColors.OnHudLock
                            },
                            animationSpec = tween(PillScopeMotion.FAST_MS),
                            label = "shutterIcon",
                        )
                        val shutterDescription = when {
                            !canCapture -> "Align a medicine label to run safety check"
                            state.resolvedCandidates.size > 1 -> "Review ${state.resolvedCandidates.size} matches below"
                            state.resolvedCandidates.size == 1 -> "Run safety check for ${state.resolvedCandidates.first().brandName}"
                            else -> "Run safety check"
                        }

                        // Optical dual-ring mechanical shutter
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .tactilePressScale(shutterInteraction, pressedScale = 0.92f, enabled = canCapture)
                                .border(2.dp, shutterRingColor, CircleShape)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (state.resolvedCandidates.size > 1) {
                                        coroutineScope.launch {
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    } else {
                                        viewModel.buildAnalyzeRequestForShutter()?.let(onAnalyzeRequest)
                                    }
                                },
                                enabled = canCapture,
                                interactionSource = shutterInteraction,
                                shape = CircleShape,
                                color = shutterInnerColor,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                                            Icons.Default.ShoppingBag
                                        } else {
                                            Icons.Default.CameraAlt
                                        },
                                        contentDescription = shutterDescription,
                                        tint = shutterIconTint,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val modeSegColors = SegmentedButtonDefaults.colors(
            activeContainerColor = PillScopeColors.ClinicalTealContainer,
            activeContentColor = PillScopeColors.ClinicalTealDeep,
            activeBorderColor = PillScopeColors.ClinicalTeal.copy(alpha = 0.45f),
            inactiveContainerColor = PillScopeColors.CardSurface,
            inactiveContentColor = PillScopeColors.InkSecondary,
            inactiveBorderColor = PillScopeColors.SlateBorder,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.scanMode == ScanMode.ADD_TO_CABINET,
                onClick = { viewModel.onScanModeChange(ScanMode.ADD_TO_CABINET) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = modeSegColors,
                label = { Text(strings.saveToHistory) },
            )
            SegmentedButton(
                selected = state.scanMode == ScanMode.CHECK_BEFORE_BUYING,
                onClick = { viewModel.onScanModeChange(ScanMode.CHECK_BEFORE_BUYING) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PillScopeColors.ModerateAmberSurface,
                    activeContentColor = PillScopeColors.ModerateAmberDark,
                    activeBorderColor = PillScopeColors.ModerateAmber.copy(alpha = 0.45f),
                    inactiveContainerColor = PillScopeColors.CardSurface,
                    inactiveContentColor = PillScopeColors.InkSecondary,
                    inactiveBorderColor = PillScopeColors.SlateBorder,
                ),
                label = { Text(strings.quickCheck) },
            )
        }

        val queryTrimmedLen = state.manualQuery.trim().length
        val micTint by animateColorAsState(
            targetValue = if (voiceSearch.isListening) {
                PillScopeColors.CriticalCrimson
            } else {
                PillScopeColors.ClinicalTeal
            },
            animationSpec = tween(PillScopeMotion.FAST_MS),
            label = "searchMicTint",
        )
        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = viewModel::onManualQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { viewModel.onSearchFocusChanged(it.isFocused) },
            singleLine = true,
            label = { Text(strings.searchMedicineLabel) },
            supportingText = if (voiceSearch.isListening) {
                { Text(voiceSearch.partialTranscript.ifBlank { strings.listeningNow }) }
            } else if (voiceSearch.errorMessage != null) {
                { Text(voiceSearch.errorMessage.orEmpty()) }
            } else if (queryTrimmedLen in 1..2) {
                { Text(strings.searchSupportingHint) }
            } else {
                null
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        state.isResolving -> LoadingIndicator(
                            modifier = Modifier.size(20.dp),
                            color = PillScopeColors.ClinicalTeal,
                        )
                        state.manualQuery.isNotEmpty() -> IconButton(
                            onClick = viewModel::clearManualSearchOnly,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = strings.close,
                            )
                        }
                        state.rawOcrText.isNotEmpty() -> IconButton(
                            onClick = viewModel::clearScanState,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = strings.close,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (voiceSearch.isListening) {
                                voiceSearch.stopListening()
                            } else {
                                voiceSearch.startListening()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (voiceSearch.isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = strings.voiceSearchTooltip,
                            tint = micTint,
                        )
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
        )

        AnimatedVisibility(
            visible = state.resolveError != null,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            state.resolveError?.let { errorMsg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = PillScopeColors.ModerateAmberSurface,
                    border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = PillScopeColors.ModerateAmberDark,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // Unified Brand Matches Ledger — clean hairline-divided rows instead of identical boxed cards
        AnimatedVisibility(
            visible = state.resolvedCandidates.isNotEmpty(),
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Column(
                modifier = Modifier.animateContentSize(PillScopeMotion.settleSpring()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = strings.tapMatchToCheck,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CardSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        state.resolvedCandidates.forEachIndexed { idx, candidate ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = PillScopeColors.SlateBorder.copy(alpha = 0.7f),
                                )
                            }
                            BrandConfirmationLedgerRow(
                                candidate = candidate,
                                scanMode = state.scanMode,
                                onConfirm = {
                                    onAnalyzeRequest(viewModel.buildAnalyzeRequestForCandidate(candidate))
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.localSaltChips.isNotEmpty(),
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Column(
                modifier = Modifier.animateContentSize(PillScopeMotion.settleSpring()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = strings.detectedIngredients,
                        style = MaterialTheme.typography.titleMedium,
                        color = PillScopeColors.InkPrimary,
                    )
                    TextButton(onClick = viewModel::clearScanState) {
                        Text(strings.rescan, color = PillScopeColors.ClinicalTeal)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.localSaltChips.forEach { chip ->
                        val label = if (chip.strengthText != null) {
                            "${chip.canonicalSalt} ${chip.strengthText}"
                        } else {
                            chip.canonicalSalt
                        }
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeSaltChip(chip.canonicalSalt) },
                            label = { Text(label) },
                            shape = CircleShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PillScopeColors.ClinicalTeal,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove ${chip.canonicalSalt}",
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            colors = InputChipDefaults.inputChipColors(
                                selectedContainerColor = PillScopeColors.ClinicalTealContainer,
                                selectedLabelColor = PillScopeColors.ClinicalTealDeep,
                                selectedTrailingIconColor = PillScopeColors.ClinicalTealDeep,
                            ),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onAnalyzeRequest(viewModel.buildAnalyzeRequestForLocalChips()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = "${strings.checkDetectedIngredients} (${state.localSaltChips.size})",
                        color = PillScopeColors.ClinicalTealDeep,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.manualQuery.isNotBlank() && state.resolvedCandidates.isEmpty() && !state.isResolving,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            Button(
                onClick = {
                    viewModel.buildAnalyzeRequestForShutter()?.let(onAnalyzeRequest)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PillScopeColors.ClinicalTeal,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                val actionPrefix = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                    strings.quickCheckForPrefix
                } else {
                    strings.checkSafetyForPrefix
                }
                Text("$actionPrefix \"${state.manualQuery.trim()}\"")
            }
        }
    }
}

@Composable
private fun CameraPreviewWithAnalyzer(
    viewModel: ScanViewModel,
    torchEnabled: Boolean,
    onCameraBound: (Camera?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(torchEnabled, cameraRef) {
        cameraRef?.cameraControl?.enableTorch(torchEnabled)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderRef?.unbindAll() }
            onCameraBound(null)
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val pv = previewViewRef ?: return@detectTapGestures
                val cam = cameraRef ?: return@detectTapGestures
                val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                runCatching { cam.cameraControl.startFocusAndMetering(action) }
            }
        },
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            previewViewRef = previewView
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                analysisExecutor,
                                MedicineLabelAnalyzer(
                                    preprocessor = viewModel.preprocessor,
                                    ocrEngine = viewModel.ocrEngine,
                                    scope = coroutineScope,
                                    forceNextOcr = viewModel.forceNextOcr,
                                    onResult = viewModel::onCameraPipelineResult,
                                ),
                            )
                        }
                    runCatching {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        cameraRef = camera
                        onCameraBound(camera)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            previewView
        },
    )
}

/**
 * Matte precision optical reticle: zero neon glow. Features crisp instrument corner ticks,
 * subtle optical snap on focus lock, and a delicate single scan sweep while seeking focus.
 */
@Composable
private fun OpticalViewfinderHudOverlay(
    cropBox: NormalizedCropRect,
    ocrTextBlocks: List<OcrTextBlockBox>,
    isAcceptable: Boolean,
    isCameraFrozen: Boolean,
    reducedMotion: Boolean,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPillScopePalette.current
    val bracketColor by animateColorAsState(
        targetValue = if (isAcceptable) palette.hudEmeraldLock else palette.hudAmberGlow,
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "bracketColor",
    )
    val bracketInsetScale by animateFloatAsState(
        targetValue = if (isAcceptable) 0f else 0.022f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bracketInset",
    )

    val isActivelySeeking = !isAcceptable && !isCameraFrozen && !reducedMotion
    val scanLineAlpha by animateFloatAsState(
        targetValue = if (isActivelySeeking) 1f else 0f,
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "scanLineAlpha",
    )
    val scanSweepProgress = if (isActivelySeeking) {
        val sweep = rememberInfiniteTransition(label = "opticalSweep")
        sweep.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sweepY",
        ).value
    } else {
        0.5f
    }

    Canvas(modifier = modifier) {
        val (contentLeft, contentTop, contentW, contentH) = if (frameWidth > 0 && frameHeight > 0) {
            val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
            val viewAspect = size.width / size.height.coerceAtLeast(1f)
            if (frameAspect > viewAspect) {
                val w = size.height * frameAspect
                listOf((size.width - w) / 2f, 0f, w, size.height)
            } else {
                val h = size.width / frameAspect
                listOf(0f, (size.height - h) / 2f, size.width, h)
            }
        } else {
            listOf(0f, 0f, size.width, size.height)
        }

        // Subtle optical OCR region indicators (clean matte celadon, no neon wash)
        for (block in ocrTextBlocks) {
            val bLeft = (contentLeft + block.bounds.left * contentW).coerceIn(0f, size.width)
            val bTop = (contentTop + block.bounds.top * contentH).coerceIn(0f, size.height)
            val bRight = (contentLeft + block.bounds.right * contentW).coerceIn(bLeft, size.width)
            val bBottom = (contentTop + block.bounds.bottom * contentH).coerceIn(bTop, size.height)
            val bWidth = (bRight - bLeft).coerceAtLeast(8f)
            val bHeight = (bBottom - bTop).coerceAtLeast(8f)
            drawRoundRect(
                color = palette.hudEmeraldLock.copy(alpha = 0.12f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            drawRoundRect(
                color = palette.hudEmeraldLock.copy(alpha = 0.70f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }

        val expandX = size.width * bracketInsetScale
        val expandY = size.height * bracketInsetScale
        val minLeft = 22.dp.toPx()
        val minTop = 64.dp.toPx()
        val maxRight = size.width - 22.dp.toPx()
        val maxBottom = size.height - 112.dp.toPx()
        val left = (contentLeft + cropBox.left * contentW - expandX).coerceIn(minLeft, (maxRight - 48f).coerceAtLeast(minLeft))
        val top = (contentTop + cropBox.top * contentH - expandY).coerceIn(minTop, (maxBottom - 48f).coerceAtLeast(minTop))
        val right = (contentLeft + cropBox.right * contentW + expandX).coerceIn(left + 48f, maxRight)
        val bottom = (contentTop + cropBox.bottom * contentH + expandY).coerceIn(top + 48f, maxBottom)
        val rectWidth = (right - left).coerceAtLeast(40f)
        val rectHeight = (bottom - top).coerceAtLeast(40f)

        // Delicate calibration sweep line while actively seeking focus
        if (scanLineAlpha > 0.01f) {
            val lineY = top + rectHeight * scanSweepProgress
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        bracketColor.copy(alpha = 0.55f * scanLineAlpha),
                        Color.Transparent,
                    ),
                    startX = left + 8.dp.toPx(),
                    endX = right - 8.dp.toPx(),
                ),
                start = Offset(left + 8.dp.toPx(), lineY),
                end = Offset(right - 8.dp.toPx(), lineY),
                strokeWidth = 1.2.dp.toPx(),
            )
        }

        // Precision matte optical brackets with subtle dark backing for contrast on white labels
        val cornerLen = min(rectWidth, rectHeight) * 0.22f
        val strokePx = 2.6.dp.toPx()
        val backingPx = 4.6.dp.toPx()

        val corners = listOf(
            Pair(Offset(left, top + cornerLen), Offset(left, top)) to Offset(left + cornerLen, top),
            Pair(Offset(right - cornerLen, top), Offset(right, top)) to Offset(right, top + cornerLen),
            Pair(Offset(left, bottom - cornerLen), Offset(left, bottom)) to Offset(left + cornerLen, bottom),
            Pair(Offset(right - cornerLen, bottom), Offset(right, bottom)) to Offset(right, bottom - cornerLen),
        )

        for ((firstSeg, endPoint) in corners) {
            val (startPoint, vertex) = firstSeg
            drawLine(
                color = Color.Black.copy(alpha = 0.36f),
                start = startPoint,
                end = vertex,
                strokeWidth = backingPx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.Black.copy(alpha = 0.36f),
                start = vertex,
                end = endPoint,
                strokeWidth = backingPx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = bracketColor,
                start = startPoint,
                end = vertex,
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = bracketColor,
                start = vertex,
                end = endPoint,
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }

        // Minimal center reticle crosshair hairlines
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val cross = 5.dp.toPx()
        drawLine(
            color = bracketColor.copy(alpha = 0.55f),
            start = Offset(cx - cross, cy),
            end = Offset(cx + cross, cy),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = bracketColor.copy(alpha = 0.55f),
            start = Offset(cx, cy - cross),
            end = Offset(cx, cy + cross),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun QualityHudStatusPill(
    quality: FrameQualityResult?,
    isForcedCaptureHeld: Boolean,
    isCameraFrozen: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val isOk = quality?.isAcceptable == true || isForcedCaptureHeld
    val targetAccent = when {
        isForcedCaptureHeld -> PillScopeColors.HudEmeraldLock
        isCameraFrozen -> Color(0xFFDCE3DF)
        quality == null -> Color(0xFFDCE3DF)
        isOk -> PillScopeColors.HudEmeraldLock
        else -> PillScopeColors.HudAmberGlow
    }
    val accent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(PillScopeMotion.STANDARD_MS),
        label = "hudAccent",
    )
    val statusText = when {
        isForcedCaptureHeld -> strings.hudFrameCaptured
        isCameraFrozen -> strings.hudCameraPaused
        quality == null -> strings.hudAlignLabel
        quality.glareRatio > FrameQualityResult.MAX_GLARE_RATIO -> strings.hudGlareDetected
        quality.blurScore < FrameQualityResult.MIN_BLUR_SHARPNESS -> strings.hudTooBlurry
        else -> "${strings.hudSharpFocus} (${(quality.blurScore * 100).roundToInt()}%)"
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color(0xCC131715),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            AnimatedContent(
                targetState = statusText,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                label = "hudStatusText",
            ) { label ->
                Text(
                    text = label,
                    color = Color(0xFFECEFE9),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun BrandConfirmationLedgerRow(
    candidate: BrandCandidateDto,
    scanMode: ScanMode,
    onConfirm: () -> Unit,
) {
    val saltsDisplay = remember(candidate.genericSalts, candidate.canonicalSaltKeys) {
        formatSaltComponents(candidate.genericSalts, candidate.canonicalSaltKeys)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfirm)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = candidate.brandName,
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            if (saltsDisplay.isNotBlank()) {
                Text(
                    text = saltsDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.Medium,
                )
            }
            candidate.manufacturer?.takeIf { it.isNotBlank() }?.let { mfg ->
                Text(
                    text = mfg,
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                PillScopeColors.ModerateAmberSurface
            } else {
                PillScopeColors.ClinicalTealContainer
            },
        ) {
            Text(
                text = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                    LocalAppStrings.current.quickCheck
                } else {
                    LocalAppStrings.current.checkSafety
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                    PillScopeColors.ModerateAmberDark
                } else {
                    PillScopeColors.ClinicalTealDeep
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
