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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.MedicineLabelAnalyzer
import com.pillscope.app.ml.NormalizedCropRect
import com.pillscope.app.ml.OcrTextBlockBox
import com.pillscope.app.ui.common.formatSaltComponents
import com.pillscope.app.ui.profiles.ActiveProfilePill
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
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
    onAnalyzeRequest: (ScanAnalyzeRequestDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

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

    // Refresh permission state on resume and reset hardware torch state on pause.
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
        targetValue = if (hasMatchesBelow) 276.dp else 312.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewfinderHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(PillScopeColors.HudBackdrop),
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
                    frameWidth = state.frameWidth,
                    frameHeight = state.frameHeight,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, end = 24.dp, top = 62.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (isPermanentlyDenied) {
                            "Camera permission is disabled. Open App Settings to enable live scanning, or search below."
                        } else {
                            "Enable camera access to scan medicine labels on-device. Photos never leave your phone."
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (isPermanentlyDenied) {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                runCatching { context.startActivity(intent) }
                            },
                        ) {
                            Text("Open App Settings")
                        }
                    } else {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Enable camera")
                        }
                    }
                }
            }

            // Keep ActiveProfilePill consistently top-right across all tabs; camera tools sit top-left.
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
                        IconButton(
                            onClick = {
                                val next = !torchEnabled
                                torchEnabled = next
                                boundCamera?.cameraControl?.enableTorch(next)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = if (torchEnabled) "Turn off flashlight" else "Turn on flashlight",
                                tint = if (torchEnabled) PillScopeColors.HudAmberGlow else Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (hasCameraPermission) {
                        IconButton(
                            onClick = viewModel::toggleCameraFreeze,
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (state.isCameraFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (state.isCameraFrozen) "Resume camera" else "Freeze frame",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                ActiveProfilePill(
                    profile = activeProfile,
                    onClick = onOpenProfileSwitcher,
                    darkSurface = true,
                )
            }

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
                        if (state.latestQuality != null && !currentAcceptable && !state.isForcedCaptureHeld) {
                            Surface(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.requestForcedOcrCapture()
                                },
                                modifier = Modifier.heightIn(min = 38.dp),
                                shape = CircleShape,
                                color = PillScopeColors.ClinicalTeal,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = "Force scan",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimary,
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
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.68f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            ) {
                                Text(
                                    text = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                                        "Quick check for ${activeProfile.name}"
                                    } else {
                                        "Checking against ${activeProfile.name}'s cabinet"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            if (state.resolvedCandidates.isNotEmpty()) {
                                Surface(
                                    onClick = {
                                        coroutineScope.launch {
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    },
                                    shape = CircleShape,
                                    color = PillScopeColors.HudEmeraldLock.copy(alpha = 0.92f),
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
                                            color = Color(0xFF042625),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = Color(0xFF042625),
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
                        val shutterContainerColor = when {
                            !canCapture -> Color.Black.copy(alpha = 0.48f)
                            currentAcceptable || state.isForcedCaptureHeld -> PillScopeColors.HudEmeraldLock
                            else -> PillScopeColors.ClinicalTeal
                        }
                        val shutterBorderColor = if (canCapture) {
                            Color.White.copy(alpha = 0.92f)
                        } else {
                            Color.White.copy(alpha = 0.28f)
                        }
                        val shutterIconTint = when {
                            !canCapture -> Color.White.copy(alpha = 0.38f)
                            currentAcceptable || state.isForcedCaptureHeld -> Color(0xFF042625)
                            else -> MaterialTheme.colorScheme.onPrimary
                        }
                        val shutterDescription = when {
                            !canCapture -> "Align a medicine label to run safety check"
                            state.resolvedCandidates.size > 1 -> "Review ${state.resolvedCandidates.size} matches below"
                            state.resolvedCandidates.size == 1 -> "Run safety check for ${state.resolvedCandidates.first().brandName}"
                            else -> "Run safety check"
                        }

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
                            shape = CircleShape,
                            color = shutterContainerColor,
                            border = BorderStroke(3.dp, shutterBorderColor),
                            modifier = Modifier.size(54.dp),
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
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.scanMode == ScanMode.ADD_TO_CABINET,
                onClick = { viewModel.onScanModeChange(ScanMode.ADD_TO_CABINET) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PillScopeColors.ClinicalTealContainer,
                    activeContentColor = PillScopeColors.ClinicalTealDeep,
                ),
                label = { Text("Save to History") },
            )
            SegmentedButton(
                selected = state.scanMode == ScanMode.CHECK_BEFORE_BUYING,
                onClick = { viewModel.onScanModeChange(ScanMode.CHECK_BEFORE_BUYING) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PillScopeColors.ModerateAmberSurface,
                    activeContentColor = PillScopeColors.ModerateAmberDark,
                ),
                label = { Text("Quick Check") },
            )
        }

        val queryTrimmedLen = state.manualQuery.trim().length
        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = viewModel::onManualQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { viewModel.onSearchFocusChanged(it.isFocused) },
            singleLine = true,
            label = { Text("Search medicine brand or generic salt") },
            supportingText = if (queryTrimmedLen in 1..2) {
                { Text("Type at least 3 characters for brand suggestions, or tap Analyze below.") }
            } else {
                null
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    state.isResolving -> LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PillScopeColors.ClinicalTeal,
                    )
                    state.manualQuery.isNotEmpty() -> IconButton(
                        onClick = viewModel::clearManualSearchOnly,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                        )
                    }
                    state.rawOcrText.isNotEmpty() -> IconButton(
                        onClick = viewModel::clearScanState,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear scanned label",
                        )
                    }
                }
            },
            shape = RoundedCornerShape(18.dp),
        )

        state.resolveError?.let { errorMsg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = PillScopeColors.ModerateAmberSurface,
                border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = PillScopeColors.ModerateAmberDark,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        if (state.resolvedCandidates.isNotEmpty()) {
            Text(
                text = "Tap a match to run safety check",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.resolvedCandidates.forEach { candidate ->
                    BrandConfirmationChipCard(
                        candidate = candidate,
                        scanMode = state.scanMode,
                        onConfirm = {
                            onAnalyzeRequest(viewModel.buildAnalyzeRequestForCandidate(candidate))
                        },
                    )
                }
            }
        }

        if (state.localSaltChips.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Detected active ingredients",
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
                TextButton(onClick = viewModel::clearScanState) {
                    Text("Rescan")
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                val count = state.localSaltChips.size
                Text("Check $count detected ${if (count == 1) "ingredient" else "ingredients"}")
            }
        }

        if (state.manualQuery.isNotBlank() && state.resolvedCandidates.isEmpty() && !state.isResolving) {
            Button(
                onClick = {
                    viewModel.buildAnalyzeRequestForShutter()?.let(onAnalyzeRequest)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                val actionPrefix = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                    "Quick check"
                } else {
                    "Check safety for"
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

@Composable
private fun OpticalViewfinderHudOverlay(
    cropBox: NormalizedCropRect,
    ocrTextBlocks: List<OcrTextBlockBox>,
    isAcceptable: Boolean,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPillScopePalette.current
    val bracketColor by animateColorAsState(
        targetValue = if (isAcceptable) palette.hudEmeraldLock else palette.hudAmberGlow,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bracketColor",
    )
    val bracketInsetScale by animateFloatAsState(
        targetValue = if (isAcceptable) 0f else 0.025f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bracketInset",
    )

    Canvas(modifier = modifier) {
        // Compute FILL_CENTER (center-crop) coordinate mapping so normalized OCR boxes and crop
        // brackets match the full-bleed PreviewView without pillarboxing.
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

        for (block in ocrTextBlocks) {
            val bLeft = (contentLeft + block.bounds.left * contentW).coerceIn(0f, size.width)
            val bTop = (contentTop + block.bounds.top * contentH).coerceIn(0f, size.height)
            val bRight = (contentLeft + block.bounds.right * contentW).coerceIn(bLeft, size.width)
            val bBottom = (contentTop + block.bounds.bottom * contentH).coerceIn(bTop, size.height)
            val bWidth = (bRight - bLeft).coerceAtLeast(8f)
            val bHeight = (bBottom - bTop).coerceAtLeast(8f)
            drawRoundRect(
                color = palette.hudEmeraldLock.copy(alpha = 0.22f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = palette.hudEmeraldLock.copy(alpha = 0.78f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        val expandX = size.width * bracketInsetScale
        val expandY = size.height * bracketInsetScale
        val minLeft = 20.dp.toPx()
        val minTop = 64.dp.toPx()
        val maxRight = size.width - 20.dp.toPx()
        val maxBottom = size.height - 112.dp.toPx()
        val left = (contentLeft + cropBox.left * contentW - expandX).coerceIn(minLeft, (maxRight - 48f).coerceAtLeast(minLeft))
        val top = (contentTop + cropBox.top * contentH - expandY).coerceIn(minTop, (maxBottom - 48f).coerceAtLeast(minTop))
        val right = (contentLeft + cropBox.right * contentW + expandX).coerceIn(left + 48f, maxRight)
        val bottom = (contentTop + cropBox.bottom * contentH + expandY).coerceIn(top + 48f, maxBottom)
        val rectWidth = (right - left).coerceAtLeast(40f)
        val rectHeight = (bottom - top).coerceAtLeast(40f)

        val cornerLen = min(rectWidth, rectHeight) * 0.24f
        val strokePx = 4.dp.toPx()
        val glowPx = 9.dp.toPx()

        val corners = listOf(
            Pair(Offset(left, top + cornerLen), Offset(left, top)) to Offset(left + cornerLen, top),
            Pair(Offset(right - cornerLen, top), Offset(right, top)) to Offset(right, top + cornerLen),
            Pair(Offset(left, bottom - cornerLen), Offset(left, bottom)) to Offset(left + cornerLen, bottom),
            Pair(Offset(right - cornerLen, bottom), Offset(right, bottom)) to Offset(right, bottom - cornerLen),
        )

        for ((firstSeg, endPoint) in corners) {
            val (startPoint, vertex) = firstSeg
            drawLine(
                color = bracketColor.copy(alpha = 0.28f),
                start = startPoint,
                end = vertex,
                strokeWidth = glowPx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = bracketColor.copy(alpha = 0.28f),
                start = vertex,
                end = endPoint,
                strokeWidth = glowPx,
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
    }
}

@Composable
private fun QualityHudStatusPill(
    quality: FrameQualityResult?,
    isForcedCaptureHeld: Boolean,
    isCameraFrozen: Boolean,
    modifier: Modifier = Modifier,
) {
    val isOk = quality?.isAcceptable == true || isForcedCaptureHeld
    val accent = when {
        isForcedCaptureHeld -> PillScopeColors.HudEmeraldLock
        isCameraFrozen -> Color.White.copy(alpha = 0.85f)
        quality == null -> Color.White.copy(alpha = 0.85f)
        isOk -> PillScopeColors.HudEmeraldLock
        else -> PillScopeColors.HudAmberGlow
    }
    val statusText = when {
        isForcedCaptureHeld -> "Frame captured — tap Play to resume"
        isCameraFrozen -> "Camera paused — tap Play to resume"
        quality == null -> "Align medicine label inside brackets"
        quality.glareRatio > FrameQualityResult.MAX_GLARE_RATIO -> "Hold steady — glare detected"
        quality.blurScore < FrameQualityResult.MIN_BLUR_SHARPNESS -> "Too blurry — tap to focus"
        else -> "Sharp focus locked (${(quality.blurScore * 100).roundToInt()}%)"
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = statusText,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun BrandConfirmationChipCard(
    candidate: BrandCandidateDto,
    scanMode: ScanMode,
    onConfirm: () -> Unit,
) {
    val saltsDisplay = remember(candidate.genericSalts, candidate.canonicalSaltKeys) {
        formatSaltComponents(candidate.genericSalts, candidate.canonicalSaltKeys)
    }

    Card(
        onClick = onConfirm,
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.brandName,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                        PillScopeColors.ModerateAmberSurface
                    } else {
                        PillScopeColors.ClinicalTealContainer
                    },
                ) {
                    Text(
                        text = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) "Quick check" else "Check safety",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                            PillScopeColors.ModerateAmberDark
                        } else {
                            PillScopeColors.ClinicalTealDeep
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (saltsDisplay.isNotBlank()) {
                Text(
                    text = saltsDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            candidate.manufacturer?.let { mfg ->
                Text(
                    text = mfg,
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
