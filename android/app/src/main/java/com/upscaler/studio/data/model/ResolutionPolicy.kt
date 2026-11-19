package com.upscaler.studio.data.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ResolutionPolicy {

    // Every upscaler served by the backend is a 2× model.
    fun nativeScaleForModel(modelKey: String): Int = 2

    fun nativeLabelForModel(modelKey: String): String {
        val scale = nativeScaleForModel(modelKey)
        return "Auto (${scale}× Native)"
    }

    /**
     * Orientation-aware, aspect-preserving output dimensions matching backend/models/upscaler.py.
     * Targets short-side standard resolutions (1080 / 1440 / 2160) while keeping encoder-even sizes
     * and clamping extreme long edges to 4320px.
     */
    fun computeTargetDimensions(
        srcWidth: Int,
        srcHeight: Int,
        targetQuality: String,
        modelKey: String
    ): Pair<Int, Int>? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val nativeScale = nativeScaleForModel(modelKey)
        val q = targetQuality.lowercase()

        val rawTarget = when {
            // 4× = two 2× passes (multipass).
            "4×" in q || "4x" in q -> (srcWidth * 4) to (srcHeight * 4)
            // Plain 2× always doubles the source, whatever the model's native scale.
            "2×" in q || "2x" in q -> (srcWidth * 2) to (srcHeight * 2)
            "auto" in q || "native" in q -> {
                (srcWidth * nativeScale) to (srcHeight * nativeScale)
            }
            "1080" in q || "fhd" in q -> scaleByShortEdge(srcWidth, srcHeight, 1080)
            "2k" in q || "1440" in q -> scaleByShortEdge(srcWidth, srcHeight, 1440)
            "4k" in q || "2160" in q -> scaleByShortEdge(srcWidth, srcHeight, 2160)
            else -> (srcWidth * nativeScale) to (srcHeight * nativeScale)
        }

        return ensureEvenDimensions(rawTarget.first, rawTarget.second)
    }

    private fun scaleByShortEdge(srcWidth: Int, srcHeight: Int, targetShort: Int): Pair<Int, Int> {
        val shortSide = min(srcWidth, srcHeight).toDouble()
        val longSide = max(srcWidth, srcHeight).toDouble()
        var scale = targetShort / shortSide
        val maxLong = 4320.0
        if (longSide * scale > maxLong) {
            scale = maxLong / longSide
        }
        return (srcWidth * scale).roundToInt() to (srcHeight * scale).roundToInt()
    }

    fun ensureEvenDimensions(width: Int, height: Int): Pair<Int, Int> {
        val evenW = max(2, (width + 1) / 2 * 2)
        val evenH = max(2, (height + 1) / 2 * 2)
        return evenW to evenH
    }
}

