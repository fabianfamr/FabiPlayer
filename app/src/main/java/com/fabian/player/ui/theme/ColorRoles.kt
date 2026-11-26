@file:android.annotation.SuppressLint("RestrictedApi")

package com.fabian.player.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.fabian.player.presentation.viewmodel.ColorSchemePair
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MathUtils
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.fabian.player.data.preferences.AlbumArtColorAccuracy
import com.fabian.player.data.preferences.AlbumArtPaletteStyle
import androidx.core.graphics.scale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

data class ColorScoringConfig(
    val targetChroma: Double = 48.0,
    val weightProportion: Double = 0.7,
    val weightChromaAbove: Double = 0.3,
    val weightChromaBelow: Double = 0.1,
    val cutoffChroma: Double = 5.0,
    val cutoffExcitedProportion: Double = 0.01,
    val maxColorCount: Int = 4,
    val maxHueDifference: Int = 90,
    val minHueDifference: Int = 15
)

data class ColorExtractionConfig(
    val downscaleMaxDimension: Int = 128,
    val quantizerMaxColors: Int = 128,
    val scoring: ColorScoringConfig = ColorScoringConfig(),
    val accuracyLevel: Int = AlbumArtColorAccuracy.DEFAULT
) {
    val normalizedAccuracy: Double
        get() = AlbumArtColorAccuracy.clamp(accuracyLevel).toDouble() / AlbumArtColorAccuracy.MAX.toDouble()
}

private data class ScoredHct(
    val hct: Hct,
    val score: Double
)

private data class RepresentativeArtworkColor(
    val argb: Int,
    val hct: Hct
)

private val extractedColorCache = LruCache<Int, Color>(32)
private const val GRAYSCALE_CHROMA_THRESHOLD = 5.5
private const val NEUTRAL_PIXEL_CHROMA_THRESHOLD = 5.5
private const val HIGH_CHROMA_THRESHOLD = 18.0
private const val REQUIRED_NEUTRAL_POPULATION = 0.92
private const val MAX_HIGH_CHROMA_POPULATION = 0.03
private const val MAX_WEIGHTED_CHROMA_FOR_NEUTRAL = 6.5
private const val MAX_GRAYSCALE_CHANNEL_DELTA = 4
private const val REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD = 10.0
private const val MIN_REPRESENTATIVE_PIXEL_RATIO = 0.04
private const val MIN_REFINEMENT_PIXEL_RATIO = 0.08
private const val MIN_VISIBLE_RGB_SUM = 36
private const val MIN_VISIBLE_PIXEL_ALPHA = 28
private const val FIDELITY_HUE_WINDOW = 90.0
private const val FIDELITY_CHROMA_WINDOW = 32.0
private const val FIDELITY_TONE_WINDOW = 28.0
private const val FIDELITY_HUE_WEIGHT = 18.0
private const val FIDELITY_CHROMA_WEIGHT = 7.0
private const val FIDELITY_TONE_WEIGHT = 3.0
private const val ACCURATE_FIDELITY_HUE_WINDOW = 52.0
private const val ACCURATE_FIDELITY_CHROMA_WINDOW = 18.0
private const val ACCURATE_FIDELITY_TONE_WINDOW = 18.0
private const val ACCURATE_FIDELITY_HUE_WEIGHT = 28.0
private const val ACCURATE_FIDELITY_CHROMA_WEIGHT = 14.0
private const val ACCURATE_FIDELITY_TONE_WEIGHT = 6.0
private const val EXCESS_CHROMA_PENALTY_START = 18.0
private const val EXCESS_CHROMA_PENALTY_WEIGHT = 0.18
private const val ACCURATE_EXCESS_CHROMA_PENALTY_START = 8.0
private const val ACCURATE_EXCESS_CHROMA_PENALTY_WEIGHT = 0.38
private const val LOCAL_REFINEMENT_HUE_WINDOW = 32.0
private const val LOCAL_REFINEMENT_BLEND_RATIO = 0.42f
private const val ACCURATE_LOCAL_REFINEMENT_HUE_WINDOW = 18.0
private const val ACCURATE_LOCAL_REFINEMENT_BLEND_RATIO = 0.72f
private const val ACCURATE_REPRESENTATIVE_BLEND_RATIO = 0.42f
private const val ACCURATE_REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD = 6.0
private const val ACCURATE_CHROMA_ABOVE_WEIGHT = 0.14
private const val ACCURATE_CHROMA_BELOW_WEIGHT = 0.04
private const val ACCURATE_CUTOFF_CHROMA = 3.5
private const val ACCURATE_MAX_HUE_DIFFERENCE = 64
private const val ACCURATE_MIN_HUE_DIFFERENCE = 10

fun clearExtractedColorCache() {
    extractedColorCache.evictAll()
}

