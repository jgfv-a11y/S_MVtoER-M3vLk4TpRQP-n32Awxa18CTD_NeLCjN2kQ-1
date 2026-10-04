package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.telemetry.FramePacingMetrics

/** Where the frames are being lost — the first honest question of any boost. */
enum class Bottleneck {
    /** No FPS source (no Shizuku / gfxinfo) — decisions are blind, loop stays off. */
    UNKNOWN,
    /** Everything within target — nothing to fix. */
    NONE,
    CPU,
    GPU,
    MEMORY,
    NETWORK,
    THERMAL
}

/** Statistical verdict for one tweak after a real A/B trial. */
enum class Decision {
    KEEP,
    DROP,
    /** Evidence is absent, stale, noisy, unsafe or insufficient; retry later. */
    MORE_DATA,
    /** Enough evidence, but no useful net effect. */
    NEUTRAL,
    /** Legacy spelling kept so ledgers created by earlier releases still load. */
    @Deprecated("Use MORE_DATA")
    NEEDS_MORE;

    val resolved: Boolean
        get() = this == KEEP || this == DROP || this == NEUTRAL
}

/** One real frame-time distribution; null means the platform exposed no frame-time source. */
data class FrameTimeDistribution(
    val frameCount: Int,
    val meanMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val varianceMs2: Double
)

data class FrameTimeMetrics(
    val frameCount: Int,
    val medianMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val varianceMs2: Double,
    val hitchCount: Int,
    val hitchRate: Double,
    val hitchThresholdMs: Double
)

/** One session's input metrics for bottleneck classification. */
data class FrameMetrics(
    val fps: Int?,
    val targetFps: Int,
    val cpuPct: Int,
    val ramPct: Int,
    val pingMs: Int?,
    val retransPerSec: Int?,
    val thermalStatus: Int,
    val tempC: Double?,
    /** Only filled from actual gfxinfo framestats output. */
    val frameTime: FrameTimeMetrics? = null,
    val monitorTimestampMs: Long = 0L,
    val gamePackage: String? = null
)

/**
 * Tunables for the adaptive experiment.
 *
 * A measurement window is split into non-overlapping blocks of fresh monitor
 * observations before paired scores are accumulated. minPairs is deliberately
 * evidence across paired blocks/sessions, not a claim that every FPS tick is an
 * independent experiment.
 */
data class TrialConfig(
    val minPairs: Int = 8,
    val maxPairs: Int = 40,
    /** Retained for the legacy FPS-only assessor. */
    val minEffectFps: Double = 0.5,
    /** 1.5% of the normalized multi-objective score is the minimum useful effect. */
    val minEffectScore: Double = 0.015,
    val maxScoreStdDev: Double = 0.30,
    val windowMs: Long = 12_000L,
    val settleMs: Long = 2_000L,
    val sampleIntervalMs: Long = 1_000L,
    val minWindowFpsSamples: Int = 5,
    val minWindowThermalSamples: Int = 6,
    val maxMonitorAgeMs: Long = 2_500L,
    val maxSamplingGapMs: Long = 3_500L,
    /** Reject clearly unstable per-window FPS workload variation; measured from actual samples. */
    val maxFpsCoefficientOfVariation: Double = 0.75,
    val maximumThermalTierDrift: Int = 0,
    val decisionTtlMs: Long = 30L * 24L * 60L * 60L * 1_000L
)

/** Result of an assessment. The legacy delta/CI fields remain source-compatible. */
data class TrialOutcome(
    val decision: Decision,
    val meanDelta: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val pairs: Int,
    val reason: String,
    /** Set by the multi-objective scorer; null for legacy FPS-only assessments. */
    val meanScore: Double? = null,
    /** Family-wise confidence requested before the Bonferroni adjustment. */
    val confidenceLevel: Double? = null,
    val comparisonCount: Int = 1
)

/**
 * Persistent per-task decision record. Legacy `deltas` are retained for
 * compatibility; v2 experiments keep full paired-window records per variant.
 */
data class LedgerEntry(
    val taskId: String,
    val taskTitle: String,
    val decision: Decision,
    val deltas: List<Double>,
    val meanDelta: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val pairs: Int,
    val sessions: Int,
    val evaluatedAt: Long,
    /** e.g. "level=0.8" for a downscale sweep winner. */
    val detail: String? = null,
    val context: TrialContext? = null,
    val score: Double? = null,
    val confidenceLevel: Double? = null,
    val comparisonCount: Int = 1,
    val reason: String? = null,
    val variants: Map<String, VariantTrialRecord> = emptyMap()
) {
    val resolved: Boolean
        get() = decision.resolved
}
