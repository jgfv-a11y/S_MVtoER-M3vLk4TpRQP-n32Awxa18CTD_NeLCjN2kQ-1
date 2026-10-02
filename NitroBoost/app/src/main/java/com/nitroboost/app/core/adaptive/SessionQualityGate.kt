package com.nitroboost.app.core.adaptive

import kotlin.math.abs

/** Rejects bad/unaligned windows before they are allowed to update the ledger. */
object SessionQualityGate {
    fun validateWindow(
        window: WindowMetrics,
        expectedGamePackage: String,
        cfg: TrialConfig
    ): QualityResult {
        if (!window.windowComplete) {
            return QualityResult.reject("session window stopped early")
        }
        if (window.sampleCount < cfg.minWindowThermalSamples) {
            return QualityResult.reject("too few fresh monitor samples")
        }
        if (window.fpsSampleCount < cfg.minWindowFpsSamples) {
            return QualityResult.reject("too few FPS samples")
        }
        if (window.thermalSampleCount < cfg.minWindowThermalSamples) {
            return QualityResult.reject("thermal samples incomplete")
        }
        if (window.maxMonitorAgeMs > cfg.maxMonitorAgeMs) {
            return QualityResult.reject("monitor stale")
        }
        if (window.maxSampleGapMs > cfg.maxSamplingGapMs) {
            return QualityResult.reject("sampling gap too large")
        }
        if (window.requestedWindowMs > 0L &&
            window.lastTimestampMs - window.firstTimestampMs < window.requestedWindowMs / 2L
        ) {
            return QualityResult.reject("monitor coverage is too short")
        }
        if (window.gamePackage.isNullOrBlank() || window.gamePackage != expectedGamePackage) {
            return QualityResult.reject("game process/package changed")
        }
        if (window.processEpoch < 0L) {
            return QualityResult.reject("game process counter reset")
        }
        if (window.maxThermalTier - window.minThermalTier > cfg.maximumThermalTierDrift) {
            return QualityResult.reject("thermal state changed during window")
        }
        return QualityResult.accept()
    }

    fun compare(
        baseline: WindowMetrics,
        candidate: WindowMetrics,
        expectedGamePackage: String,
        cfg: TrialConfig
    ): QualityResult {
        val baseQuality = validateWindow(baseline, expectedGamePackage, cfg)
        if (!baseQuality.accepted) return baseQuality
        val candidateQuality = validateWindow(candidate, expectedGamePackage, cfg)
        if (!candidateQuality.accepted) return candidateQuality
        if (baseline.gamePackage != candidate.gamePackage ||
            baseline.processEpoch != candidate.processEpoch
        ) {
            return QualityResult.reject("game process changed between windows")
        }
        if (abs(candidate.thermalTier - baseline.thermalTier) > cfg.maximumThermalTierDrift) {
            return QualityResult.reject("thermal state changed between baseline and candidate")
        }
        val baseTemp = baseline.tempMeanC
        val candidateTemp = candidate.tempMeanC
        if (baseTemp != null && candidateTemp != null &&
            abs(candidateTemp - baseTemp) >= MAX_COMPARISON_TEMP_SHIFT_C
        ) {
            return QualityResult.reject("thermal context shifted materially")
        }
        return QualityResult.accept()
    }

    const val MAX_COMPARISON_TEMP_SHIFT_C = 4.0
}
