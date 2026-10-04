package com.nitroboost.app.core.adaptive

import kotlin.math.ceil

/** Pure parser/statistics so frame-time behavior is covered by JVM tests. */
object GfxFrameStatsParser {
    data class Parsed(
        val frameTimesMs: List<Double>,
        val lastCompletedNs: Long,
        val rowsSeen: Int,
        /** IntendedVsync timestamps corresponding to the valid measured durations. */
        val intendedVsyncNs: List<Long> = emptyList()
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
        val intendedVsyncs = ArrayList<Long>(minOf(frameLimit, 128))

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
            if (durations.size == frameLimit) {
                durations.removeAt(0)
                intendedVsyncs.removeAt(0)
            }
            durations.add(durationMs)
            intendedVsyncs.add(intended)
        }
        return Parsed(durations, lastCompleted, rowsSeen, intendedVsyncs)
    }

    private const val MIN_FRAME_MS = 0.1
    private const val MAX_FRAME_MS = 1_000.0
}

object FrameTimeAnalysis {
    /** Distribution of measured durations even when no target-FPS budget exists. */
    fun distribution(frameTimesMs: List<Double>): FrameTimeDistribution? {
        val sorted = frameTimesMs.filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }.sorted()
        if (sorted.isEmpty()) return null
        val mean = sorted.average()
        val variance = if (sorted.size > 1) {
            sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1)
        } else 0.0
        return FrameTimeDistribution(
            frameCount = sorted.size,
            meanMs = mean,
            medianMs = median(sorted),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
            varianceMs2 = variance
        )
    }

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
        if (targetFps <= 0 || !hitchMultiplier.isFinite() || hitchMultiplier <= 0.0) return null
        val distribution = distribution(frameTimesMs) ?: return null
        val hitches = frameTimesMs.count {
            it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS &&
                it > (1_000.0 / targetFps) * hitchMultiplier
        }
        return FrameTimeMetrics(
            frameCount = distribution.frameCount,
            medianMs = distribution.medianMs,
            p95Ms = distribution.p95Ms,
            p99Ms = distribution.p99Ms,
            varianceMs2 = distribution.varianceMs2,
            hitchCount = hitches,
            hitchRate = hitches.toDouble() / distribution.frameCount,
            hitchThresholdMs = (1_000.0 / targetFps) * hitchMultiplier
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

    private const val MIN_FRAME_MS = 0.1
    private const val MAX_FRAME_MS = 1_000.0
    const val HITCH_MULTIPLIER = 2.0
}
