package com.nitroboost.app.core.adaptive

/**
 * Predictive thermal headroom from the temperature TREND, not just the
 * current reading.
 *
 * The OS thermal status flips late — by the time it says MODERATE the SoC
 * is already throttling and frames are lost. Tracking the slope over the
 * last [windowMs] lets the engine de-escalate one tier EARLY while the
 * device is still nominal but clearly heating up (e.g. +2°C/min during a
 * boss fight that started on a cold boot).
 */
class ThermalTrend(private val windowMs: Long = 120_000L) {

    private class Sample(val t: Long, val c: Double)

    private val samples = ArrayList<Sample>()

    /** Record a temperature reading; trims samples outside the window. */
    fun record(nowMs: Long, tempC: Double?) {
        if (tempC == null) return
        samples.add(Sample(nowMs, tempC))
        while (samples.isNotEmpty() && nowMs - samples.first().t > windowMs) {
            samples.removeAt(0)
        }
    }

    fun clear() {
        samples.clear()
    }

    /** Slope in °C per minute over the buffered window; 0.0 with <2 samples. */
    fun slopePerMin(): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val dt = (last.t - first.t) / 60_000.0
        if (dt <= 0.0) return 0.0
        return (last.c - first.c) / dt
    }

    /**
     * Effective status: the OS status, escalated by one tier when the trend
     * is sharply rising while we are still below SEVERE.
     * This is the "thermal headroom prediction" — act before the flip.
     */
    fun effectiveStatus(currentStatus: Int): Int {
        val s = slopePerMin()
        if (s >= EARLY_WARN_SLOPE && currentStatus in 1..2) return currentStatus + 1
        return currentStatus
    }

    /** True while the trend is cooling down — safe to resume trials. */
    fun cooling(currentStatus: Int): Boolean =
        slopePerMin() <= -0.3 || currentStatus <= 1

    companion object {
        /** °C per minute considered an early warning. */
        const val EARLY_WARN_SLOPE = 1.2
    }
}
