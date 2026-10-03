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
    val taskRevision: Int = TASK_REVISION,
    /** Changes to objective semantics invalidate decisions from older policies. */
    val objectivePolicyRevision: Int = OBJECTIVE_POLICY_REVISION,
    /** Stable identity of the explicit normalized weight profile used. */
    val objectivePolicyKey: String = DEFAULT_OBJECTIVE_POLICY_KEY
) {
    companion object {
        /** Bump when task semantics change without changing the app package/version. */
        const val TASK_REVISION = 2
        /** New objective semantics; v1.10 contexts without this field parse as legacy revision 0. */
        const val OBJECTIVE_POLICY_REVISION = 1
        const val DEFAULT_OBJECTIVE_POLICY_KEY = "balanced-v1"
        const val LEGACY_OBJECTIVE_POLICY_KEY = "legacy-v1.10"
    }
}

/** Measurement order within one nearby, quality-gated pair. */
enum class PairOrder {
    BASELINE_THEN_CANDIDATE,
    CANDIDATE_THEN_BASELINE,
    /** v1.10 stored windows sequentially and did not retain their order. */
    LEGACY_SEQUENTIAL
}

/** Persisted before a paired block starts so retries alternate even after invalid data/cancellation. */
data class PairAttempt(
    val blockIndex: Int,
    val order: PairOrder,
    val startedAtMs: Long
)

/** One completed pair of baseline/candidate windows; invalid pairs never enter statistics. */
data class PairObservation(
    val sampleId: String,
    val sessionId: String,
    val observedAtMs: Long,
    val baseline: WindowMetrics,
    val candidate: WindowMetrics,
    val score: ScoreComponents,
    val blockIndex: Int = 0,
    val order: PairOrder = PairOrder.LEGACY_SEQUENTIAL,
    val temporalDistanceMs: Long = 0L,
    val blockDurationMs: Long = 0L,
    val qualityValid: Boolean = true,
    val invalidReason: String? = null
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
    val reason: String,
    /** Includes invalid/incomplete attempts, not just scored observations. */
    val attemptCount: Int = observations.size,
    val lastAttemptIndex: Int = -1,
    val lastAttemptOrder: PairOrder? = null,
    val lastAttemptAtMs: Long = 0L,
    val lastInvalidReason: String? = null
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
    val targetFps: Int,
    /** Coefficient of variation from sampled FPS readings; null when it cannot be measured. */
    val fpsCoefficientOfVariation: Double? = null
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
    val score: Double,
    /** Exact normalized policy inputs make each persisted pair auditable. */
    val objectiveWeights: ObjectiveWeights = ObjectiveWeightResolver.DEFAULT_WEIGHTS
)

/** Output of the pre-commit quality gate; rejected windows never add evidence. */
data class QualityResult(val accepted: Boolean, val reason: String) {
    companion object {
        fun accept() = QualityResult(true, "quality gate passed")
        fun reject(reason: String) = QualityResult(false, reason)
    }
}
