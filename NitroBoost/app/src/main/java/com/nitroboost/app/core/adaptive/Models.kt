package com.nitroboost.app.core.adaptive

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
    /** Mean delta above the confidence interval — keep the tweak. */
    KEEP,
    /** Measured harmful — revert and stop retrying. */
    DROP,
    /** Not enough paired samples yet — accumulate in the next session. */
    NEEDS_MORE,
    /** Enough pairs, no measurable effect — reverted to keep the system clean. */
    NEUTRAL
}

/** One session's input metrics for bottleneck classification. */
data class FrameMetrics(
    val fps: Int?,
    val targetFps: Int,
    val cpuPct: Int,
    val ramPct: Int,
    val pingMs: Int?,
    val retransPerSec: Int?,
    val thermalStatus: Int,
    val tempC: Double?
)

/**
 * Tunables for the trial statistics.
 * v2 speed: 12s windows + 2s settles + session-wide baseline reuse make a
 * full 9-candidate sweep ~5 minutes instead of ~10.
 */
data class TrialConfig(
    val minPairs: Int = 8,
    val maxPairs: Int = 40,
    val minEffectFps: Double = 0.5,
    val windowMs: Long = 12_000L,
    val settleMs: Long = 2_000L
)

/** Result of assessing the accumulated delta pairs of one task. */
data class TrialOutcome(
    val decision: Decision,
    val meanDelta: Double?,
    val ciLow: Double?,
    val ciHigh: Double?,
    val pairs: Int,
    val reason: String
)

/**
 * Persistent per-task decision record. `deltas` are the accumulated paired
 * FPS differences (arm - baseline) across ALL sessions, bounded by
 * [TrialConfig.maxPairs] — the engine converges over sessions, exactly like
 * a real experiment ledger.
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
    /** e.g. "level=0.8" for the downscale sweep winner — engine metadata. */
    val detail: String? = null
) {
    val resolved: Boolean
        get() = decision == Decision.KEEP || decision == Decision.DROP || decision == Decision.NEUTRAL
}
