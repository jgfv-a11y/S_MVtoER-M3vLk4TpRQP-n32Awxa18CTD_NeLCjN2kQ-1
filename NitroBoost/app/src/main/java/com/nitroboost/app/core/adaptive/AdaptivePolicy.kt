package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.ThermalGuard
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Statistical and objective model for adaptive trials.
 *
 * The v2 decision is based on bounded, normalized changes in average FPS,
 * lower-tail FPS, real frame-time stability, hitch rate, memory pressure and
 * energy (only if measured). Thermal temperature, slope and tier are explicit
 * costs. The weight budget is documented below: 60% performance objective,
 * 40% thermal risk; the available performance indicators share their budget
 * equally so no unnormalized unit can dominate.
 *
 * Multi-variant intervals use Bonferroni family-wise correction: each arm is
 * assessed against its own paired baseline with alpha / numberOfArms. No
 * winner is selected before its corrected interval is calculated.
 */
object AdaptivePolicy {

    private val T_TABLE_95 = doubleArrayOf(
        0.0,
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    )

    /** Balanced normalized performance score; the constants are public for deterministic tests. */
    object Weights {
        const val PERFORMANCE = 0.60
        const val THERMAL_TEMPERATURE = 0.15
        const val THERMAL_SLOPE = 0.15
        const val THERMAL_TIER = 0.10
    }

    const val FAMILY_WISE_CONFIDENCE = 0.95
    const val TEMP_EFFECT_SCALE_C = 2.0
    const val SLOPE_EFFECT_SCALE_C_PER_MIN = 1.2
    const val MIN_HITCH_RATE_SCALE = 0.02
    const val SOFT_THERMAL_HEADROOM_C = 6.0

    /** Legacy one-comparison 95% critical value. */
    fun tQuantile(df: Int): Double = tCritical(df, comparisons = 1)

    /**
     * Student-t critical value for a two-sided family-wise confidence level.
     * Bonferroni uses alpha / comparisons for each individual comparison.
     */
    fun tCritical(df: Int, comparisons: Int): Double {
        if (df < 1) return Double.POSITIVE_INFINITY
        if (comparisons <= 1) {
            return when {
                df <= 30 -> T_TABLE_95[df]
                else -> 2.0 // conservative large-df approximation; exact 95% z is 1.96
            }
        }
        val alpha = (1.0 - FAMILY_WISE_CONFIDENCE) / comparisons.coerceAtLeast(1)
        var lo = 0.0
        var hi = 1.0
        while (twoSidedStudentTail(hi, df) > alpha && hi < 1_000_000.0) hi *= 2.0
        repeat(90) {
            val mid = (lo + hi) / 2.0
            if (twoSidedStudentTail(mid, df) > alpha) lo = mid else hi = mid
        }
        return hi
    }

    /** Paired differences (arm - baseline), truncated to the shorter list. */
    fun deltasOf(baseline: List<Int>, arm: List<Int>): List<Double> {
        val n = minOf(baseline.size, arm.size)
        return (0 until n).map { arm[it].toDouble() - baseline[it].toDouble() }
    }

    /** Retained for the legacy FPS-only ledger format; v2 block scores are not trimmed. */
    fun trimmedDeltas(deltas: List<Double>): List<Double> {
        if (deltas.size < 6) return deltas
        val sorted = deltas.sorted()
        val k = deltas.size / 10
        return sorted.subList(k, sorted.size - k)
    }

