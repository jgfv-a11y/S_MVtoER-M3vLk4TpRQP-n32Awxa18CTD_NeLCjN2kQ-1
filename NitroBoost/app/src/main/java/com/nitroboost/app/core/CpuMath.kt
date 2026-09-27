package com.nitroboost.app.core

/**
 * Pure parsing helpers for /proc/stat — unit-testable without Android.
 */
object CpuMath {

    data class Sample(val total: Long, val idle: Long)

    /**
     * Parse the aggregate "cpu" line (or a "cpuN" line) of /proc/stat.
     * Fields: user nice system idle iowait irq softirq steal guest guest_nice
     */
    fun parseLine(line: String): Sample? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val name = parts[0]
        if (!name.startsWith("cpu")) return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val total = nums.sum()
        val idle = nums[3] + (if (nums.size > 4) nums[4] else 0L) // idle + iowait
        return Sample(total, idle)
    }

    /** CPU usage % between two samples. */
    fun usagePercent(prev: Sample, now: Sample): Int {
        val dt = now.total - prev.total
        val di = now.idle - prev.idle
        if (dt <= 0) return 0
        val pct = (dt - di) * 100 / dt
        return pct.coerceIn(0L, 100L).toInt()
    }
}
