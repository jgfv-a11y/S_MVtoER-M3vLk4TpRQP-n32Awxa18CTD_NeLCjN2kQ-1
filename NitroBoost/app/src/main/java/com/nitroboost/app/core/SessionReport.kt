package com.nitroboost.app.core

/**
 * Aggregated performance of one boost session.
 * Pure data + pure builder so the math is unit-testable on the JVM.
 *
 * [previousAvgFps] is the average FPS of the previous session (persisted by
 * the caller); [deltaFps] is this session minus that — the first honest
 * "did the boost help?" number, measured on the real device.
 */
data class SessionReport(
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Int,
    val avgFps: Int?,
    val minFps: Int?,
    val peakTempC: Int?,
    val minPingMs: Int?,
    val peakRamMb: Int,
    val applied: Int,
    val failed: Int,
    val previousAvgFps: Int?,
    val deltaFps: Int?
)

object SessionReportBuilder {

    fun summarize(
        startedAt: Long,
        endedAt: Long,
        fpsSamples: List<Int>,
        tempSamples: List<Int>,
        pingSamples: List<Int>,
        ramMbSamples: List<Int>,
        applied: Int,
        failed: Int,
        previousAvgFps: Int?
    ): SessionReport {
        val avgFps = if (fpsSamples.isEmpty()) null else fpsSamples.average().toInt()
        val delta =
            if (avgFps != null && previousAvgFps != null) avgFps - previousAvgFps else null
        return SessionReport(
            startedAt = startedAt,
            endedAt = endedAt,
            durationSec = ((endedAt - startedAt) / 1000L).toInt().coerceAtLeast(0),
            avgFps = avgFps,
            minFps = fpsSamples.minOrNull(),
            peakTempC = tempSamples.maxOrNull(),
            minPingMs = pingSamples.minOrNull(),
            peakRamMb = ramMbSamples.maxOrNull() ?: 0,
            applied = applied,
            failed = failed,
            previousAvgFps = previousAvgFps,
            deltaFps = delta
        )
    }
}
