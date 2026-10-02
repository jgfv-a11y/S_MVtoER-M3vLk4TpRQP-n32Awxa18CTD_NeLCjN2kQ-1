package com.nitroboost.app.core.adaptive

import kotlin.math.ceil
import kotlin.math.sqrt

/** Pure parser/statistics so frame-time behavior is covered by JVM tests. */
object GfxFrameStatsParser {
    data class Parsed(
        val frameTimesMs: List<Double>,
        val lastCompletedNs: Long,
        val rowsSeen: Int
    )

    /**
     * Parses real `dumpsys gfxinfo <package> framestats` PROFILEDATA rows.
     * Frame time is FrameCompleted - IntendedVsync (both source timestamps in
     * nanoseconds); no FPS-derived or synthetic frame durations are produced.
     */
    fun parse(
        output: String,
        afterCompletedNs: Long = Long.MIN_VALUE,
        maxFrames: Int = 1_000
    ): Parsed {
        var intendedIndex = -1
        var completedIndex = -1
        var rowsSeen = 0
        var lastCompleted = afterCompletedNs
        val frameLimit = maxFrames.coerceAtLeast(0)
        val durations = ArrayList<Double>(minOf(frameLimit, 128))

        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("---")) continue
            if (trimmed.contains("IntendedVsync") && trimmed.contains("FrameCompleted")) {
                val columns = trimmed.split(',').map { it.trim() }
                intendedIndex = columns.indexOf("IntendedVsync")
                completedIndex = columns.indexOf("FrameCompleted")
                continue
            }
            if (intendedIndex < 0 || completedIndex < 0 || !trimmed.firstOrNull().let { it?.isDigit() == true }) {
                continue
            }
            val columns = trimmed.split(',')
            if (columns.size <= maxOf(intendedIndex, completedIndex)) continue
            val intended = columns[intendedIndex].trim().toLongOrNull() ?: continue
            val completed = columns[completedIndex].trim().toLongOrNull() ?: continue
            rowsSeen++
            if (completed <= afterCompletedNs || intended <= 0L) continue
            if (completed > lastCompleted) lastCompleted = completed
            if (completed <= intended) continue
            val durationMs = (completed - intended) / 1_000_000.0
            if (frameLimit == 0 || !durationMs.isFinite() || durationMs !in MIN_FRAME_MS..MAX_FRAME_MS) continue
            if (durations.size == frameLimit) durations.removeAt(0)
            durations.add(durationMs)
        }
        return Parsed(durations, lastCompleted, rowsSeen)
    }

    private const val MIN_FRAME_MS = 0.1
    private const val MAX_FRAME_MS = 1_000.0
}

object FrameTimeAnalysis {
    /**
     * Summarizes measured frame durations. A hitch is strictly longer than
     * two target frame budgets (e.g. >33.3ms at 60Hz); absent input returns
     * null rather than estimating from an FPS counter.
     */
    fun summarize(
        frameTimesMs: List<Double>,
        targetFps: Int,
        hitchMultiplier: Double = HITCH_MULTIPLIER
    ): FrameTimeMetrics? {
        val samples = frameTimesMs.filter { it.isFinite() && it in 0.1..1_000.0 }
        if (samples.isEmpty() || targetFps <= 0) return null
        val sorted = samples.sorted()
        val mean = sorted.average()
        val variance = if (sorted.size > 1) {
            sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1)
        } else 0.0
        val threshold = (1_000.0 / targetFps) * hitchMultiplier
        val hitches = sorted.count { it > threshold }
        return FrameTimeMetrics(
            frameCount = sorted.size,
            medianMs = median(sorted),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
            varianceMs2 = variance,
            hitchCount = hitches,
            hitchRate = hitches.toDouble() / sorted.size,
            hitchThresholdMs = threshold
        )
    }

    private fun median(sorted: List<Double>): Double = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    const val HITCH_MULTIPLIER = 2.0
}
