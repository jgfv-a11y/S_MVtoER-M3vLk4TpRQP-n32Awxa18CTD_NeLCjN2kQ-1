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
        if (window.firstTimestampMs <= 0L || window.lastTimestampMs < window.firstTimestampMs) {
            return QualityResult.reject("invalid window timestamps")
        }
        if (window.sampleCount < cfg.minWindowThermalSamples) {
            return QualityResult.reject("too few fresh monitor samples")
        }
        if (window.fpsSampleCount < cfg.minWindowFpsSamples) {
            return QualityResult.reject("too few FPS samples")
        }
        if (!window.fpsMean.isFinite() || window.fpsMean <= 0.0 ||
            !window.lowFps.isFinite() || window.lowFps <= 0.0
        ) {
            return QualityResult.reject("invalid FPS measurements")
        }
        val fpsVariation = window.fpsCoefficientOfVariation
            ?: return QualityResult.reject("FPS workload stability unavailable")
        if (!cfg.maxFpsCoefficientOfVariation.isFinite() || cfg.maxFpsCoefficientOfVariation < 0.0 ||
            !fpsVariation.isFinite() || fpsVariation < 0.0
        ) {
            return QualityResult.reject("invalid FPS variability")
        }
        if (fpsVariation > cfg.maxFpsCoefficientOfVariation) {
            return QualityResult.reject("FPS workload instability")
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
        val baseMidpoint = baseline.firstTimestampMs +
            (baseline.lastTimestampMs - baseline.firstTimestampMs) / 2L
        val candidateMidpoint = candidate.firstTimestampMs +
            (candidate.lastTimestampMs - candidate.firstTimestampMs) / 2L
        val temporalDistance = abs(candidateMidpoint - baseMidpoint)
        val allowedDistance = maxOf(baseline.requestedWindowMs, candidate.requestedWindowMs)
            .coerceAtLeast(0L) + cfg.settleMs.coerceAtLeast(0L) +
            2L * cfg.sampleIntervalMs.coerceAtLeast(1L)
        if (temporalDistance > allowedDistance) {
            return QualityResult.reject("paired windows are too far apart")
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