fun extractSeedColor(
    bitmap: Bitmap,
    config: ColorExtractionConfig = ColorExtractionConfig()
): Color {
    val cacheKey = 31 * bitmap.hashCode() + config.hashCode()
    extractedColorCache.get(cacheKey)?.let { return it }

    val workingBitmap = resizeForExtraction(bitmap, config.downscaleMaxDimension)

    val seedColor = runCatching {
        val pixels = IntArray(workingBitmap.width * workingBitmap.height)
        workingBitmap.getPixels(
            pixels,
            0,
            workingBitmap.width,
            0,
            0,
            workingBitmap.width,
            workingBitmap.height
        )

        Color(selectSeedColorArgbFromPixels(pixels, config))
    }.getOrElse { DarkColorScheme.primary }

    extractedColorCache.put(cacheKey, seedColor)
    if (workingBitmap !== bitmap) {
        workingBitmap.recycle()
    }
    return seedColor
}

fun generateColorSchemeFromSeed(
    seedColor: Color,
    paletteStyle: AlbumArtPaletteStyle = AlbumArtPaletteStyle.default
): ColorSchemePair {
    return runCatching {
        val seedArgb = seedColor.toArgb()
        val sourceHct = Hct.fromInt(seedArgb)
        val shouldForceNeutral = shouldUseNeutralArtworkScheme(seedArgb, sourceHct)

        val lightDynamic = createDynamicScheme(
            sourceHct = sourceHct,
            paletteStyle = paletteStyle,
            isDark = false
        )
        val darkDynamic = createDynamicScheme(
            sourceHct = sourceHct,
            paletteStyle = paletteStyle,
            isDark = true
        )
        if (shouldForceNeutral) {
            ColorSchemePair(
                light = lightDynamic.toComposeColorScheme().toGrayscaleColorScheme(),
                dark = darkDynamic.toComposeColorScheme().toGrayscaleColorScheme()
            )
        } else {
            ColorSchemePair(
                light = enrichDynamicColorScheme(
                    baseScheme = lightDynamic,
                    sourceHct = sourceHct,
                    paletteStyle = paletteStyle,
                    isDark = false
                ),
                dark = enrichDynamicColorScheme(
                    baseScheme = darkDynamic,
                    sourceHct = sourceHct,
                    paletteStyle = paletteStyle,
                    isDark = true
                )
            )
        }
    }.getOrElse {
        ColorSchemePair(LightColorScheme, DarkColorScheme)
    }
}

fun generateMonochromeColorSchemeFromSeed(seedColor: Color): ColorSchemePair {
    return runCatching {
        val sourceHct = Hct.fromInt(seedColor.toArgb())
        ColorSchemePair(
            light = SchemeMonochrome(sourceHct, false, 0.0).toComposeColorScheme(),
            dark = SchemeMonochrome(sourceHct, true, 0.0).toComposeColorScheme()
        )
    }.getOrElse {
        ColorSchemePair(LightColorScheme, DarkColorScheme)
    }
}

internal fun selectSeedColorArgbFromPixels(
    pixels: IntArray,
    config: ColorExtractionConfig = ColorExtractionConfig()
): Int {
    val fallbackArgb = averageColorArgb(pixels)
    val quantized = QuantizerCelebi.quantize(pixels, config.quantizerMaxColors)
    val mostlyNeutralArtwork = isMostlyNeutralArtwork(quantized)

    if (mostlyNeutralArtwork && isArgbNearGrayscale(fallbackArgb)) {
        return fallbackArgb
    }

    val representativeColor = calculateRepresentativeArtworkColor(
        pixels = pixels,
        accuracy = config.normalizedAccuracy
    )
    val rankedSeeds = scoreQuantizedColors(
        colorsToPopulation = quantized,
        scoring = config.scoring,
        fallbackColorArgb = fallbackArgb,
        representativeColor = representativeColor,
        accuracy = config.normalizedAccuracy
    )
    val selectedSeed = rankedSeeds.firstOrNull() ?: fallbackArgb

    return refineSeedColorArgb(
        candidateArgb = selectedSeed,
        pixels = pixels,
        representativeColor = representativeColor,
        cutoffChroma = config.scoring.cutoffChroma,
        accuracy = config.normalizedAccuracy
    )
}

private fun resizeForExtraction(bitmap: Bitmap, maxDimension: Int): Bitmap {
    if (maxDimension <= 0) return bitmap
    if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height).toFloat()
    return bitmap.scale(
        width = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    )
}

