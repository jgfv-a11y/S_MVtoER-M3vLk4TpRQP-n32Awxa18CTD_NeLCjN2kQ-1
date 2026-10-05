package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.ShellInput
import kotlin.math.abs

/** Rejects bad/unaligned windows before they are allowed to update the ledger. */
object SessionQualityGate {
    private const val MAX_FPS = 1_000.0
    private const val MIN_VALID_TEMP_C = -40.0
    private const val MAX_VALID_TEMP_C = 200.0
    private const val MAX_VALID_SLOPE_C_PER_MIN = 100.0
    private const val MAX_VALID_ENERGY_MAH = 1_000_000_000.0

    fun validateWindow(
        window: WindowMetrics,
        expectedGamePackage: String,
        cfg: TrialConfig
    ): QualityResult {
        if (!validConfig(cfg)) {
            return QualityResult.reject("invalid quality-gate configuration")
        }
        if (!ShellInput.isPackageName(expectedGamePackage)) {
            return QualityResult.reject("invalid expected game package")
        }
        if (!window.windowComplete) {
            return QualityResult.reject("session window stopped early")
        }
        if (window.firstTimestampMs <= 0L || window.lastTimestampMs < window.firstTimestampMs) {
            return QualityResult.reject("invalid window timestamps")
        }
        if (window.requestedWindowMs !in 0L..300_000L || window.maxSampleGapMs < 0L ||
            window.maxMonitorAgeMs < 0L
        ) {
            return QualityResult.reject("negative timing or future monitor timestamp")
        }
        if (window.sampleCount < 0 || window.fpsSampleCount < 0 ||
            window.thermalSampleCount < 0 || window.fpsSampleCount > window.sampleCount ||
            window.thermalSampleCount > window.sampleCount
        ) {
            return QualityResult.reject("inconsistent sample counts")
        }
        if (window.sampleCount < cfg.minWindowThermalSamples) {
            return QualityResult.reject("too few fresh monitor samples")
        }
        if (window.fpsSampleCount < cfg.minWindowFpsSamples) {
            return QualityResult.reject("too few FPS samples")
        }
        if (!window.fpsMean.isFinite() || window.fpsMean !in 1.0..MAX_FPS ||
            !window.lowFps.isFinite() || window.lowFps !in 1.0..MAX_FPS ||
            window.lowFps > window.fpsMean || window.targetFps !in 1..MAX_FPS.toInt()
        ) {
            return QualityResult.reject("invalid FPS measurements")
        }
        if (window.thermalTier !in 0..6 || window.minThermalTier !in 0..6 ||
            window.maxThermalTier !in 0..6 || window.minThermalTier > window.maxThermalTier ||
            window.thermalTier != window.maxThermalTier
        ) {
            return QualityResult.reject("invalid thermal tier summary")
        }
        val temps = listOfNotNull(window.tempMeanC, window.tempMaxC)
        if (temps.any { !it.isFinite() || it !in MIN_VALID_TEMP_C..MAX_VALID_TEMP_C } ||
            (window.tempMeanC == null) != (window.tempMaxC == null) ||
            (window.tempMeanC != null && window.tempMaxC!! < window.tempMeanC)
        ) {
            return QualityResult.reject("invalid temperature measurements")
        }
        if (window.thermalSlopeCPerMin?.let {
                !it.isFinite() || it !in -MAX_VALID_SLOPE_C_PER_MIN..MAX_VALID_SLOPE_C_PER_MIN
            } == true
        ) {
            return QualityResult.reject("invalid thermal slope")
        }
        if (window.ramMeanPct?.let { !it.isFinite() || it !in 0.0..100.0 } == true) {
            return QualityResult.reject("invalid memory measurement")
        }
        if (window.energyMeanMah?.let {
                !it.isFinite() || it !in 0.0..MAX_VALID_ENERGY_MAH
            } == true
        ) {
            return QualityResult.reject("invalid energy measurement")
        }
        if (window.frameTime?.let { !validFrameTimeMetrics(it) } == true) {
            return QualityResult.reject("invalid frame-time measurements")
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

    private fun validFrameTimeMetrics(metrics: FrameTimeMetrics): Boolean {
        if (metrics.frameCount !in 1..1_000_000) return false
        if (listOf(metrics.medianMs, metrics.p95Ms, metrics.p99Ms).any {
                !it.isFinite() || it !in 0.1..1_000.0
            }
        ) return false
        if (metrics.medianMs > metrics.p95Ms || metrics.p95Ms > metrics.p99Ms) return false
        if (!metrics.varianceMs2.isFinite() || metrics.varianceMs2 < 0.0) return false
        if (metrics.hitchCount !in 0..metrics.frameCount ||
            !metrics.hitchRate.isFinite() || metrics.hitchRate !in 0.0..1.0 ||
            abs(metrics.hitchRate - metrics.hitchCount.toDouble() / metrics.frameCount) > 1e-9
        ) return false
        return metrics.hitchThresholdMs.isFinite() && metrics.hitchThresholdMs in 0.1..2_000.0
    }

    private fun validConfig(cfg: TrialConfig): Boolean =
        cfg.minPairs in 2..1_000 && cfg.maxPairs in cfg.minPairs..1_000 &&
            cfg.minEffectFps.isFinite() && cfg.minEffectFps in 0.0..MAX_FPS &&
            cfg.minEffectScore.isFinite() && cfg.minEffectScore in 0.0..1.0 &&
            cfg.maxScoreStdDev.isFinite() && cfg.maxScoreStdDev in 0.0..2.0 &&
            cfg.windowMs in 0L..300_000L && cfg.settleMs in 0L..300_000L &&
            cfg.sampleIntervalMs in 100L..60_000L &&
            cfg.minWindowFpsSamples in 1..1_000 && cfg.minWindowThermalSamples in 1..1_000 &&
            cfg.maxMonitorAgeMs in 0L..60_000L && cfg.maxSamplingGapMs in 1L..60_000L &&
            cfg.maxFpsCoefficientOfVariation.isFinite() &&
            cfg.maxFpsCoefficientOfVariation in 0.0..10.0 &&
            cfg.maximumThermalTierDrift in 0..6 && cfg.decisionTtlMs in 0L..(365L * 24 * 60 * 60 * 1_000)

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
