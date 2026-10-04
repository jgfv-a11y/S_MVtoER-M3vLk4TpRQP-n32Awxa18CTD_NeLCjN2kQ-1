package com.nitroboost.app.platform

import android.content.Context
import android.os.SystemClock
import com.nitroboost.app.core.telemetry.BatterySnapshot
import com.nitroboost.app.core.telemetry.CpuSnapshot
import com.nitroboost.app.core.telemetry.FrameSnapshot
import com.nitroboost.app.core.telemetry.GpuSnapshot
import com.nitroboost.app.core.telemetry.MemoryPressure
import com.nitroboost.app.core.telemetry.MemorySnapshot
import com.nitroboost.app.core.telemetry.NetworkSnapshot
import com.nitroboost.app.core.telemetry.SensorAvailability
import com.nitroboost.app.core.telemetry.SensorFusion
import com.nitroboost.app.core.telemetry.ThermalSnapshot
import com.nitroboost.app.core.telemetry.TelemetrySourceTimestamps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 1-second monitor snapshots for UI/session use. Expensive or low-value
 * sources have their own slower intervals: gfxinfo every 2s, ping every 10s,
 * raw thermal-zone scan every 3s, and battery every 10s.
 */
class MonitorHub(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    private val cpu = CpuSampler()
    private val cpuFrequency = CpuFrequencySampler()
    private val ram = RamSampler(ctx)
    private val thermal = ThermalSampler(ctx)
    private val battery = BatterySampler(ctx)
    private val net = NetSampler()
    private val fps = FpsSampler()
    private var deviceSnapshot = DeviceSnapshotProvider.read(ctx)
    private val executor by lazy(LazyThreadSafetyMode.NONE) { AndroidExecutor(ctx) }

    private var lastDeviceMetadataRead = 0L
    private var deviceSnapshotAtMs: Long? = null
    private var lastThermalRead = 0L
    private var cachedTempC: Double? = null
    private var cachedTempAtMs: Long? = null
    private var lastBatteryRead = 0L
    private var cachedBattery = BatteryReading()
    private var cachedBatteryAtMs: Long? = null
    private var lastPingRead = 0L
    private var cachedPing: Int? = null
    private var cachedPingAtMs: Long? = null
    private var lastRetransRead = 0L
    private var cachedRetrans: Int? = null
    private var cachedRetransAtMs: Long? = null

    /** Current game package and target provider (the session layer owns both). */
    var gamePackage: () -> String? = { null }
    var targetFps: () -> Int? = { null }

    fun start(listener: (MonitorSnapshot) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                val snap = try {
                    val now = SystemClock.elapsedRealtime()
                    val cpuReading = cpu.sample()
                    val cpuFrequencyReading = cpuFrequency.sample()
                    val ramReading = ram.sample()
                    if (lastDeviceMetadataRead == 0L ||
                        now - lastDeviceMetadataRead >= DEVICE_METADATA_INTERVAL_MS
                    ) {
                        runCatching { DeviceSnapshotProvider.read(ctx) }.getOrNull()?.let {
                            deviceSnapshot = it
                            deviceSnapshotAtMs = now
                        }
                        lastDeviceMetadataRead = now
                    }
                    if (lastBatteryRead == 0L || now - lastBatteryRead >= BATTERY_INTERVAL_MS) {
                        cachedBattery = battery.sample()
                        cachedBatteryAtMs = now.takeIf {
                            cachedBattery.levelPct != null || cachedBattery.charging != null ||
                                cachedBattery.temperatureC != null || cachedBattery.currentMilliAmps != null
                        }
                        lastBatteryRead = now
                    }
                    if (lastThermalRead == 0L || now - lastThermalRead >= THERMAL_INTERVAL_MS) {
                        cachedTempC = thermal.tempC()
                        cachedTempAtMs = now.takeIf { cachedTempC != null }
                        lastThermalRead = now
                    }
                    if (lastPingRead == 0L || now - lastPingRead >= PING_INTERVAL_MS) {
                        cachedPing = net.pingMs()
                        cachedPingAtMs = now.takeIf { cachedPing != null }
                        lastPingRead = now
                    }
                    if (lastRetransRead == 0L || now - lastRetransRead >= RETRANS_INTERVAL_MS) {
                        cachedRetrans = net.retransPerSec()
                        cachedRetransAtMs = now.takeIf { cachedRetrans != null }
                        lastRetransRead = now
                    }
                    val pkg = gamePackage()?.takeIf { it.isNotBlank() }
                    val fpsObservation = fps.pollObservation(pkg.orEmpty(), executor)
                    val thermalStatus = thermal.statusOrNull()
                    val sampledAt = SystemClock.elapsedRealtime()
                    val target = targetFps()?.takeIf { it in 1..1_000 }
                    val performance = SensorFusion.fuse(
                        capturedAtMs = sampledAt,
                        cpu = CpuSnapshot(
                            utilizationPct = cpuReading.utilizationPct?.toDouble(),
                            perCoreUtilizationPct = cpuReading.perCoreUtilizationPct
                                ?.map { it.toDouble() },
                            meanPolicyFrequencyKHz = cpuFrequencyReading.meanPolicyFrequencyKHz,
                            frequencyAvailability = cpuFrequencyReading.availability
                        ),
                        gpu = GpuSnapshot(
                            utilizationPct = null,
                            frequencyKHz = null,
                            model = null,
                            renderer = null,
                            utilizationAvailability = SensorAvailability.UNAVAILABLE,
                            frequencyAvailability = SensorAvailability.UNAVAILABLE,
                            reason = "No trusted system-wide GPU telemetry source is wired"
                        ),
                        memory = MemorySnapshot(
                            usedBytes = ramReading.usedBytes,
                            availableBytes = ramReading.availableBytes,
                            totalBytes = ramReading.totalBytes,
                            thresholdBytes = ramReading.thresholdBytes,
                            pressure = when (ramReading.lowMemory) {
                                true -> MemoryPressure.LOW
                                false -> MemoryPressure.NORMAL
                                null -> null
                            }
                        ),
                        thermal = ThermalSnapshot(
                            osStatus = thermalStatus,
                            temperatureC = cachedTempC
                        ),
                        battery = BatterySnapshot(
                            levelPct = cachedBattery.levelPct?.toDouble(),
                            charging = cachedBattery.charging,
                            temperatureC = cachedBattery.temperatureC,
                            currentMilliAmps = cachedBattery.currentMilliAmps,
                            energyMah = null
                        ),
                        network = NetworkSnapshot(
                            latencyMs = cachedPing?.toDouble(),
                            retransmissionsPerSec = cachedRetrans?.toDouble()
                        ),
                        frame = FrameSnapshot(
                            fps = fpsObservation.fps?.toDouble(),
                            targetFps = target,
                            frameTimesMs = fpsObservation.frameTimesMs.takeIf { it.isNotEmpty() },
                            intendedVsyncIntervalsMs = fpsObservation.frameIntervalsMs
                                .takeIf { it.isNotEmpty() }
                        ),
                        device = deviceSnapshot,
                        sourceTimestamps = TelemetrySourceTimestamps(
                            cpuAtMs = now,
                            cpuFrequencyAtMs = now.takeIf {
                                cpuFrequencyReading.meanPolicyFrequencyKHz != null
                            },
                            memoryAtMs = now.takeIf { ramReading.available },
                            thermalStatusAtMs = sampledAt.takeIf { thermalStatus != null },
                            temperatureAtMs = cachedTempAtMs,
                            batteryAtMs = cachedBatteryAtMs,
                            networkLatencyAtMs = cachedPingAtMs,
                            networkRetransmissionsAtMs = cachedRetransAtMs,
                            frameAtMs = fpsObservation.measuredAtMs.takeIf {
                                fpsObservation.sourceAvailable
                            },
                            deviceAtMs = deviceSnapshotAtMs
                        )
                    )
                    MonitorSnapshot(
                        cpuPct = cpuReading.utilizationPct ?: 0,
                        perCore = cpuReading.perCoreUtilizationPct.orEmpty(),
                        ramUsedMb = ramReading.usedMb,
                        ramTotalMb = ramReading.totalMb,
                        ramPct = ramReading.usedPct,
                        tempC = cachedTempC,
                        batteryPct = cachedBattery.levelPct ?: 0,
                        charging = cachedBattery.charging ?: false,
                        fps = fpsObservation.fps,
                        pingMs = cachedPing,
                        retransPerSec = cachedRetrans,
                        thermalStatus = thermalStatus ?: 0,
                        ts = sampledAt,
                        frameTimesMs = fpsObservation.frameTimesMs,
                        gamePackage = pkg,
                        processEpoch = fpsObservation.processEpoch,
                        thermalSampleAvailable = thermalStatus != null || cachedTempC != null,
                        energyMah = null,
                        cpuSampleAvailable = cpuReading.utilizationPct != null,
                        ramSampleAvailable = ramReading.available,
                        batterySampleAvailable = cachedBattery.levelPct != null ||
                            cachedBattery.charging != null || cachedBattery.temperatureC != null ||
                            cachedBattery.currentMilliAmps != null,
                        fpsSourceAvailable = fpsObservation.sourceAvailable,
                        frameIntervalsMs = fpsObservation.frameIntervalsMs,
                        performance = performance
                    )
                } catch (_: Exception) {
                    null
                }
                if (snap == null) {
                    delay(MONITOR_INTERVAL_MS)
                    continue
                }
                try {
                    listener(snap)
                } catch (_: Exception) {
                    // A removed observer must not stop sampling for other consumers.
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val MONITOR_INTERVAL_MS = 1_000L
        const val DEVICE_METADATA_INTERVAL_MS = 5_000L
        const val THERMAL_INTERVAL_MS = 3_000L
        const val PING_INTERVAL_MS = 10_000L
        const val RETRANS_INTERVAL_MS = 2_000L
        const val BATTERY_INTERVAL_MS = 10_000L
    }
}
