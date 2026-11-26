package com.pillscope.app.ml

import com.pillscope.app.data.remote.dto.EdgeQualityMetricsDto

data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun clamped(): NormalizedCropRect {
        val l = left.coerceIn(0f, 0.85f)
        val t = top.coerceIn(0f, 0.85f)
        val r = right.coerceIn(l + 0.15f, 1f)
        val b = bottom.coerceIn(t + 0.15f, 1f)
        return NormalizedCropRect(l, t, r, b)
    }

    companion object {
        val DEFAULT_LABEL_BOX = NormalizedCropRect(0.08f, 0.20f, 0.92f, 0.80f)
    }
}

data class OcrTextBlockBox(
    val text: String,
    val bounds: NormalizedCropRect,
)

data class FrameQualityResult(
    val blurScore: Float,
    val glareRatio: Float,
    val cropBox: NormalizedCropRect,
    val cropConfidence: Float,
    val isAcceptable: Boolean,
    val guidanceMessage: String,
    val inferenceEngine: String,
) {
    fun toMetricsDto(): EdgeQualityMetricsDto = EdgeQualityMetricsDto(
        blurScore = blurScore,
        glareRatio = glareRatio,
        cropConfidence = cropConfidence,
    )

    companion object {
        const val MIN_BLUR_SHARPNESS = 0.42f
        const val MAX_GLARE_RATIO = 0.22f
    }
}

data class LocalSaltChip(
    val canonicalSalt: String,
    val strengthText: String? = null,
)

data class CameraPipelineResult(
    val quality: FrameQualityResult,
    val rawOcrText: String,
    val localSaltChips: List<LocalSaltChip>,
    val primaryBrandGuess: String,
    val ocrTextBlocks: List<OcrTextBlockBox> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val wasForced: Boolean = false,
)
