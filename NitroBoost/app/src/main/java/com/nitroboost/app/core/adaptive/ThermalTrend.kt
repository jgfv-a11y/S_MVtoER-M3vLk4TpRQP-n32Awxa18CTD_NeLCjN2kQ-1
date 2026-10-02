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
    @Synchronized
    fun record(nowMs: Long, tempC: Double?) {
        if (tempC == null) return
        samples.add(Sample(nowMs, tempC))
        while (samples.isNotEmpty() && nowMs - samples.first().t > windowMs) {
            samples.removeAt(0)
        }
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    /** Slope in °C per minute over the buffered window; 0.0 with <2 samples. */
    @Synchronized
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
    @Synchronized
    fun effectiveStatus(currentStatus: Int): Int {
        val current = currentStatus.coerceIn(0, 6)
        val slope = slopePerMin()
        val predicted = when {
            current >= 3 -> current
            slope >= STRONG_WARN_SLOPE -> maxOf(current + 2, 2)
            slope >= EARLY_WARN_SLOPE -> maxOf(current + 1, 1)
            else -> current
        }
        return maxOf(current, predicted.coerceAtMost(4))
    }

    /** True while the trend is cooling down — safe to resume trials. */
    @Synchronized
    fun cooling(currentStatus: Int): Boolean =
        slopePerMin() <= -0.3 || currentStatus <= 1

    companion object {
        /** °C per minute considered an early warning. */
        const val EARLY_WARN_SLOPE = 1.2
        /** Rapid rise escalates at least to MODERATE even from nominal OS status. */
        const val STRONG_WARN_SLOPE = 2.0
    }
}
