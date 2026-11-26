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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.ml.FrameQualityResult
import com.pillscope.app.ml.MedicineLabelAnalyzer
import com.pillscope.app.ml.NormalizedCropRect
import com.pillscope.app.ui.theme.PillScopeColors
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    onAnalyzeRequest: (ScanAnalyzeRequestDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Scan Medicine Strip",
            style = MaterialTheme.typography.headlineSmall,
            color = PillScopeColors.InkPrimary,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0F172A)),
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithAnalyzer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
                CropReticleOverlay(
                    cropBox = state.latestQuality?.cropBox ?: NormalizedCropRect.DEFAULT_LABEL_BOX,
                    isAcceptable = state.latestQuality?.isAcceptable ?: false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Camera permission needed for live strip scanning",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Access")
                    }
                }
            }

            IconButton(
                onClick = viewModel::toggleCameraFreeze,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
            ) {
                Icon(
                    imageVector = if (state.isCameraFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (state.isCameraFrozen) "Resume scan" else "Freeze frame",
                    tint = Color.White,
                )
            }

            state.latestQuality?.let { quality ->
                QualityOverlayBar(
                    quality = quality,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp),
                )
            }
        }

        OutlinedTextField(
            value = state.manualQuery,
            onValueChange = viewModel::onManualQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search brand or generic salt (e.g., Augmentin 625 Duo)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            shape = RoundedCornerShape(14.dp),
        )

        if (state.resolvedCandidates.isNotEmpty()) {
            Text(
                text = "1-Tap Generic Salt Confirmation",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.resolvedCandidates.forEach { candidate ->
                    BrandConfirmationChipCard(
                        candidate = candidate,
                        onConfirm = {
                            onAnalyzeRequest(viewModel.buildAnalyzeRequestForCandidate(candidate))
                        },
                    )
                }
            }
        }

        if (state.localSaltChips.isNotEmpty()) {
            Text(
                text = "On-Device OCR Salts Detected",
                style = MaterialTheme.typography.titleMedium,
                color = PillScopeColors.InkPrimary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.localSaltChips.forEach { chip ->
                    val label = if (chip.strengthText != null) {
                        "${chip.canonicalSalt} (${chip.strengthText})"
                    } else {
                        chip.canonicalSalt
                    }
                    AssistChip(
                        onClick = { onAnalyzeRequest(viewModel.buildAnalyzeRequestForLocalChips()) },
                        label = { Text(label) },
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
                        ),
                    )
                }
            }
        }

        if (state.manualQuery.isNotBlank() && state.resolvedCandidates.isEmpty() && !state.isResolving) {
            Button(
                onClick = {
                    onAnalyzeRequest(
                        ScanAnalyzeRequestDto(
                            inputMode = "MANUAL_TEXT",
                            submittedQuery = state.manualQuery.trim(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Analyze \"${state.manualQuery.trim()}\"")
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
private fun CropReticleOverlay(
    cropBox: NormalizedCropRect,
    isAcceptable: Boolean,
    modifier: Modifier = Modifier,
) {
    val strokeColor = if (isAcceptable) Color(0xFF22C55E) else Color(0xFFF59E0B)
    Canvas(modifier = modifier) {
        val left = cropBox.left * size.width
        val top = cropBox.top * size.height
        val width = (cropBox.right - cropBox.left) * size.width
        val height = (cropBox.bottom - cropBox.top) * size.height
        drawRect(
            color = strokeColor,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun QualityOverlayBar(
    quality: FrameQualityResult,
    modifier: Modifier = Modifier,
) {
    val badgeColor = if (quality.isAcceptable) {
        PillScopeColors.SafeEmerald
    } else {
        PillScopeColors.ModerateAmber
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.72f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (quality.isAcceptable) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = quality.guidanceMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sharpness ${(quality.blurScore * 100).roundToInt()}% · " +
                    "Glare ${(quality.glareRatio * 100).roundToInt()}% · ${quality.inferenceEngine}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun BrandConfirmationChipCard(
    candidate: BrandCandidateDto,
    onConfirm: () -> Unit,
) {
    Card(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
        border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.4f)),
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
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PillScopeColors.ClinicalTealContainer,
                ) {
                    Text(
                        text = "Tap to Check",
                        style = MaterialTheme.typography.labelSmall,
                        color = PillScopeColors.ClinicalTeal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                    text = "$mfg · ${candidate.matchStrategy}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                )
            }
        }
    }
}
