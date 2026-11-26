package com.pillscope.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.MedicineLabelAnalyzer
import com.pillscope.app.ml.NormalizedCropRect
import com.pillscope.app.ml.OcrTextBlockBox
import com.pillscope.app.ui.profiles.ActiveProfilePill
import com.pillscope.app.ui.theme.PillScopeColors
import java.util.concurrent.Executors
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
    val haptics = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
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
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(352.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PillScopeColors.HudBackdrop),
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithAnalyzer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
                OpticalViewfinderHudOverlay(
                    cropBox = state.latestQuality?.cropBox ?: NormalizedCropRect.DEFAULT_LABEL_BOX,
                    ocrTextBlocks = state.ocrTextBlocks,
                    isAcceptable = currentAcceptable,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Allow camera access to inspect blister packs and medicine labels live.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Enable camera")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActiveProfilePill(
                    profile = activeProfile,
                    onClick = onOpenProfileSwitcher,
                    darkSurface = true,
                )

                IconButton(
                    onClick = viewModel::toggleCameraFreeze,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.58f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (state.isCameraFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.isCameraFrozen) "Resume camera" else "Freeze frame",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QualityHudStatusPill(quality = state.latestQuality)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                            "Sandbox check for ${activeProfile.name}"
                        } else {
                            "Checking against ${activeProfile.name}'s cabinet"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )

                    val canCapture = state.resolvedCandidates.isNotEmpty() ||
                        state.localSaltChips.isNotEmpty() ||
                        state.manualQuery.isNotBlank() ||
                        state.rawOcrText.isNotBlank()

                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.buildAnalyzeRequestForShutter()?.let(onAnalyzeRequest)
                        },
                        enabled = canCapture,
                        shape = CircleShape,
                        color = if (currentAcceptable) {
                            PillScopeColors.HudEmeraldLock
                        } else {
                            PillScopeColors.ClinicalTeal
                        },
                        border = BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)),
                        modifier = Modifier.size(54.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (state.scanMode == ScanMode.CHECK_BEFORE_BUYING) {
                                    Icons.Default.ShoppingBag
                                } else {
                                    Icons.Default.CameraAlt
                                },
                                contentDescription = "Run safety check",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
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
                label = { Text("Add to Cabinet") },
            )
            SegmentedButton(
                selected = state.scanMode == ScanMode.CHECK_BEFORE_BUYING,
                onClick = { viewModel.onScanModeChange(ScanMode.CHECK_BEFORE_BUYING) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PillScopeColors.ModerateAmberSurface,
                    activeContentColor = PillScopeColors.ModerateAmberDark,
                ),
                label = { Text("Check Before Buying") },
            )
        }

        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = viewModel::onManualQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search medicine brand or generic salt") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.isResolving) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PillScopeColors.ClinicalTeal,
                    )
                }
            },
            shape = RoundedCornerShape(18.dp),
        )

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
            Text(
                text = "Detected active salts",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
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
                    AssistChip(
                        onClick = { onAnalyzeRequest(viewModel.buildAnalyzeRequestForLocalChips()) },
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
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = PillScopeColors.ClinicalTealContainer,
                            labelColor = PillScopeColors.ClinicalTealDeep,
                        ),
                    )
                }
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
                    "Preview safety for"
                } else {
                    "Analyze"
                }
                Text("$actionPrefix \"${state.manualQuery.trim()}\"")
            }
        }
    }
}

@Composable
private fun CameraPreviewWithAnalyzer(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
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
                                    onResult = viewModel::onCameraPipelineResult,
                                ),
                            )
                        }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
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
    modifier: Modifier = Modifier,
) {
    val bracketColor by animateColorAsState(
        targetValue = if (isAcceptable) PillScopeColors.HudEmeraldLock else PillScopeColors.HudAmberGlow,
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
        for (block in ocrTextBlocks) {
            val bLeft = block.bounds.left * size.width
            val bTop = block.bounds.top * size.height
            val bWidth = ((block.bounds.right - block.bounds.left) * size.width).coerceAtLeast(8f)
            val bHeight = ((block.bounds.bottom - block.bounds.top) * size.height).coerceAtLeast(8f)
            drawRoundRect(
                color = PillScopeColors.HudEmeraldLock.copy(alpha = 0.22f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = PillScopeColors.HudEmeraldLock.copy(alpha = 0.78f),
                topLeft = Offset(bLeft, bTop),
                size = Size(bWidth, bHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        val expandX = size.width * bracketInsetScale
        val expandY = size.height * bracketInsetScale
        val left = (cropBox.left * size.width - expandX).coerceAtLeast(16.dp.toPx())
        val top = (cropBox.top * size.height - expandY).coerceAtLeast(52.dp.toPx())
        val right = (cropBox.right * size.width + expandX).coerceAtMost(size.width - 16.dp.toPx())
        val bottom = (cropBox.bottom * size.height + expandY).coerceAtMost(size.height - 96.dp.toPx())
        val rectWidth = (right - left).coerceAtLeast(40f)
        val rectHeight = (bottom - top).coerceAtLeast(40f)

        val cornerLen = min(rectWidth, rectHeight) * 0.22f
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
    modifier: Modifier = Modifier,
) {
    val isOk = quality?.isAcceptable == true
    val accent = when {
        quality == null -> Color.White.copy(alpha = 0.85f)
        isOk -> PillScopeColors.HudEmeraldLock
        else -> PillScopeColors.HudAmberGlow
    }
    val statusText = when {
        quality == null -> "Align medicine label inside brackets"
        quality.glareRatio > FrameQualityResult.MAX_GLARE_RATIO -> "Hold steady — glare detected"
        quality.blurScore < FrameQualityResult.MIN_BLUR_SHARPNESS -> "Too blurry"
        else -> "Sharp focus locked (${(quality.blurScore * 100).roundToInt()}%)"
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.72f),
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
                        text = if (scanMode == ScanMode.CHECK_BEFORE_BUYING) "Preview risk" else "Check safety",
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
            Text(
                text = candidate.canonicalSaltKeys.joinToString(" + "),
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.ClinicalTeal,
                fontWeight = FontWeight.SemiBold,
            )
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