private fun scoreQuantizedColors(
    colorsToPopulation: Map<Int, Int>,
    scoring: ColorScoringConfig,
    fallbackColorArgb: Int,
    representativeColor: RepresentativeArtworkColor?,
    accuracy: Double
): List<Int> {
    if (colorsToPopulation.isEmpty()) return listOf(fallbackColorArgb)

    val colorsHct = ArrayList<Hct>(colorsToPopulation.size)
    val huePopulation = IntArray(360)
    var populationSum = 0.0

    for ((argb, population) in colorsToPopulation) {
        if (population <= 0) continue
        val hct = Hct.fromInt(argb)
        colorsHct.add(hct)
        val hue = MathUtils.sanitizeDegreesInt(floor(hct.hue).toInt())
        huePopulation[hue] += population
        populationSum += population.toDouble()
    }

    if (populationSum <= 0.0) return listOf(fallbackColorArgb)

    val effectiveCutoffChroma = lerpDouble(scoring.cutoffChroma, ACCURATE_CUTOFF_CHROMA, accuracy)
    val effectiveTargetChroma = representativeColor?.let { representative ->
        lerpDouble(scoring.targetChroma, representative.hct.chroma.coerceIn(12.0, 72.0), accuracy * 0.92)
    } ?: scoring.targetChroma
    val chromaAboveWeight = lerpDouble(scoring.weightChromaAbove, ACCURATE_CHROMA_ABOVE_WEIGHT, accuracy)
    val chromaBelowWeight = lerpDouble(scoring.weightChromaBelow, ACCURATE_CHROMA_BELOW_WEIGHT, accuracy)

    val hueExcitedProportions = DoubleArray(360)
    for (hue in 0 until 360) {
        val proportion = huePopulation[hue] / populationSum
        for (neighbor in hue - 14..hue + 15) {
            val wrappedHue = MathUtils.sanitizeDegreesInt(neighbor)
            hueExcitedProportions[wrappedHue] += proportion
        }
    }

    val scoredColors = ArrayList<ScoredHct>(colorsHct.size)
    for (hct in colorsHct) {
        val hue = MathUtils.sanitizeDegreesInt(hct.hue.roundToInt())
        val excitedProportion = hueExcitedProportions[hue]
        if (hct.chroma < effectiveCutoffChroma || excitedProportion <= scoring.cutoffExcitedProportion) {
            continue
        }

        val proportionScore = excitedProportion * 100.0 * scoring.weightProportion
        val chromaWeight =
            if (hct.chroma < effectiveTargetChroma) chromaBelowWeight else chromaAboveWeight
        val chromaScore = (hct.chroma - effectiveTargetChroma) * chromaWeight
        val fidelityScore = representativeColor?.let { representative ->
            calculateRepresentativeFidelityScore(hct, representative.hct, accuracy)
        } ?: 0.0
        val excessChromaPenalty = representativeColor?.let { representative ->
            calculateExcessChromaPenalty(hct, representative.hct, accuracy)
        } ?: 0.0
        scoredColors.add(
            ScoredHct(
                hct = hct,
                score = proportionScore + chromaScore + fidelityScore - excessChromaPenalty
            )
        )
    }

    if (scoredColors.isEmpty()) return listOf(fallbackColorArgb)
    scoredColors.sortByDescending { it.score }

    val minHueDifference = lerpDouble(
        scoring.minHueDifference.toDouble(),
        ACCURATE_MIN_HUE_DIFFERENCE.toDouble(),
        accuracy
    ).roundToInt().coerceAtLeast(1)
    val maxHueDifference = lerpDouble(
        scoring.maxHueDifference.toDouble(),
        ACCURATE_MAX_HUE_DIFFERENCE.toDouble(),
        accuracy
    ).roundToInt().coerceAtLeast(minHueDifference)
    val desiredColorCount = scoring.maxColorCount.coerceAtLeast(1)
    val chosen = mutableListOf<Hct>()

    for (differenceDegrees in maxHueDifference downTo minHueDifference) {
        chosen.clear()
        for (candidate in scoredColors) {
            val isDuplicateHue = chosen.any {
                MathUtils.differenceDegrees(candidate.hct.hue, it.hue) < differenceDegrees.toDouble()
            }
            if (!isDuplicateHue) {
                chosen.add(candidate.hct)
            }
            if (chosen.size >= desiredColorCount) break
        }
        if (chosen.size >= desiredColorCount) break
    }

    if (chosen.isEmpty()) return listOf(fallbackColorArgb)
    return chosen.map { it.toInt() }
}

private fun calculateRepresentativeArtworkColor(
    pixels: IntArray,
    accuracy: Double
): RepresentativeArtworkColor? {
    if (pixels.isEmpty()) return null

    var totalRed = 0.0
    var totalGreen = 0.0
    var totalBlue = 0.0
    var totalWeight = 0.0
    var representativePixelCount = 0
    val chromaThreshold = lerpDouble(
        REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD,
        ACCURATE_REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD,
        accuracy
    )
    val chromaWeightMultiplier = lerpDouble(1.0, 0.42, accuracy)

    for (argb in pixels) {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_VISIBLE_PIXEL_ALPHA) continue

        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        if (red + green + blue <= MIN_VISIBLE_RGB_SUM) continue

        val hct = Hct.fromInt(argb)
        if (hct.chroma < chromaThreshold) continue

        val weight = 1.0 +
            (((hct.chroma - chromaThreshold) / 24.0).coerceAtLeast(0.0) * chromaWeightMultiplier) +
            (hct.tone / 100.0)

        totalRed += red * weight
        totalGreen += green * weight
        totalBlue += blue * weight
        totalWeight += weight
        representativePixelCount++
    }

    if (totalWeight <= 0.0) return null
    if (representativePixelCount.toDouble() / pixels.size.toDouble() < MIN_REPRESENTATIVE_PIXEL_RATIO) {
        return null
    }

    val argb = (
        (0xFF shl 24) or
            ((totalRed / totalWeight).roundToInt().coerceIn(0, 255) shl 16) or
            ((totalGreen / totalWeight).roundToInt().coerceIn(0, 255) shl 8) or
            (totalBlue / totalWeight).roundToInt().coerceIn(0, 255)
        )
    val hct = Hct.fromInt(argb)

    return RepresentativeArtworkColor(argb = argb, hct = hct)
}

