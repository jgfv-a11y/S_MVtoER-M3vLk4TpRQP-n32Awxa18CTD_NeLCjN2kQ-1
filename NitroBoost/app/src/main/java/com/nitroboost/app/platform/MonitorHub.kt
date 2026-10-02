package com.nitroboost.app.platform

import android.content.Context
import android.os.SystemClock
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
    private val ram = RamSampler(ctx)
    private val thermal = ThermalSampler(ctx)
    private val battery = BatterySampler(ctx)
    private val net = NetSampler()
    private val fps = FpsSampler()
    private val executor by lazy(LazyThreadSafetyMode.NONE) { AndroidExecutor(ctx) }

    private var lastThermalRead = 0L
    private var cachedTempC: Double? = null
    private var lastBatteryRead = 0L
    private var cachedBattery = 0 to false
    private var lastPingRead = 0L
    private var cachedPing: Int? = null
    private var lastRetransRead = 0L
    private var cachedRetrans: Int? = null

    /** Current game package provider (the layer that knows the session). */
    var gamePackage: () -> String? = { null }

    fun start(listener: (MonitorSnapshot) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                val snap = try {
                    val now = SystemClock.elapsedRealtime()
                    val (cpuPct, perCore) = cpu.sample()
                    val (ramTotal, ramUsed, ramPct) = ram.sample()
                    if (lastBatteryRead == 0L || now - lastBatteryRead >= BATTERY_INTERVAL_MS) {
                        cachedBattery = battery.sample()
                        lastBatteryRead = now
                    }
                    if (lastThermalRead == 0L || now - lastThermalRead >= THERMAL_INTERVAL_MS) {
                        cachedTempC = thermal.tempC()
                        lastThermalRead = now
                    }
                    if (lastPingRead == 0L || now - lastPingRead >= PING_INTERVAL_MS) {
                        cachedPing = net.pingMs()
                        lastPingRead = now
                    }
                    if (lastRetransRead == 0L || now - lastRetransRead >= RETRANS_INTERVAL_MS) {
                        cachedRetrans = net.retransPerSec()
                        lastRetransRead = now
                    }
                    val pkg = gamePackage()?.takeIf { it.isNotBlank() }
                    val fpsObservation = fps.pollObservation(pkg.orEmpty(), executor)
                    val thermalStatus = thermal.statusOrNull()
                    MonitorSnapshot(
                        cpuPct = cpuPct,
                        perCore = perCore,
                        ramUsedMb = ramUsed,
                        ramTotalMb = ramTotal,
                        ramPct = ramPct,
                        tempC = cachedTempC,
                        batteryPct = cachedBattery.first,
                        charging = cachedBattery.second,
                        fps = fpsObservation.fps,
                        pingMs = cachedPing,
                        retransPerSec = cachedRetrans,
                        thermalStatus = thermalStatus ?: 0,
                        ts = SystemClock.elapsedRealtime(),
                        frameTimesMs = fpsObservation.frameTimesMs,
                        gamePackage = pkg,
                        processEpoch = fpsObservation.processEpoch,
                        thermalSampleAvailable = thermalStatus != null,
                        energyMah = null
                    )
                } catch (_: Exception) {
                    null
                }
                if (snap == null) {
                    delay(1_000)
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
        const val THERMAL_INTERVAL_MS = 3_000L
        const val PING_INTERVAL_MS = 10_000L
        const val RETRANS_INTERVAL_MS = 2_000L
        const val BATTERY_INTERVAL_MS = 10_000L
    }
}
