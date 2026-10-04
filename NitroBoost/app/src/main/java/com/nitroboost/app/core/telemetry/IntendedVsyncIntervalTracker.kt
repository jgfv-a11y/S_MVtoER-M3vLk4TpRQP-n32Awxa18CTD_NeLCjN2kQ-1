package com.nitroboost.app.core.telemetry

/** Converts ordered, real IntendedVsync nanosecond timestamps into bounded gaps. */
class IntendedVsyncIntervalTracker(
    private val minIntervalMs: Double = MIN_INTERVAL_MS,
    private val maxIntervalMs: Double = MAX_INTERVAL_MS
) {
    private var previousVsyncNs = Long.MIN_VALUE

    fun append(timestampsNs: List<Long>): List<Double> {
        val intervals = ArrayList<Double>()
        for (timestampNs in timestampsNs) {
            if (timestampNs <= 0L) continue
            if (previousVsyncNs != Long.MIN_VALUE && timestampNs < previousVsyncNs) {
                // gfxinfo timestamps can restart with a new game process even
                // when its cumulative frame counter was not reset yet.
                previousVsyncNs = timestampNs
                continue
            }
            if (previousVsyncNs != Long.MIN_VALUE && timestampNs > previousVsyncNs) {
                val intervalMs = (timestampNs - previousVsyncNs) / NANOS_PER_MILLISECOND
                if (intervalMs.isFinite() && intervalMs in minIntervalMs..maxIntervalMs) {
                    intervals.add(intervalMs)
                }
            }
            if (timestampNs > previousVsyncNs) previousVsyncNs = timestampNs
        }
        return intervals
    }

    fun reset() {
        previousVsyncNs = Long.MIN_VALUE
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val MIN_INTERVAL_MS = 0.1
        private const val MAX_INTERVAL_MS = 1_000.0
    }
}
