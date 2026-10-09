package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.telemetry.FramePacingMetrics
import com.nitroboost.app.core.telemetry.FrameSnapshot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Windowed diagnostics over real FPS counters, gfxinfo frame times and
 * IntendedVsync deltas. No FPS is inferred from frame duration.
 *
 * Stability is a transparent weighted penalty over frame-time coefficient of
 * variation, measured hitch rate, and estimated missed-vsync rate. Smoothness
 * uses P95/P99 excess over the target budget plus those same late/drop rates.
 * Composite scores are withheld until at least five frame-time samples exist.
 */
object FramePacingAnalyzer {

    const val MIN_SCORE_FRAME_SAMPLES = 5
    const val STABILITY_CV_FULL_PENALTY = 0.50

    fun analyze(
        samples: List<FrameSnapshot>,
        targetFps: Int? = samples.mapNotNull { it.targetFps }.firstOrNull { it in 1..MAX_FPS }
    ): FramePacingMetrics? {
        val safeTarget = targetFps?.takeIf { it in 1..MAX_FPS }
        val fpsValues = samples.asSequence().mapNotNull { sample ->
            sample.fps?.takeIf { it.isFinite() && it in MIN_FPS..MAX_FPS.toDouble() && it > 0.0 }
        }.take(MAX_INPUT_SAMPLES).toList().sorted()
        val frameTimes = samples.asSequence().flatMap { it.frameTimesMs.orEmpty().asSequence() }
            .filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }
            .take(MAX_INPUT_FRAME_SAMPLES).toList()
        val intervals = samples.asSequence()
            .flatMap { it.intendedVsyncIntervalsMs.orEmpty().asSequence() }
            .filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }
            .take(MAX_INPUT_FRAME_SAMPLES).toList()
        val distribution = FrameTimeAnalysis.distribution(frameTimes)
        val targetFrameMetrics = safeTarget?.let { FrameTimeAnalysis.summarize(frameTimes, it) }
        val dropRate = estimateDroppedFrameRate(intervals, safeTarget)
        val jankRate = targetFrameMetrics?.hitchRate

        if (fpsValues.isEmpty() && distribution == null && intervals.isEmpty()) return null

        val enoughFrames = distribution != null &&
            distribution.frameCount >= MIN_SCORE_FRAME_SAMPLES
        val stabilityScore = if (!enoughFrames) {
            null
        } else {
            val dist = distribution!!
            val coefficientOfVariation = if (dist.meanMs > 0.0) {
                sqrt(dist.varianceMs2) / dist.meanMs
            } else 1.0
            val penalties = buildList {
                add((coefficientOfVariation / STABILITY_CV_FULL_PENALTY).coerceIn(0.0, 1.0) to 0.50)
                jankRate?.let { add(it.coerceIn(0.0, 1.0) to 0.25) }
                dropRate?.let { add(it.coerceIn(0.0, 1.0) to 0.25) }
            }
            scoreFromPenalties(penalties)
        }

        val smoothnessScore = if (!enoughFrames || safeTarget == null || distribution == null) {
            null
        } else {
            val budgetMs = 1_000.0 / safeTarget
            val penalties = buildList {
                add(excessBudgetPenalty(distribution.p95Ms, budgetMs) to 0.40)
                add(excessBudgetPenalty(distribution.p99Ms, budgetMs) to 0.30)
                jankRate?.let { add(it.coerceIn(0.0, 1.0) to 0.20) }
                dropRate?.let { add(it.coerceIn(0.0, 1.0) to 0.10) }
            }
            scoreFromPenalties(penalties)
        }

        return FramePacingMetrics(
            targetFps = safeTarget,
            averageFps = fpsValues.takeIf { it.isNotEmpty() }?.average(),
            medianFps = fpsValues.takeIf { it.isNotEmpty() }?.let(::median),
            meanFrameTimeMs = distribution?.meanMs,
            medianFrameTimeMs = distribution?.medianMs,
            p95FrameTimeMs = distribution?.p95Ms,
            p99FrameTimeMs = distribution?.p99Ms,
            frameTimeVarianceMs2 = distribution?.varianceMs2,
            jankRate = jankRate,
            estimatedDroppedFrameRate = dropRate,
            stabilityScore = stabilityScore,
            smoothnessScore = smoothnessScore,
            fpsSampleCount = fpsValues.size,
            frameSampleCount = distribution?.frameCount ?: 0,
            intendedVsyncIntervalCount = intervals.size,
            onePercentLowFps = fpsValues
                .takeIf { it.size >= MIN_SCORE_FRAME_SAMPLES }
                ?.let(::onePercentLow)
        )
    }

    /**
     * Estimated missed refresh slots / expected slots from consecutive
     * IntendedVsync gaps. This is null without a valid target or measured gaps.
     */
    private fun estimateDroppedFrameRate(intervalsMs: List<Double>, targetFps: Int?): Double? {
        val target = targetFps?.takeIf { it in 1..MAX_FPS } ?: return null
        if (intervalsMs.isEmpty()) return null
        val expectedFrameMs = 1_000.0 / target
        var expectedSlots = 0.0
        var missedSlots = 0.0
        for (interval in intervalsMs) {
            if (!interval.isFinite() || interval !in MIN_FRAME_MS..MAX_FRAME_MS) continue
            val slots = interval / expectedFrameMs
            expectedSlots += max(slots, 1.0)
            missedSlots += (slots - 1.0).coerceAtLeast(0.0)
        }
        if (expectedSlots <= 0.0) return null
        return (missedSlots / expectedSlots).coerceIn(0.0, 1.0)
    }

    private fun excessBudgetPenalty(valueMs: Double, budgetMs: Double): Double =
        ((valueMs / budgetMs) - 1.0).coerceIn(0.0, 1.0)

    private fun scoreFromPenalties(penalties: List<Pair<Double, Double>>): Double? {
        val totalWeight = penalties.sumOf { it.second }
        if (totalWeight <= 0.0) return null
        val penalty = penalties.sumOf { (value, weight) -> value.coerceIn(0.0, 1.0) * weight } /
            totalWeight
        return ((1.0 - penalty) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun median(sorted: List<Double>): Double = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    /** Standard 1% low: average of the slowest 1% of valid samples. */
    private fun onePercentLow(sortedAscending: List<Double>): Double {
        val count = (sortedAscending.size * 0.01).toInt().coerceAtLeast(1)
        return sortedAscending.take(count).average()
    }

    private const val MIN_FPS = 0.0
    private const val MAX_FPS = 1_000
    private const val MIN_FRAME_MS = 0.1
    private const val MAX_FRAME_MS = 1_000.0
    private const val MAX_INPUT_SAMPLES = 4_096
    private const val MAX_INPUT_FRAME_SAMPLES = 20_000
}
