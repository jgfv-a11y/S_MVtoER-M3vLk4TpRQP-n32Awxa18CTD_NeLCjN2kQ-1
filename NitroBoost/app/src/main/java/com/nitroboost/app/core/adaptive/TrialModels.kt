package com.nitroboost.app.core.adaptive

/** Local-only cache context. It intentionally contains no user/account identifiers. */
data class TrialContext(
    val deviceKey: String,
    val androidVersion: String,
    val gamePackage: String,
    val boostLevel: Int,
    val thermalTier: Int,
    val capabilityKey: String,
    val profileKey: String,
    /** Coarse local temperature/slope bands; small sensor noise does not churn the cache. */
    val thermalSignature: String = "",
    val taskRevision: Int = TASK_REVISION
) {
    companion object {
        /** Bump when task semantics change without changing the app package/version. */
        const val TASK_REVISION = 2
    }
}

/** One completed pair of baseline/candidate blocks. */
data class PairObservation(
    val sampleId: String,
    val sessionId: String,
    val observedAtMs: Long,
    val baseline: WindowMetrics,
    val candidate: WindowMetrics,
    val score: ScoreComponents
)

/** Per-variant experiment retained independently from every other variant. */
data class VariantTrialRecord(
    val variantId: String,
    val detail: String,
    val observations: List<PairObservation>,
    val decision: Decision,
    val meanScore: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val confidenceLevel: Double,
    val comparisonCount: Int,
    val sessions: Int,
    val evaluatedAt: Long,
    val reason: String
)

/** A quality-checked block summary produced from actual monitor observations. */
data class WindowMetrics(
    val fpsMean: Double,
    val lowFps: Double,
    val fpsSampleCount: Int,
    val sampleCount: Int,
    val thermalSampleCount: Int,
    val thermalTier: Int,
    val minThermalTier: Int,
    val maxThermalTier: Int,
    val tempMeanC: Double?,
    val tempMaxC: Double?,
    val thermalSlopeCPerMin: Double?,
    val ramMeanPct: Double?,
    val energyMeanMah: Double?,
    val frameTime: FrameTimeMetrics?,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    val windowComplete: Boolean,
    val requestedWindowMs: Long,
    val maxSampleGapMs: Long,
    val maxMonitorAgeMs: Long,
    val gamePackage: String?,
    val processEpoch: Long,
    val targetFps: Int
)

/** Normalized, bounded effects and risk terms for one paired block. */
data class ScoreComponents(
    val averageFpsGain: Double,
    val lowFpsGain: Double,
    val frameStabilityGain: Double?,
    val hitchGain: Double?,
    val memoryGain: Double?,
    val energyGain: Double?,
    val thermalTemperatureCost: Double?,
    val thermalSlopeCost: Double?,
    val thermalTierCost: Double,
    val thermalHeadroomFactor: Double,
    val score: Double
)

/** Output of the pre-commit quality gate; rejected windows never add evidence. */
data class QualityResult(val accepted: Boolean, val reason: String) {
    companion object {
        fun accept() = QualityResult(true, "quality gate passed")
        fun reject(reason: String) = QualityResult(false, reason)
    }
}