    /**
     * Compare two measured windows using the normalized multi-objective model.
     * Frame-time/hitch/energy dimensions are omitted if either side lacks an
     * actual source; FPS is never used to synthesize those values.
     */
    fun scoreComparison(baseline: WindowMetrics, candidate: WindowMetrics): ScoreComponents {
        val fpsGain = normalizedGain(candidate.fpsMean - baseline.fpsMean, baseline.fpsMean)
        val lowFpsGain = normalizedGain(candidate.lowFps - baseline.lowFps, baseline.lowFps)

        val stabilityGain = if (baseline.frameTime != null && candidate.frameTime != null) {
            val b = baseline.frameTime
            val c = candidate.frameTime
            listOf(
                normalizedGain(b.p95Ms - c.p95Ms, b.p95Ms),
                normalizedGain(b.p99Ms - c.p99Ms, b.p99Ms),
                normalizedGain(b.varianceMs2 - c.varianceMs2, b.varianceMs2)
            ).average().coerceIn(-1.0, 1.0)
        } else null

        val hitchGain = if (baseline.frameTime != null && candidate.frameTime != null) {
            ((baseline.frameTime.hitchRate - candidate.frameTime.hitchRate) /
                max(baseline.frameTime.hitchRate, MIN_HITCH_RATE_SCALE)).coerceIn(-1.0, 1.0)
        } else null
        val memoryGain = if (baseline.ramMeanPct != null && candidate.ramMeanPct != null) {
            ((baseline.ramMeanPct - candidate.ramMeanPct) / 100.0).coerceIn(-1.0, 1.0)
        } else null
        val energyGain = if (baseline.energyMeanMah != null && candidate.energyMeanMah != null) {
            normalizedGain(baseline.energyMeanMah - candidate.energyMeanMah, baseline.energyMeanMah)
        } else null

        val performanceGains = buildList {
            add(fpsGain)
            add(lowFpsGain)
            stabilityGain?.let(::add)
            hitchGain?.let(::add)
            memoryGain?.let(::add)
            energyGain?.let(::add)
        }
        val performanceGain = performanceGains.average().coerceIn(-1.0, 1.0)

        val tempCost = if (baseline.tempMeanC != null && candidate.tempMeanC != null) {
            val baselinePeak = baseline.tempMaxC ?: baseline.tempMeanC
            val candidatePeak = candidate.tempMaxC ?: candidate.tempMeanC
            val riseCost = ((candidatePeak - baselinePeak).coerceAtLeast(0.0) / TEMP_EFFECT_SCALE_C)
                .coerceIn(0.0, 1.0)
            // Absolute headroom matters too: equal FPS at 43C is not as safe as equal FPS at 37C.
            val nearFloorCost = ((candidatePeak - (ThermalGuard.RAW_MODERATE_C - 2.0)) / 2.0)
                .coerceIn(0.0, 1.0)
            max(riseCost, nearFloorCost)
        } else null

        val slopeCost = if (baseline.thermalSlopeCPerMin != null && candidate.thermalSlopeCPerMin != null) {
            val absoluteRise = candidate.thermalSlopeCPerMin.coerceAtLeast(0.0) /
                (SLOPE_EFFECT_SCALE_C_PER_MIN * 2.0)
            val regression = (candidate.thermalSlopeCPerMin - baseline.thermalSlopeCPerMin)
                .coerceAtLeast(0.0) / SLOPE_EFFECT_SCALE_C_PER_MIN
            max(absoluteRise, regression).coerceIn(0.0, 1.0)
        } else null
        val tierCost = (candidate.thermalTier.coerceAtLeast(0).toDouble() /
            ThermalGuard.STATUS_CRITICAL).coerceIn(0.0, 1.0)

        val candidateTemp = candidate.tempMaxC ?: candidate.tempMeanC
        val headroom = if (candidateTemp != null) {
            ((ThermalGuard.RAW_MODERATE_C - candidateTemp) / SOFT_THERMAL_HEADROOM_C)
                .coerceIn(0.0, 1.0)
        } else {
            (1.0 - candidate.thermalTier.toDouble() / ThermalGuard.STATUS_CRITICAL)
                .coerceIn(0.0, 1.0)
        }

        var denominator = Weights.PERFORMANCE + Weights.THERMAL_TIER
        var numerator = Weights.PERFORMANCE * performanceGain * headroom -
            Weights.THERMAL_TIER * tierCost
        if (tempCost != null) {
            denominator += Weights.THERMAL_TEMPERATURE
            numerator -= Weights.THERMAL_TEMPERATURE * tempCost
        }
        if (slopeCost != null) {
            denominator += Weights.THERMAL_SLOPE
            numerator -= Weights.THERMAL_SLOPE * slopeCost
        }
        val score = (numerator / denominator).coerceIn(-1.0, 1.0)
        return ScoreComponents(
            averageFpsGain = fpsGain,
            lowFpsGain = lowFpsGain,
            frameStabilityGain = stabilityGain,
            hitchGain = hitchGain,
            memoryGain = memoryGain,
            energyGain = energyGain,
            thermalTemperatureCost = tempCost,
            thermalSlopeCost = slopeCost,
            thermalTierCost = tierCost,
            thermalHeadroomFactor = headroom,
            score = score
        )
    }

    /** Family-wise-corrected assessment across independently stored variants. */
    fun assessScores(
        scores: List<Double>,
        cfg: TrialConfig,
        comparisons: Int = 1
    ): TrialOutcome {
        val values = scores.filter { it.isFinite() }
        val n = values.size
        if (n < 2) {
            return TrialOutcome(
                Decision.MORE_DATA, null, null, null, n,
                "need at least two valid paired score blocks",
                confidenceLevel = FAMILY_WISE_CONFIDENCE,
                comparisonCount = comparisons.coerceAtLeast(1)
            )
        }
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val sd = sqrt(variance)
        val critical = tCritical(n - 1, comparisons)
        val half = critical * sd / sqrt(n.toDouble())
        val lo = mean - half
        val hi = mean + half
        val decision = when {
            n < cfg.minPairs -> Decision.MORE_DATA
            sd > cfg.maxScoreStdDev && n < cfg.maxPairs -> Decision.MORE_DATA
            lo > cfg.minEffectScore -> Decision.KEEP
            hi < -cfg.minEffectScore -> Decision.DROP
            n >= cfg.maxPairs -> Decision.NEUTRAL
            else -> Decision.MORE_DATA
        }
        val reason = when {
            n < cfg.minPairs -> "collecting paired blocks ($n/${cfg.minPairs})"
            sd > cfg.maxScoreStdDev && n < cfg.maxPairs -> "score variance is high; collect more data"
            decision == Decision.KEEP -> "family-wise CI exceeds the useful-effect threshold"
            decision == Decision.DROP -> "family-wise CI shows a harmful net score"
            decision == Decision.NEUTRAL -> "maximum sample count reached without a useful net effect"
            else -> "family-wise CI is too wide or crosses the useful-effect threshold"
        }
        return TrialOutcome(
            decision = decision,
            meanDelta = mean,
            ciLow = lo,
            ciHigh = hi,
            pairs = n,
            reason = reason,
            meanScore = mean,
            confidenceLevel = FAMILY_WISE_CONFIDENCE,
            comparisonCount = comparisons.coerceAtLeast(1)
        )
    }