private fun calculateRepresentativeFidelityScore(
    candidate: Hct,
    representative: Hct,
    accuracy: Double
): Double {
    val hueDistance = MathUtils.differenceDegrees(candidate.hue, representative.hue)
    val chromaDistance = abs(candidate.chroma - representative.chroma)
    val toneDistance = abs(candidate.tone - representative.tone)
    val hueWindow = lerpDouble(FIDELITY_HUE_WINDOW, ACCURATE_FIDELITY_HUE_WINDOW, accuracy)
    val chromaWindow = lerpDouble(FIDELITY_CHROMA_WINDOW, ACCURATE_FIDELITY_CHROMA_WINDOW, accuracy)
    val toneWindow = lerpDouble(FIDELITY_TONE_WINDOW, ACCURATE_FIDELITY_TONE_WINDOW, accuracy)
    val hueWeight = lerpDouble(FIDELITY_HUE_WEIGHT, ACCURATE_FIDELITY_HUE_WEIGHT, accuracy)
    val chromaWeight = lerpDouble(FIDELITY_CHROMA_WEIGHT, ACCURATE_FIDELITY_CHROMA_WEIGHT, accuracy)
    val toneWeight = lerpDouble(FIDELITY_TONE_WEIGHT, ACCURATE_FIDELITY_TONE_WEIGHT, accuracy)

    val hueScore =
        ((hueWindow - hueDistance).coerceAtLeast(0.0) / hueWindow) * hueWeight
    val chromaScore =
        ((chromaWindow - chromaDistance).coerceAtLeast(0.0) / chromaWindow) * chromaWeight
    val toneScore =
        ((toneWindow - toneDistance).coerceAtLeast(0.0) / toneWindow) * toneWeight

    return hueScore + chromaScore + toneScore
}

private fun calculateExcessChromaPenalty(
    candidate: Hct,
    representative: Hct,
    accuracy: Double
): Double {
    val penaltyStart = lerpDouble(EXCESS_CHROMA_PENALTY_START, ACCURATE_EXCESS_CHROMA_PENALTY_START, accuracy)
    val penaltyWeight = lerpDouble(EXCESS_CHROMA_PENALTY_WEIGHT, ACCURATE_EXCESS_CHROMA_PENALTY_WEIGHT, accuracy)
    val excessChroma = candidate.chroma - representative.chroma - penaltyStart
    if (excessChroma <= 0.0) return 0.0
    return excessChroma * penaltyWeight
}

private fun refineSeedColorArgb(
    candidateArgb: Int,
    pixels: IntArray,
    representativeColor: RepresentativeArtworkColor?,
    cutoffChroma: Double,
    accuracy: Double
): Int {
    if (pixels.isEmpty()) return candidateArgb

    val candidateHct = Hct.fromInt(candidateArgb)
    val effectiveCutoffChroma = lerpDouble(cutoffChroma, ACCURATE_CUTOFF_CHROMA, accuracy)
    val localHueWindow = lerpDouble(LOCAL_REFINEMENT_HUE_WINDOW, ACCURATE_LOCAL_REFINEMENT_HUE_WINDOW, accuracy)
    val localBlendRatio = lerpFloat(
        LOCAL_REFINEMENT_BLEND_RATIO,
        ACCURATE_LOCAL_REFINEMENT_BLEND_RATIO,
        accuracy.toFloat()
    )
    val representativeBlendRatio = lerpFloat(
        LOCAL_REFINEMENT_BLEND_RATIO / 2f,
        ACCURATE_REPRESENTATIVE_BLEND_RATIO,
        accuracy.toFloat()
    )
    var totalRed = 0.0
    var totalGreen = 0.0
    var totalBlue = 0.0
    var totalWeight = 0.0
    var matchingPixelCount = 0

    for (argb in pixels) {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_VISIBLE_PIXEL_ALPHA) continue

        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        if (red + green + blue <= MIN_VISIBLE_RGB_SUM) continue

        val hct = Hct.fromInt(argb)
        if (hct.chroma < effectiveCutoffChroma) continue

        val hueDistance = MathUtils.differenceDegrees(candidateHct.hue, hct.hue)
        if (hueDistance > localHueWindow) continue

        val weight = 1.0 +
            ((localHueWindow - hueDistance) / localHueWindow) +
            ((hct.chroma - effectiveCutoffChroma) / 32.0).coerceAtLeast(0.0)

        totalRed += red * weight
        totalGreen += green * weight
        totalBlue += blue * weight
        totalWeight += weight
        matchingPixelCount++
    }

    if (totalWeight <= 0.0) return candidateArgb
    if (matchingPixelCount.toDouble() / pixels.size.toDouble() < MIN_REFINEMENT_PIXEL_RATIO) {
        return candidateArgb
    }

    val localAverageArgb = (
        (0xFF shl 24) or
            ((totalRed / totalWeight).roundToInt().coerceIn(0, 255) shl 16) or
            ((totalGreen / totalWeight).roundToInt().coerceIn(0, 255) shl 8) or
            (totalBlue / totalWeight).roundToInt().coerceIn(0, 255)
        )
    val localAverageHct = Hct.fromInt(localAverageArgb)
    if (MathUtils.differenceDegrees(candidateHct.hue, localAverageHct.hue) > localHueWindow) {
        return candidateArgb
    }

    val refinedArgb = blendArgb(candidateArgb, localAverageArgb, localBlendRatio)
    if (representativeColor == null) return refinedArgb

    val fidelityHueWindow = lerpDouble(FIDELITY_HUE_WINDOW, ACCURATE_FIDELITY_HUE_WINDOW, accuracy)
    return if (MathUtils.differenceDegrees(localAverageHct.hue, representativeColor.hct.hue) <= fidelityHueWindow) {
        blendArgb(refinedArgb, representativeColor.argb, representativeBlendRatio)
    } else {
        refinedArgb
    }
}

