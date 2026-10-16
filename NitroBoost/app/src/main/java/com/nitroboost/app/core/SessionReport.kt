package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.Bottleneck
import com.nitroboost.app.core.telemetry.FramePacingMetrics

/** Aggregates the session without retaining an unbounded list of samples. */
data class SessionSampleSummary(
    val fpsCount: Long,
    val fpsSum: Long,
    val minFps: Int?,
    val peakTempC: Int?,
    val minPingMs: Int?,
    val peakRamMb: Int,
    val framePacing: FramePacingMetrics? = null
) {
    val avgFps: Int?
        get() = if (fpsCount == 0L) null else (fpsSum.toDouble() / fpsCount).toInt()

    companion object {
        val EMPTY = SessionSampleSummary(0L, 0L, null, null, null, 0)
    }
}

/** Constant-memory statistics for a potentially long-running boost session. */
class SessionSampleAccumulator {
    private var fpsCount = 0L
    private var fpsSum = 0L
    private var minFps: Int? = null
    private var peakTempC: Int? = null
    private var minPingMs: Int? = null
    private var peakRamMb = 0
    private var framePacing: FramePacingMetrics? = null

    @Synchronized
    fun add(
        fps: Int?,
        tempC: Int?,
        pingMs: Int?,
        ramMb: Int?,
        framePacing: FramePacingMetrics? = null
    ) {
        fps?.let {
            fpsCount += 1L
            fpsSum += it.toLong()
            minFps = minFps?.let { old -> minOf(old, it) } ?: it
        }
        tempC?.let { peakTempC = peakTempC?.let { old -> maxOf(old, it) } ?: it }
        pingMs?.let { minPingMs = minPingMs?.let { old -> minOf(old, it) } ?: it }
        if (ramMb != null && ramMb > 0) peakRamMb = maxOf(peakRamMb, ramMb)
        framePacing?.let { this.framePacing = it }
    }

    @Synchronized
    fun snapshot(): SessionSampleSummary = SessionSampleSummary(
        fpsCount, fpsSum, minFps, peakTempC, minPingMs, peakRamMb, framePacing
    )

    @Synchronized
    fun clear() {
        fpsCount = 0L
        fpsSum = 0L
        minFps = null
        peakTempC = null
        minPingMs = null
        peakRamMb = 0
        framePacing = null
    }
}

/**
 * Aggregated performance of one boost session.
 * Pure data + pure builder so the math is unit-testable on the JVM.
 *
 * [previousAvgFps] is the most recent measured average for the same game,
 * persisted by the caller. [deltaFps] is descriptive only: sessions are not
 * controlled A/B trials and workload, settings, or thermal conditions may differ.
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
    val deltaFps: Int?,
    val endBottleneck: Bottleneck? = null,
    val framePacing: FramePacingMetrics? = null,
    val gamePackage: String? = null
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
        previousAvgFps: Int?,
        endBottleneck: Bottleneck? = null,
        framePacing: FramePacingMetrics? = null,
        gamePackage: String? = null
    ): SessionReport = summarize(
        startedAt = startedAt,
        endedAt = endedAt,
        samples = SessionSampleSummary(
            fpsCount = fpsSamples.size.toLong(),
            fpsSum = fpsSamples.sumOf { it.toLong() },
            minFps = fpsSamples.minOrNull(),
            peakTempC = tempSamples.maxOrNull(),
            minPingMs = pingSamples.minOrNull(),
            peakRamMb = ramMbSamples.maxOrNull() ?: 0
        ),
        applied = applied,
        failed = failed,
        previousAvgFps = previousAvgFps,
        endBottleneck = endBottleneck,
        framePacing = framePacing,
        gamePackage = gamePackage
    )

    fun summarize(
        startedAt: Long,
        endedAt: Long,
        samples: SessionSampleSummary,
        applied: Int,
        failed: Int,
        previousAvgFps: Int?,
        endBottleneck: Bottleneck? = null,
        framePacing: FramePacingMetrics? = samples.framePacing,
        gamePackage: String? = null
    ): SessionReport {
        val avgFps = samples.avgFps
        val safeGamePackage = gamePackage?.takeIf(ShellInput::isPackageName)
        val comparablePreviousFps = previousAvgFps?.takeIf { safeGamePackage != null && it > 0 }
        val delta = if (avgFps != null && comparablePreviousFps != null) {
            avgFps - comparablePreviousFps
        } else null
        return SessionReport(
            startedAt = startedAt,
            endedAt = endedAt,
            durationSec = ((endedAt - startedAt) / 1000L).toInt().coerceAtLeast(0),
            avgFps = avgFps,
            minFps = samples.minFps,
            peakTempC = samples.peakTempC,
            minPingMs = samples.minPingMs,
            peakRamMb = samples.peakRamMb,
            applied = applied,
            failed = failed,
            previousAvgFps = comparablePreviousFps,
            deltaFps = delta,
            endBottleneck = endBottleneck,
            framePacing = framePacing,
            gamePackage = safeGamePackage
        )
    }
}
