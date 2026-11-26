package com.pillscope.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LiteRtLabelPreprocessor(
    private val context: Context,
) : Closeable {
    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer>? = null
    private var outputBuffers: List<TensorBuffer>? = null
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        val assetExists = runCatching {
            context.assets.list("")?.contains(MODEL_ASSET_NAME) == true
        }.getOrDefault(false)
        if (!assetExists) return

        runCatching {
            val model = CompiledModel.create(
                context.assets,
                MODEL_ASSET_NAME,
                CompiledModel.Options(Accelerator.CPU),
            )
            compiledModel = model
            inputBuffers = model.createInputBuffers()
            outputBuffers = model.createOutputBuffers()
        }
    }

    @Synchronized
    fun analyzeFrame(bitmap: Bitmap): FrameQualityResult {
        ensureInitialized()
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized !== bitmap) {
            resized.recycle()
        }

        val model = compiledModel
        val inputs = inputBuffers
        val outputs = outputBuffers
        if (model != null && inputs != null && outputs != null && inputs.isNotEmpty() && outputs.isNotEmpty()) {
            val litertOutput = runCatching {
                val inputTensor = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
                var outIdx = 0
                for (px in pixels) {
                    inputTensor[outIdx++] = ((px shr 16) and 0xFF) / 255f
                    inputTensor[outIdx++] = ((px shr 8) and 0xFF) / 255f
                    inputTensor[outIdx++] = (px and 0xFF) / 255f
                }
                inputs[0].writeFloat(inputTensor)
                model.run(inputs, outputs)
                outputs[0].readFloat()
            }.getOrNull()

            if (litertOutput != null && litertOutput.size >= 7) {
                val blur = litertOutput[0].coerceIn(0f, 1f)
                val glare = litertOutput[1].coerceIn(0f, 1f)
                val crop = NormalizedCropRect(
                    left = litertOutput[2],
                    top = litertOutput[3],
                    right = litertOutput[4],
                    bottom = litertOutput[5],
                ).clamped()
                val cropConf = litertOutput[6].coerceIn(0f, 1f)
                return buildQualityResult(
                    blurScore = blur,
                    glareRatio = glare,
                    cropBox = crop,
                    cropConfidence = cropConf,
                    engine = ENGINE_LITERT,
                )
            }
        }

        return evaluateSignalQualityAndCrop(pixels, INPUT_SIZE, INPUT_SIZE)
    }

    fun cropToLabel(source: Bitmap, cropBox: NormalizedCropRect): Bitmap {
        val safe = cropBox.clamped()
        val x = (safe.left * source.width).toInt().coerceIn(0, max(source.width - 1, 0))
        val y = (safe.top * source.height).toInt().coerceIn(0, max(source.height - 1, 0))
        val w = ((safe.right - safe.left) * source.width).toInt().coerceIn(1, source.width - x)
        val h = ((safe.bottom - safe.top) * source.height).toInt().coerceIn(1, source.height - y)
        return Bitmap.createBitmap(source, x, y, w, h)
    }

    @Synchronized
    override fun close() {
        runCatching { inputBuffers?.forEach { it.close() } }
        runCatching { outputBuffers?.forEach { it.close() } }
        runCatching { compiledModel?.close() }
        inputBuffers = null
        outputBuffers = null
        compiledModel = null
    }

    companion object {
        const val MODEL_ASSET_NAME = "label_quality_cropper.tflite"
        const val INPUT_SIZE = 128
        const val ENGINE_LITERT = "litert_compiled_model"
        const val ENGINE_SIGNAL_FALLBACK = "laplacian_glare_roi"

        fun evaluateSignalQualityAndCrop(
            argbPixels: IntArray,
            width: Int,
            height: Int,
        ): FrameQualityResult {
            val luma = FloatArray(width * height)
            var glarePixels = 0

            for (i in argbPixels.indices) {
                val px = argbPixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val y = (0.299f * r) + (0.587f * g) + (0.114f * b)
                luma[i] = y
                if (r >= 248 && g >= 248 && b >= 248) {
                    glarePixels++
                }
            }

            val glareRatio = (glarePixels.toFloat() / max(argbPixels.size, 1)).coerceIn(0f, 1f)

            var lapSum = 0.0
            var lapSqSum = 0.0
            var count = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var strongEdges = 0

            for (y in 1 until (height - 1)) {
                val rowOffset = y * width
                for (x in 1 until (width - 1)) {
                    val idx = rowOffset + x
                    val lap = (
                        luma[idx - width] +
                            luma[idx + width] +
                            luma[idx - 1] +
                            luma[idx + 1] -
                            (4f * luma[idx])
                        )
                    lapSum += lap
                    lapSqSum += lap * lap
                    count++
                    if (abs(lap) >= 28f) {
                        strongEdges++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val mean = if (count > 0) lapSum / count else 0.0
            val variance = if (count > 0) max(0.0, (lapSqSum / count) - (mean * mean)) else 0.0
            val blurScore = (variance / (variance + 320.0)).toFloat().coerceIn(0f, 1f)

            val hasValidBox = strongEdges >= 24 && (maxX - minX) >= width / 5 && (maxY - minY) >= height / 6
            val cropBox = if (hasValidBox) {
                val padX = width * 0.06f
                val padY = height * 0.06f
                NormalizedCropRect(
                    left = max(0f, (minX - padX) / width),
                    top = max(0f, (minY - padY) / height),
                    right = min(1f, (maxX + padX) / width),
                    bottom = min(1f, (maxY + padY) / height),
                ).clamped()
            } else {
                NormalizedCropRect.DEFAULT_LABEL_BOX
            }

            val cropConfidence = if (hasValidBox) {
                (0.65f + (blurScore * 0.30f)).coerceIn(0f, 0.98f)
            } else {
                0.55f
            }

            return buildQualityResult(
                blurScore = blurScore,
                glareRatio = glareRatio,
                cropBox = cropBox,
                cropConfidence = cropConfidence,
                engine = ENGINE_SIGNAL_FALLBACK,
            )
        }

        private fun buildQualityResult(
            blurScore: Float,
            glareRatio: Float,
            cropBox: NormalizedCropRect,
            cropConfidence: Float,
            engine: String,
        ): FrameQualityResult {
            val tooBlurry = blurScore < FrameQualityResult.MIN_BLUR_SHARPNESS
            val tooMuchGlare = glareRatio > FrameQualityResult.MAX_GLARE_RATIO
            val acceptable = !tooBlurry && !tooMuchGlare
            val guidance = when {
                tooMuchGlare -> "Tilt the medicine strip slightly to reduce foil glare"
                tooBlurry -> "Hold steady and bring the label into sharp focus"
                else -> "Label sharp — reading generic composition"
            }
            return FrameQualityResult(
                blurScore = blurScore,
                glareRatio = glareRatio,
                cropBox = cropBox,
                cropConfidence = cropConfidence,
                isAcceptable = acceptable,
                guidanceMessage = guidance,
                inferenceEngine = engine,
            )
        }
    }
}