private fun blendArgb(firstArgb: Int, secondArgb: Int, ratio: Float): Int {
    val clampedRatio = ratio.coerceIn(0f, 1f)
    val inverseRatio = 1f - clampedRatio

    val alpha = (
        ((firstArgb ushr 24) and 0xFF) * inverseRatio +
            ((secondArgb ushr 24) and 0xFF) * clampedRatio
        ).roundToInt().coerceIn(0, 255)
    val red = (
        ((firstArgb ushr 16) and 0xFF) * inverseRatio +
            ((secondArgb ushr 16) and 0xFF) * clampedRatio
        ).roundToInt().coerceIn(0, 255)
    val green = (
        ((firstArgb ushr 8) and 0xFF) * inverseRatio +
            ((secondArgb ushr 8) and 0xFF) * clampedRatio
        ).roundToInt().coerceIn(0, 255)
    val blue = (
        (firstArgb and 0xFF) * inverseRatio +
            (secondArgb and 0xFF) * clampedRatio
        ).roundToInt().coerceIn(0, 255)

    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun lerpDouble(start: Double, stop: Double, fraction: Double): Double {
    val clampedFraction = fraction.coerceIn(0.0, 1.0)
    return start + ((stop - start) * clampedFraction)
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    return start + ((stop - start) * clampedFraction)
}

private fun createDynamicScheme(
    sourceHct: Hct,
    paletteStyle: AlbumArtPaletteStyle,
    isDark: Boolean
): DynamicScheme {
    return when (paletteStyle) {
        AlbumArtPaletteStyle.TONAL_SPOT -> SchemeTonalSpot(sourceHct, isDark, 0.0)
        AlbumArtPaletteStyle.VIBRANT -> SchemeVibrant(sourceHct, isDark, 0.0)
        AlbumArtPaletteStyle.EXPRESSIVE -> SchemeExpressive(sourceHct, isDark, 0.0)
        AlbumArtPaletteStyle.FRUIT_SALAD -> SchemeFruitSalad(sourceHct, isDark, 0.0)
    }
}

private data class EnrichedPaletteSpec(
    val neutralChroma: Double,
    val neutralVariantChroma: Double,
    val primaryChroma: Double,
    val secondaryChroma: Double,
    val tertiaryChroma: Double,
    val secondaryHueOverride: Double? = null,
    val tertiaryHueOverride: Double? = null
)

private fun paletteSpecFor(
    paletteStyle: AlbumArtPaletteStyle,
    sourceHct: Hct
): EnrichedPaletteSpec {
    return when (paletteStyle) {
        AlbumArtPaletteStyle.TONAL_SPOT -> EnrichedPaletteSpec(
            neutralChroma = 32.0,
            neutralVariantChroma = 36.0,
            primaryChroma = 48.0,
            secondaryChroma = 42.0,
            tertiaryChroma = 44.0,
            secondaryHueOverride = MathUtils.sanitizeDegreesDouble(sourceHct.hue - 8.0),
            tertiaryHueOverride = MathUtils.sanitizeDegreesDouble(sourceHct.hue + 12.0)
        )
        AlbumArtPaletteStyle.VIBRANT -> EnrichedPaletteSpec(
            neutralChroma = 36.0,
            neutralVariantChroma = 40.0,
            primaryChroma = 56.0,
            secondaryChroma = 48.0,
            tertiaryChroma = 48.0,
            secondaryHueOverride = MathUtils.sanitizeDegreesDouble(sourceHct.hue - 10.0),
            tertiaryHueOverride = MathUtils.sanitizeDegreesDouble(sourceHct.hue + 18.0)
        )
        AlbumArtPaletteStyle.EXPRESSIVE -> EnrichedPaletteSpec(
            neutralChroma = 30.0,
            neutralVariantChroma = 34.0,
            primaryChroma = 48.0,
            secondaryChroma = 42.0,
            tertiaryChroma = 44.0
        )
        AlbumArtPaletteStyle.FRUIT_SALAD -> EnrichedPaletteSpec(
            neutralChroma = 32.0,
            neutralVariantChroma = 36.0,
            primaryChroma = 52.0,
            secondaryChroma = 46.0,
            tertiaryChroma = 46.0
        )
    }
}

private fun hctColor(hue: Double, chroma: Double, tone: Double): Color {
    val sanitizedHue = MathUtils.sanitizeDegreesDouble(hue)
    val clampedChroma = chroma.coerceAtLeast(0.0)
    val clampedTone = tone.coerceIn(0.0, 100.0)
    return Color(Hct.from(sanitizedHue, clampedChroma, clampedTone).toInt())
}

private fun enrichDynamicColorScheme(
    baseScheme: DynamicScheme,
    sourceHct: Hct,
    paletteStyle: AlbumArtPaletteStyle,
    isDark: Boolean
): ColorScheme {
    val spec = paletteSpecFor(paletteStyle, sourceHct)
    val neutralHue = baseScheme.neutralPalette.getHue()
    val neutralVariantHue = baseScheme.neutralVariantPalette.getHue()
    val primaryHue = baseScheme.primaryPalette.getHue()
    val secondaryHue = spec.secondaryHueOverride ?: baseScheme.secondaryPalette.getHue()
    val tertiaryHue = spec.tertiaryHueOverride ?: baseScheme.tertiaryPalette.getHue()

    val primary = hctColor(primaryHue, spec.primaryChroma, if (isDark) 82.0 else 36.0)
    val onPrimary = hctColor(primaryHue, spec.primaryChroma * 0.45, if (isDark) 18.0 else 98.0)
    val primaryContainer = hctColor(primaryHue, spec.primaryChroma * 0.92, if (isDark) 30.0 else 86.0)
    val onPrimaryContainer = hctColor(primaryHue, spec.primaryChroma * 0.35, if (isDark) 93.0 else 12.0)
    val inversePrimary = hctColor(primaryHue, spec.primaryChroma, if (isDark) 38.0 else 80.0)

    val secondary = hctColor(secondaryHue, spec.secondaryChroma, if (isDark) 83.0 else 36.0)
    val onSecondary = hctColor(secondaryHue, spec.secondaryChroma * 0.45, if (isDark) 18.0 else 98.0)
    val secondaryContainer = hctColor(secondaryHue, spec.secondaryChroma * 0.95, if (isDark) 26.0 else 82.0)
    val onSecondaryContainer = hctColor(secondaryHue, spec.secondaryChroma * 0.35, if (isDark) 93.0 else 12.0)

    val tertiary = hctColor(tertiaryHue, spec.tertiaryChroma, if (isDark) 84.0 else 36.0)
    val onTertiary = hctColor(tertiaryHue, spec.tertiaryChroma * 0.45, if (isDark) 18.0 else 98.0)
    val tertiaryContainer = hctColor(tertiaryHue, spec.tertiaryChroma * 0.92, if (isDark) 34.0 else 88.0)
    val onTertiaryContainer = hctColor(tertiaryHue, spec.tertiaryChroma * 0.35, if (isDark) 93.0 else 12.0)

    val background = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.90 else 0.62, if (isDark) 10.5 else 94.5)
    val onBackground = hctColor(neutralHue, spec.neutralChroma * 0.40, if (isDark) 93.0 else 11.0)
    val surface = background
    val onSurface = onBackground
    val surfaceVariant = hctColor(neutralVariantHue, spec.neutralVariantChroma * if (isDark) 1.0 else 0.82, if (isDark) 28.5 else 85.5)
    val onSurfaceVariant = hctColor(neutralVariantHue, spec.neutralVariantChroma * 0.55, if (isDark) 84.0 else 28.0)
    val surfaceTint = primary
    val inverseSurface = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.55 else 0.75, if (isDark) 90.0 else 20.0)
    val inverseOnSurface = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.60 else 0.45, if (isDark) 18.0 else 94.0)

    val outline = hctColor(neutralVariantHue, spec.neutralVariantChroma * 0.72, if (isDark) 62.0 else 46.0)
    val outlineVariant = hctColor(neutralVariantHue, spec.neutralVariantChroma * 0.88, if (isDark) 34.0 else 76.0)

    val surfaceDim = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.85 else 0.72, if (isDark) 7.5 else 85.0)
    val surfaceBright = hctColor(neutralHue, spec.neutralChroma * if (isDark) 1.0 else 0.55, if (isDark) 26.5 else 97.5)
    val surfaceContainerLowest = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.80 else 0.45, if (isDark) 6.5 else 98.5)
    val surfaceContainerLow = hctColor(neutralHue, spec.neutralChroma * if (isDark) 0.98 else 0.68, if (isDark) 14.5 else 92.0)
    val surfaceContainer = hctColor(neutralHue, spec.neutralChroma * if (isDark) 1.05 else 0.75, if (isDark) 18.0 else 89.0)
    val surfaceContainerHigh = hctColor(neutralVariantHue, spec.neutralVariantChroma * if (isDark) 1.0 else 0.78, if (isDark) 22.0 else 86.0)
    val surfaceContainerHighest = hctColor(neutralVariantHue, spec.neutralVariantChroma * if (isDark) 1.05 else 0.82, if (isDark) 26.0 else 83.0)

    val primaryFixed = hctColor(primaryHue, spec.primaryChroma * 0.90, 86.0)
    val primaryFixedDim = hctColor(primaryHue, spec.primaryChroma * 0.95, 78.0)
    val onPrimaryFixed = hctColor(primaryHue, spec.primaryChroma * 0.35, 12.0)
    val onPrimaryFixedVariant = hctColor(primaryHue, spec.primaryChroma * 0.60, 28.0)

    val secondaryFixed = hctColor(secondaryHue, spec.secondaryChroma * 0.95, 86.0)
    val secondaryFixedDim = hctColor(secondaryHue, spec.secondaryChroma, 78.0)
    val onSecondaryFixed = hctColor(secondaryHue, spec.secondaryChroma * 0.35, 12.0)
    val onSecondaryFixedVariant = hctColor(secondaryHue, spec.secondaryChroma * 0.60, 28.0)

    val tertiaryFixed = hctColor(tertiaryHue, spec.tertiaryChroma * 0.95, 86.0)
    val tertiaryFixedDim = hctColor(tertiaryHue, spec.tertiaryChroma, 78.0)
    val onTertiaryFixed = hctColor(tertiaryHue, spec.tertiaryChroma * 0.35, 12.0)
    val onTertiaryFixedVariant = hctColor(tertiaryHue, spec.tertiaryChroma * 0.60, 28.0)

    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = Color(baseScheme.getError()),
        onError = Color(baseScheme.getOnError()),
        errorContainer = Color(baseScheme.getErrorContainer()),
        onErrorContainer = Color(baseScheme.getOnErrorContainer()),
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color(baseScheme.getScrim()),
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = primaryFixed,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = onPrimaryFixed,
        onPrimaryFixedVariant = onPrimaryFixedVariant,
        secondaryFixed = secondaryFixed,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = onSecondaryFixed,
        onSecondaryFixedVariant = onSecondaryFixedVariant,
        tertiaryFixed = tertiaryFixed,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = onTertiaryFixed,
        onTertiaryFixedVariant = onTertiaryFixedVariant
    )
}

