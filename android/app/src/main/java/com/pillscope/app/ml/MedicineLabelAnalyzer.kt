package com.pillscope.app.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MedicineLabelAnalyzer(
    private val preprocessor: LiteRtLabelPreprocessor,
    private val ocrEngine: MlKitOcrEngine,
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 350L,
    private val forceNextOcr: AtomicBoolean = AtomicBoolean(false),
    private val onResult: (CameraPipelineResult) -> Unit,
) : ImageAnalysis.Analyzer {
    private val isProcessing = AtomicBoolean(false)
    private val lastProcessedMs = AtomicLong(0L)

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs.get() < minIntervalMs || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastProcessedMs.set(now)

        val uprightBitmap = runCatching {
            val rawBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation == 0) {
                rawBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true,
                )
                rawBitmap.recycle()
                rotated
            }
        }.getOrNull()

        imageProxy.close()

        if (uprightBitmap == null) {
            isProcessing.set(false)
            return
        }

        scope.launch(Dispatchers.Default) {
            try {
                val quality = preprocessor.analyzeFrame(uprightBitmap)
                val forced = forceNextOcr.getAndSet(false)
                if (!quality.isAcceptable && !forced) {
                    onResult(
                        CameraPipelineResult(
                            quality = quality,
                            rawOcrText = "",
                            localSaltChips = emptyList(),
                            primaryBrandGuess = "",
                            frameWidth = uprightBitmap.width,
                            frameHeight = uprightBitmap.height,
                        ),
                    )
                    return@launch
                }

                val effectiveCropBox = if (forced) {
                    NormalizedCropRect(0f, 0f, 1f, 1f)
                } else {
                    quality.cropBox
                }
                val cropped = preprocessor.cropToLabel(uprightBitmap, effectiveCropBox)
                val ocrResult = try {
                    ocrEngine.recognizeTextWithBlocks(
                        bitmap = cropped,
                        parentCropBox = effectiveCropBox,
                    )
                } finally {
                    if (cropped !== uprightBitmap) {
                        cropped.recycle()
                    }
                }

                val chips = OnDeviceSaltChipExtractor.extractSaltChips(ocrResult.rawText)
                val brandGuess = OnDeviceSaltChipExtractor.extractPrimaryBrandGuess(ocrResult.rawText)
                onResult(
                    CameraPipelineResult(
                        quality = if (forced) quality.copy(isAcceptable = true) else quality,
                        rawOcrText = ocrResult.rawText,
                        localSaltChips = chips,
                        primaryBrandGuess = brandGuess,
                        ocrTextBlocks = ocrResult.blocks,
                        frameWidth = uprightBitmap.width,
                        frameHeight = uprightBitmap.height,
                    ),
                )
            } finally {
                uprightBitmap.recycle()
                isProcessing.set(false)
            }
        }
    }
}
