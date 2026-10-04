package com.nitroboost.app.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.nitroboost.app.core.CpuMath
import com.nitroboost.app.core.telemetry.PerformanceSnapshot
import com.nitroboost.app.core.telemetry.SensorAvailability
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

data class MonitorSnapshot(
    // Legacy flat fields remain for the existing UI and session reports.
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
    val thermalStatus: Int = 0,
    val ts: Long = 0,
    /** New frame durations from gfxinfo, empty when framestats is unavailable. */
    val frameTimesMs: List<Double> = emptyList(),
    val gamePackage: String? = null,
    val processEpoch: Long = 0L,
    val thermalSampleAvailable: Boolean = false,
    /** Null until a real energy counter is wired by a platform source. */
    val energyMah: Double? = null,
    /** True only when CPU utilization has a valid delta between /proc/stat reads. */
    val cpuSampleAvailable: Boolean = false,
    val ramSampleAvailable: Boolean = false,
    val batterySampleAvailable: Boolean = false,
    val fpsSourceAvailable: Boolean = false,
    /** Measured gaps between consecutive IntendedVsync timestamps. */
    val frameIntervalsMs: List<Double> = emptyList(),
    /** Unified, nullable telemetry view; null only for an unproduced/empty sample. */
    val performance: PerformanceSnapshot? = null
) {
    companion object {
        val EMPTY = MonitorSnapshot()
    }
}

data class CpuReading(
    val utilizationPct: Int?,
    val perCoreUtilizationPct: List<Int>?
)

class CpuSampler {
    private var prevAgg: CpuMath.Sample? = null
    private val prevCores = HashMap<Int, CpuMath.Sample>()

    fun sample(): CpuReading {
        val text = try {
            File("/proc/stat").readText()
        } catch (_: Exception) {
            return CpuReading(null, null)
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
            val sample = CpuMath.Sample(nums.sum(), nums[3] + nums[4])
            val previous = prevCores[n]
            if (previous != null && sample.total > previous.total) {
                cores[n] = CpuMath.usagePercent(previous, sample)
            }
            prevCores[n] = sample
        }

        val previousAggregate = prevAgg
        prevAgg = agg
        val utilization = if (agg != null && previousAggregate != null && agg.total > previousAggregate.total) {
            CpuMath.usagePercent(previousAggregate, agg)
        } else null
        return CpuReading(utilization, cores.values.toList().takeIf { it.isNotEmpty() })
    }
}

data class CpuFrequencyReading(
    val meanPolicyFrequencyKHz: Long? = null,
    val availability: SensorAvailability = SensorAvailability.UNAVAILABLE
)

/** Read-only best-effort cpufreq sampler; it never requests elevated access. */
class CpuFrequencySampler(
    private val cpuRoot: File = File("/sys/devices/system/cpu")
) {
    fun sample(): CpuFrequencyReading = try {
        readSample()
    } catch (_: Exception) {
        CpuFrequencyReading(availability = SensorAvailability.ERROR)
    }

    private fun readSample(): CpuFrequencyReading {
        val policiesRoot = File(cpuRoot, "cpufreq")
        val policyDirectories = policiesRoot.listFiles()
            ?.filter { it.isDirectory && POLICY_NAME.matches(it.name) }
            .orEmpty()
        val cpuDirectories = if (policyDirectories.isEmpty()) {
            cpuRoot.listFiles()
                ?.filter { it.isDirectory && CPU_NAME.matches(it.name) }
                .orEmpty()
        } else emptyList()
        val candidates = when {
            policyDirectories.isNotEmpty() -> policyDirectories.map { File(it, "scaling_cur_freq") }
            cpuDirectories.isNotEmpty() -> cpuDirectories.map { File(File(it, "cpufreq"), "scaling_cur_freq") }
            else -> return CpuFrequencyReading(
                availability = if (policiesRoot.exists()) SensorAvailability.UNAVAILABLE
                else SensorAvailability.UNSUPPORTED
            )
        }
        val existing = candidates.filter { it.exists() }
        if (existing.isEmpty()) {
            return CpuFrequencyReading(availability = SensorAvailability.UNAVAILABLE)
        }

        var denied = 0
        var failed = false
        val readings = ArrayList<Long>(existing.size)
        for (file in existing) {
            if (!file.canRead()) {
                denied++
                continue
            }
            val value = try {
                file.readText().trim().toLongOrNull()
            } catch (_: Exception) {
                failed = true
                null
            }
            if (value != null && value in MIN_FREQUENCY_KHZ..MAX_FREQUENCY_KHZ) {
                readings.add(value)
            }
        }
        if (readings.isNotEmpty()) {
            return CpuFrequencyReading(
                meanPolicyFrequencyKHz = readings.average().roundToLong(),
                availability = SensorAvailability.AVAILABLE
            )
        }
        val status = when {
            denied == existing.size -> SensorAvailability.RESTRICTED
            failed -> SensorAvailability.ERROR
            else -> SensorAvailability.UNAVAILABLE
        }
        return CpuFrequencyReading(availability = status)
    }

    companion object {
        private val POLICY_NAME = Regex("policy\\d+")
        private val CPU_NAME = Regex("cpu\\d+")
        private const val MIN_FREQUENCY_KHZ = 1L
        private const val MAX_FREQUENCY_KHZ = 20_000_000L
    }
}

