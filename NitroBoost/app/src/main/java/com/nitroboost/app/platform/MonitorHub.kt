package com.nitroboost.app.platform

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 2-second sampling loop producing [MonitorSnapshot] values for the UI,
 * the overlay and the widget. Runs off the main thread; every sampler is
 * exception-proof so one broken source never kills the loop.
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

    /** Current game package provider (the layer that knows the session). */
    var gamePackage: () -> String? = { null }

    fun start(listener: (MonitorSnapshot) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                val snap = try {
                    val (cpuPct, perCore) = cpu.sample()
                    val (ramTotal, ramUsed, ramPct) = ram.sample()
                    val (batPct, charging) = battery.sample()
                    val ex = AndroidExecutor(ctx)
                    val gamePkg = gamePackage()
                    MonitorSnapshot(
                        cpuPct = cpuPct,
                        perCore = perCore,
                        ramUsedMb = ramUsed,
                        ramTotalMb = ramTotal,
                        ramPct = ramPct,
                        tempC = thermal.tempC(),
                        batteryPct = batPct,
                        charging = charging,
                        fps = if (!gamePkg.isNullOrBlank()) fps.poll(gamePkg, ex) else null,
                        pingMs = net.pingMs(),
                        retransPerSec = net.retransPerSec(),
                        thermalStatus = thermal.status(),
                        ts = android.os.SystemClock.elapsedRealtime()
                    )
                } catch (e: Exception) {
                    MonitorSnapshot.EMPTY
                }
                try {
                    listener(snap)
                } catch (e: Exception) {
                    // listener gone — keep sampling
                }
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