private fun averageColorArgb(pixels: IntArray): Int {
    if (pixels.isEmpty()) return DarkColorScheme.primary.toArgb()

    var totalRed = 0L
    var totalGreen = 0L
    var totalBlue = 0L

    for (argb in pixels) {
        totalRed += (argb ushr 16) and 0xFF
        totalGreen += (argb ushr 8) and 0xFF
        totalBlue += argb and 0xFF
    }

    val size = pixels.size.toLong()
    val r = (totalRed / size).toInt().coerceIn(0, 255)
    val g = (totalGreen / size).toInt().coerceIn(0, 255)
    val b = (totalBlue / size).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun isMostlyNeutralArtwork(colorsToPopulation: Map<Int, Int>): Boolean {
    if (colorsToPopulation.isEmpty()) return false

    var totalPopulation = 0.0
    var neutralPopulation = 0.0
    var highChromaPopulation = 0.0
    var weightedChroma = 0.0

    for ((argb, populationInt) in colorsToPopulation) {
        if (populationInt <= 0) continue
        val population = populationInt.toDouble()
        val chroma = Hct.fromInt(argb).chroma

        totalPopulation += population
        weightedChroma += chroma * population

        if (chroma <= NEUTRAL_PIXEL_CHROMA_THRESHOLD) {
            neutralPopulation += population
        }
        if (chroma >= HIGH_CHROMA_THRESHOLD) {
            highChromaPopulation += population
        }
    }

    if (totalPopulation <= 0.0) return false

    val neutralRatio = neutralPopulation / totalPopulation
    val highChromaRatio = highChromaPopulation / totalPopulation
    val meanChroma = weightedChroma / totalPopulation

    return neutralRatio >= REQUIRED_NEUTRAL_POPULATION &&
        highChromaRatio <= MAX_HIGH_CHROMA_POPULATION &&
        meanChroma <= MAX_WEIGHTED_CHROMA_FOR_NEUTRAL
}

private fun shouldUseNeutralArtworkScheme(argb: Int, sourceHct: Hct): Boolean {
    return sourceHct.chroma <= GRAYSCALE_CHROMA_THRESHOLD &&
        isArgbNearGrayscale(argb)
}

private fun isArgbNearGrayscale(argb: Int): Boolean {
    val red = (argb ushr 16) and 0xFF
    val green = (argb ushr 8) and 0xFF
    val blue = argb and 0xFF
    return maxOf(
        abs(red - green),
        abs(green - blue),
        abs(red - blue)
    ) <= MAX_GRAYSCALE_CHANNEL_DELTA
}

private fun ColorScheme.toGrayscaleColorScheme(): ColorScheme {
    fun convert(color: Color): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[0] = 0f
        hsl[1] = 0f
        return Color(ColorUtils.HSLToColor(hsl))
    }

    return copy(
        primary = convert(primary),
        onPrimary = convert(onPrimary),
        primaryContainer = convert(primaryContainer),
        onPrimaryContainer = convert(onPrimaryContainer),
        inversePrimary = convert(inversePrimary),
        secondary = convert(secondary),
        onSecondary = convert(onSecondary),
        secondaryContainer = convert(secondaryContainer),
        onSecondaryContainer = convert(onSecondaryContainer),
        tertiary = convert(tertiary),
        onTertiary = convert(onTertiary),
        tertiaryContainer = convert(tertiaryContainer),
        onTertiaryContainer = convert(onTertiaryContainer),
        background = convert(background),
        onBackground = convert(onBackground),
        surface = convert(surface),
        onSurface = convert(onSurface),
        surfaceVariant = convert(surfaceVariant),
        onSurfaceVariant = convert(onSurfaceVariant),
        surfaceTint = convert(surfaceTint),
        inverseSurface = convert(inverseSurface),
        inverseOnSurface = convert(inverseOnSurface),
        error = convert(error),
        onError = convert(onError),
        errorContainer = convert(errorContainer),
        onErrorContainer = convert(onErrorContainer),
        outline = convert(outline),
        outlineVariant = convert(outlineVariant),
        scrim = convert(scrim),
        surfaceBright = convert(surfaceBright),
        surfaceDim = convert(surfaceDim),
        surfaceContainer = convert(surfaceContainer),
        surfaceContainerHigh = convert(surfaceContainerHigh),
        surfaceContainerHighest = convert(surfaceContainerHighest),
        surfaceContainerLow = convert(surfaceContainerLow),
        surfaceContainerLowest = convert(surfaceContainerLowest),
        primaryFixed = convert(primaryFixed),
        primaryFixedDim = convert(primaryFixedDim),
        onPrimaryFixed = convert(onPrimaryFixed),
        onPrimaryFixedVariant = convert(onPrimaryFixedVariant),
        secondaryFixed = convert(secondaryFixed),
        secondaryFixedDim = convert(secondaryFixedDim),
        onSecondaryFixed = convert(onSecondaryFixed),
        onSecondaryFixedVariant = convert(onSecondaryFixedVariant),
        tertiaryFixed = convert(tertiaryFixed),
        tertiaryFixedDim = convert(tertiaryFixedDim),
        onTertiaryFixed = convert(onTertiaryFixed),
        onTertiaryFixedVariant = convert(onTertiaryFixedVariant)
    )
}