data class RamReading(
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val usedBytes: Long? = null,
    val thresholdBytes: Long? = null,
    val lowMemory: Boolean? = null
) {
    val totalMb: Long get() = (totalBytes ?: 0L) / BYTES_PER_MB
    val usedMb: Long get() = (usedBytes ?: 0L) / BYTES_PER_MB
    val usedPct: Int
        get() {
            val total = totalBytes ?: return 0
            val used = usedBytes ?: return 0
            if (total <= 0L) return 0
            return (used.coerceIn(0L, total).toDouble() * 100.0 / total).toInt()
        }

    val available: Boolean get() = totalBytes != null && usedBytes != null

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}

class RamSampler(private val ctx: Context) {
    fun sample(): RamReading {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return RamReading()
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val total = info.totalMem.takeIf { it > 0L } ?: return RamReading()
            val available = info.availMem.coerceIn(0L, total)
            RamReading(
                totalBytes = total,
                availableBytes = available,
                usedBytes = total - available,
                thresholdBytes = info.threshold.takeIf { it in 0L..total },
                lowMemory = info.lowMemory
            )
        } catch (_: Exception) {
            RamReading()
        }
    }
}

class ThermalSampler(private val ctx: Context) {

    /** Highest temperature reported by any readable thermal zone, in Celsius. */
    fun tempC(): Double? {
        var max: Double? = null
        for (i in 0 until 40) {
            val file = File("/sys/class/thermal/thermal_zone$i/temp")
            if (!file.exists()) continue
            val value = try {
                file.readText().trim().toIntOrNull()
            } catch (_: Exception) {
                null
            } ?: continue
            val celsius = value / 1000.0
            if (max == null || celsius > max) max = celsius
        }
        return max
    }

    @Suppress("DEPRECATION")
    fun statusOrNull(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return null
            power.currentThermalStatus
        } catch (_: Exception) {
            null
        }
    }

    fun status(): Int = statusOrNull() ?: 0
}

data class BatteryReading(
    val levelPct: Int? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
    val currentMilliAmps: Double? = null
)

class BatterySampler(private val ctx: Context) {
    fun sample(): BatteryReading {
        return try {
            val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return BatteryReading()
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 }
            val charging = runCatching { manager.isCharging() }.getOrNull()
            val batteryIntent = ctx.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val temperatureTenthsC = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it in MIN_BATTERY_TEMP_TENTHS_C..MAX_BATTERY_TEMP_TENTHS_C }
            val currentMicroAmps = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMilliAmps = currentMicroAmps
                .takeIf { it != Long.MIN_VALUE && abs(it) <= MAX_BATTERY_CURRENT_MICRO_AMPS }
                ?.div(1_000.0)
            BatteryReading(
                levelPct = level,
                charging = charging,
                temperatureC = temperatureTenthsC?.div(10.0),
                currentMilliAmps = currentMilliAmps
            )
        } catch (_: Exception) {
            BatteryReading()
        }
    }

    companion object {
        private const val MIN_BATTERY_TEMP_TENTHS_C = -400
        private const val MAX_BATTERY_TEMP_TENTHS_C = 1_500
        private const val MAX_BATTERY_CURRENT_MICRO_AMPS = 20_000_000L
    }
}
