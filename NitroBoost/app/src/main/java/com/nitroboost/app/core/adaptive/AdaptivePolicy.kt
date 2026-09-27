package com.nitroboost.app.core.adaptive

import kotlin.math.sqrt

/**
 * The statistical heart of the adaptive engine.
 *
 * Every candidate tweak is A/B tested on the REAL device:
 *   baseline window (tweak reverted)  ->  arm window (tweak applied)
 * and the per-sample FPS differences become "delta pairs". The accumulated
 * pairs (across sessions, see DecisionLedger) are assessed with a paired
 * 95% confidence interval on the mean delta:
 *
 *   KEEP     : CI lower bound > +minEffectFps  (benefit, outside noise)
 *   DROP     : CI upper bound < -minEffectFps  (measured harmful)
 *   NEUTRAL  : maxPairs reached, CI straddles the effect threshold
 *   NEEDS_MORE: fewer than minPairs — keep measuring next session
 *
 * The t-quantile table (df 1..30, then z=2.0) is exact for the paired
 * two-sided 95% interval — no statistical library, fully unit-tested.
 */
object AdaptivePolicy {

    /** two-sided 95% t-quantiles, index = degrees of freedom. */
    private val T_TABLE = doubleArrayOf(
        0.0,
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, // df 1..10
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, // df 11..20
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042  // df 21..30
    )

    fun tQuantile(df: Int): Double = when {
        df < 1 -> Double.POSITIVE_INFINITY
        df <= 30 -> T_TABLE[df]
        else -> 2.0
    }

    /** Paired differences (arm - baseline), truncated to the shorter list. */
    fun deltasOf(baseline: List<Int>, arm: List<Int>): List<Double> {
        val n = minOf(baseline.size, arm.size)
        return (0 until n).map { arm[it].toDouble() - baseline[it].toDouble() }
    }

    /**
     * Pure outlier trim: drop the extreme top and bottom 10% of the deltas
     * (spikes from hitches, GC pauses, network blips) before the statistics.
     * Deterministic, order-independent, and never removes more than 2
     * values per side — small samples stay intact.
     */
    fun trimmedDeltas(deltas: List<Double>): List<Double> {
        if (deltas.size < 6) return deltas
        val sorted = deltas.sorted()
        val k = deltas.size / 10
        return sorted.subList(k, sorted.size - k)
    }

    /**
     * Assess accumulated delta pairs with the paired t interval.
     * Statistics run on the outlier-trimmed data; the evidence counters
     * (min/max pairs) count the RAW pairs, so trimming can never stall the
     * decision process.
     */
    fun assess(rawDeltas: List<Double>, cfg: TrialConfig): TrialOutcome {
        val deltas = trimmedDeltas(rawDeltas)
        val n = deltas.size
        val rawN = rawDeltas.size
        if (n < 2) {
            return TrialOutcome(Decision.NEEDS_MORE, null, null, null, rawN,
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
                TrialOutcome(Decision.NEEDS_MORE, mean, lo, hi, rawN,
                    "collecting pairs (${rawN}/${cfg.minPairs})")

            rawN >= cfg.maxPairs && lo <= e && hi >= -e ->
                TrialOutcome(Decision.NEUTRAL, mean, lo, hi, rawN,
                    "no measurable effect after ${rawN} pairs")

            lo > e ->
                TrialOutcome(Decision.KEEP, mean, lo, hi, rawN,
                    "benefit outside confidence interval")

            hi < -e ->
                TrialOutcome(Decision.DROP, mean, lo, hi, rawN,
                    "measured harmful")

            rawN >= cfg.maxPairs ->
                TrialOutcome(Decision.NEUTRAL, mean, lo, hi, rawN,
                    "no measurable effect after ${rawN} pairs")

            else ->
                TrialOutcome(Decision.NEEDS_MORE, mean, lo, hi, rawN,
                    "collecting pairs (${rawN}/${cfg.minPairs})")
        }
    }
}