    /** Legacy paired FPS assessment, retained for stored v1.x entries/tests. */
    fun assess(rawDeltas: List<Double>, cfg: TrialConfig): TrialOutcome {
        val deltas = trimmedDeltas(rawDeltas)
        val n = deltas.size
        val rawN = rawDeltas.size
        if (n < 2) {
            return TrialOutcome(Decision.MORE_DATA, null, null, null, rawN,
                "need >= 2 paired samples")
        }
        val mean = deltas.sum() / n
        val variance = deltas.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val sd = sqrt(variance)
        val half = if (sd > 0.0) tQuantile(n - 1) * sd / sqrt(n.toDouble()) else 0.0
        val lo = mean - half
        val hi = mean + half
        val e = cfg.minEffectFps
        return when {
            rawN < cfg.minPairs ->
                TrialOutcome(Decision.MORE_DATA, mean, lo, hi, rawN,
                    "collecting pairs ($rawN/${cfg.minPairs})")
            rawN >= cfg.maxPairs && lo <= e && hi >= -e ->
                TrialOutcome(Decision.NEUTRAL, mean, lo, hi, rawN,
                    "no measurable effect after $rawN pairs")
            lo > e ->
                TrialOutcome(Decision.KEEP, mean, lo, hi, rawN,
                    "benefit outside confidence interval")
            hi < -e ->
                TrialOutcome(Decision.DROP, mean, lo, hi, rawN,
                    "measured harmful")
            rawN >= cfg.maxPairs ->
                TrialOutcome(Decision.NEUTRAL, mean, lo, hi, rawN,
                    "no measurable effect after $rawN pairs")
            else ->
                TrialOutcome(Decision.MORE_DATA, mean, lo, hi, rawN,
                    "confidence interval too wide or effect is inconclusive")
        }
    }

    private fun normalizedGain(delta: Double, baseline: Double): Double =
        (delta / max(abs(baseline), 1.0)).coerceIn(-1.0, 1.0)

    private fun twoSidedStudentTail(t: Double, df: Int): Double {
        if (t <= 0.0) return 1.0
        val d = df.toDouble()
        val x = d / (d + t * t)
        return regularizedBeta(x, d / 2.0, 0.5).coerceIn(0.0, 1.0)
    }

    /** Regularized incomplete beta; Numerical Recipes continued fraction. */
    private fun regularizedBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val bt = exp(logGamma(a + b) - logGamma(a) - logGamma(b) +
            a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            bt * betaContinuedFraction(a, b, x) / a
        } else {
            1.0 - bt * betaContinuedFraction(b, a, 1.0 - x) / b
        }
    }

    private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < FPMIN) d = FPMIN
        d = 1.0 / d
        var h = d
        for (m in 1..MAX_BETA_ITERATIONS) {
            val mD = m.toDouble()
            val m2 = 2.0 * mD
            var aa = mD * (b - mD) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < FPMIN) d = FPMIN
            c = 1.0 + aa / c
            if (abs(c) < FPMIN) c = FPMIN
            d = 1.0 / d
            h *= d * c
            aa = -(a + mD) * (qab + mD) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < FPMIN) d = FPMIN
            c = 1.0 + aa / c
            if (abs(c) < FPMIN) c = FPMIN
            d = 1.0 / d
            val delta = d * c
            h *= delta
            if (abs(delta - 1.0) < BETA_EPSILON) break
        }
        return h
    }

    private fun logGamma(z: Double): Double {
        if (z < 0.5) return ln(Math.PI) - ln(kotlin.math.sin(Math.PI * z)) - logGamma(1.0 - z)
        val shifted = z - 1.0
        var x = LANCZOS[0]
        for (i in 1 until LANCZOS.size) x += LANCZOS[i] / (shifted + i)
        val t = shifted + LANCZOS_G + 0.5
        return 0.5 * ln(2.0 * Math.PI) + (shifted + 0.5) * ln(t) - t + ln(x)
    }

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    )
    private const val LANCZOS_G = 7.0
    private const val FPMIN = 1e-300
    private const val BETA_EPSILON = 3e-14
    private const val MAX_BETA_ITERATIONS = 200
}
