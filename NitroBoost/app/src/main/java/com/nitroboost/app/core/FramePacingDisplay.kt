package com.nitroboost.app.core

import com.nitroboost.app.core.telemetry.FramePacingMetrics

/** UI-safe projection of measured frame pacing; null means the source is unavailable. */
data class FramePacingDisplay(
    val onePercentLowFps: Double?,
    val p95FrameTimeMs: Double?,
    val p99FrameTimeMs: Double?,
    val sampleCount: Int
) {
    val hasMeasuredData: Boolean
        get() = onePercentLowFps != null || p95FrameTimeMs != null || p99FrameTimeMs != null

    companion object {
        fun from(metrics: FramePacingMetrics?): FramePacingDisplay = FramePacingDisplay(
            onePercentLowFps = metrics?.onePercentLowFps,
            p95FrameTimeMs = metrics?.p95FrameTimeMs,
            p99FrameTimeMs = metrics?.p99FrameTimeMs,
            sampleCount = metrics?.fpsSampleCount ?: 0
        )
    }
}
