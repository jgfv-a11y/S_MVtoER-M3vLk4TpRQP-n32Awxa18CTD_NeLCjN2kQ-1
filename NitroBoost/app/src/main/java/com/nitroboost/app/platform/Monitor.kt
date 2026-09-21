package com.nitroboost.app.platform

import android.content.Context
import android.app.ActivityManager
import com.nitroboost.app.core.CpuMath
import java.io.File

data class MonitorSnapshot(
    val cpuPct: Int = 0,
    val perCore: List<Int> = emptyList(),
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val ramPct: Int = 0,
    val tempC: Double? = null,
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val fps: Int? = null,
    val pingMs: Int? = null,
    val retransPerSec: Int? = null,
    val thermalStatus: Int = 0
) {
    companion object {
        val EMPTY = MonitorSnapshot()
    }
}

class CpuSampler {
    private var prevAgg: CpuMath.Sample? = null
    private val prevCores = HashMap<Int, CpuMath.Sample>()

    fun sample(): Pair<Int, List<Int>> {
        val text = try {
            File("/proc/stat").readText()
        } catch (e: Exception) {
            return 0 to emptyList()
        }
        val agg = text.lineSequence()
            .firstOrNull { it.startsWith("cpu ") }
            ?.let(CpuMath::parseLine)

        val coreRegex = Regex("^cpu(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
        val cores = LinkedHashMap<Int, Int>()
        for (m in coreRegex.findAll(text)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val nums = m.groupValues.drop(2).mapNotNull { it.toLongOrNull() }
            if (nums.size < 5) continue
            val s = CpuMath.Sample(nums.sum(), nums[3] + nums[4])
            val prev = prevCores[n]
            if (prev != null) cores[n] = CpuMath.usagePercent(prev, s)
            prevCores[n] = s
        }

        val pct = if (agg != null) {
            val prev = prevAgg
            if (prev != null) CpuMath.usagePercent(prev, agg) else 0
        } else 0
        prevAgg = agg
        return pct to cores.values.toList()
    }
}

class RamSampler(private val ctx: Context) {
    fun sample(): Triple<Long, Long, Int> {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = mi.totalMem / (1024 * 1024)
            val used = ((mi.totalMem - mi.availMem) / (1024 * 1024)).coerceAtLeast(0)
            val pct = if (total > 0) (used * 100 / total).toInt() else 0
            Triple(total, used, pct)
        } catch (e: Exception) {
            Triple(0, 0, 0)
        }
    }
}

class ThermalSampler(private val ctx: Context) {

    /** Highest temperature reported by any thermal zone, in Celsius. */
    fun tempC(): Double? {
        var max: Double? = null
        for (i in 0 until 40) {
            val f = File("/sys/class/thermal/thermal_zone$i/temp")
            if (!f.exists()) continue
            val v = try {
                f.readText().trim().toIntOrNull()
            } catch (e: Exception) {
                null
            } ?: continue
            val c = v / 1000.0
            if (max == null || c > max) max = c
        }
        return max
    }

    @Suppress("DEPRECATION")
    fun status(): Int {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.currentThermalStatus
        } catch (e: Exception) {
            0
        }
    }
}

class BatterySampler(private val ctx: Context) {
    fun sample(): Pair<Int, Boolean> {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = when (ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(android.content.Intent.EXTRA_PLUGGED, 0)) {
                0 -> false
                else -> true
            }
            level to charging
        } catch (e: Exception) {
            0 to false
        }
    }
}