private fun DynamicScheme.toComposeColorScheme(): ColorScheme {
    return ColorScheme(
        primary = Color(getPrimary()),
        onPrimary = Color(getOnPrimary()),
        primaryContainer = Color(getPrimaryContainer()),
        onPrimaryContainer = Color(getOnPrimaryContainer()),
        inversePrimary = Color(getInversePrimary()),
        secondary = Color(getSecondary()),
        onSecondary = Color(getOnSecondary()),
        secondaryContainer = Color(getSecondaryContainer()),
        onSecondaryContainer = Color(getOnSecondaryContainer()),
        tertiary = Color(getTertiary()),
        onTertiary = Color(getOnTertiary()),
        tertiaryContainer = Color(getTertiaryContainer()),
        onTertiaryContainer = Color(getOnTertiaryContainer()),
        background = Color(getBackground()),
        onBackground = Color(getOnBackground()),
        surface = Color(getSurface()),
        onSurface = Color(getOnSurface()),
        surfaceVariant = Color(getSurfaceVariant()),
        onSurfaceVariant = Color(getOnSurfaceVariant()),
        surfaceTint = Color(getSurfaceTint()),
        inverseSurface = Color(getInverseSurface()),
        inverseOnSurface = Color(getInverseOnSurface()),
        error = Color(getError()),
        onError = Color(getOnError()),
        errorContainer = Color(getErrorContainer()),
        onErrorContainer = Color(getOnErrorContainer()),
        outline = Color(getOutline()),
        outlineVariant = Color(getOutlineVariant()),
        scrim = Color(getScrim()),
        surfaceBright = Color(getSurfaceBright()),
        surfaceDim = Color(getSurfaceDim()),
        surfaceContainer = Color(getSurfaceContainer()),
        surfaceContainerHigh = Color(getSurfaceContainerHigh()),
        surfaceContainerHighest = Color(getSurfaceContainerHighest()),
        surfaceContainerLow = Color(getSurfaceContainerLow()),
        surfaceContainerLowest = Color(getSurfaceContainerLowest()),
        primaryFixed = Color(getPrimaryFixed()),
        primaryFixedDim = Color(getPrimaryFixedDim()),
        onPrimaryFixed = Color(getOnPrimaryFixed()),
        onPrimaryFixedVariant = Color(getOnPrimaryFixedVariant()),
        secondaryFixed = Color(getSecondaryFixed()),
        secondaryFixedDim = Color(getSecondaryFixedDim()),
        onSecondaryFixed = Color(getOnSecondaryFixed()),
        onSecondaryFixedVariant = Color(getOnSecondaryFixedVariant()),
        tertiaryFixed = Color(getTertiaryFixed()),
        tertiaryFixedDim = Color(getTertiaryFixedDim()),
        onTertiaryFixed = Color(getOnTertiaryFixed()),
        onTertiaryFixedVariant = Color(getOnTertiaryFixedVariant())
    )
}
