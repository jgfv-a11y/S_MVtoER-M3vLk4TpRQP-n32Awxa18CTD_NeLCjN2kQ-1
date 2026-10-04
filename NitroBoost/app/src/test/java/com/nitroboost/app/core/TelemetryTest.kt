package com.nitroboost.app.core

import com.nitroboost.app.core.telemetry.BatterySnapshot
import com.nitroboost.app.core.telemetry.CpuSnapshot
import com.nitroboost.app.core.telemetry.DeviceSnapshot
import com.nitroboost.app.core.telemetry.FrameSnapshot
import com.nitroboost.app.core.telemetry.GpuSnapshot
import com.nitroboost.app.core.telemetry.MemoryPressure
import com.nitroboost.app.core.telemetry.MemorySnapshot
import com.nitroboost.app.core.telemetry.NetworkSnapshot
import com.nitroboost.app.core.telemetry.SensorAvailability
import com.nitroboost.app.core.telemetry.SensorFusion
import com.nitroboost.app.core.telemetry.ThermalSnapshot
import com.nitroboost.app.core.telemetry.TelemetrySourceTimestamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    @Test fun `sensor fusion normalizes supported values and keeps unsupported values null`() {
        val result = SensorFusion.fuse(
            capturedAtMs = 123_000L,
            cpu = CpuSnapshot(
                utilizationPct = 72.0,
                perCoreUtilizationPct = listOf(40.0, Double.NaN, 105.0, 60.0),
                meanPolicyFrequencyKHz = 1_800_000L,
                frequencyAvailability = SensorAvailability.AVAILABLE
            ),
            gpu = GpuSnapshot(
                utilizationPct = null,
                frequencyKHz = null,
                reason = "no trusted reader"
            ),
            memory = MemorySnapshot(
                totalBytes = 8_000L,
                availableBytes = 2_000L,
                usedBytes = null,
                thresholdBytes = 1_000L,
                pressure = MemoryPressure.LOW
            ),
            thermal = ThermalSnapshot(osStatus = 7, temperatureC = 42.5, slopeCPerMin = 1.2),
            battery = BatterySnapshot(levelPct = 67.0, charging = false, currentMilliAmps = -850.0),
            network = NetworkSnapshot(latencyMs = 35.0, retransmissionsPerSec = -1.0),
            frame = FrameSnapshot(
                fps = 0.0,
                targetFps = 60,
                frameTimesMs = listOf(16.7, Double.NaN, -1.0, 33.0),
                intendedVsyncIntervalsMs = listOf(16.7, 33.3, Double.POSITIVE_INFINITY)
            ),
            device = DeviceSnapshot(
                manufacturer = " Example ",
                model = "Test phone",
                androidApi = 34,
                displayWidthPx = 1_080,
                displayHeightPx = 2_400,
                refreshRateHz = 120.0
            ),
            sourceTimestamps = TelemetrySourceTimestamps(
                cpuAtMs = 122_000L,
                cpuFrequencyAtMs = 130_000L,
                gpuAtMs = 122_000L,
                memoryAtMs = 121_000L,
                thermalStatusAtMs = 123_000L,
                temperatureAtMs = 120_000L,
                batteryAtMs = 121_000L,
                networkLatencyAtMs = 118_000L,
                networkRetransmissionsAtMs = 119_000L,
                frameAtMs = 122_000L,
                deviceAtMs = 123_000L
            )
        )

        assertEquals(123_000L, result.capturedAtMs)
        assertEquals(72.0, result.cpu.utilizationPct!!, 0.0)
        assertEquals(listOf(40.0, 60.0), result.cpu.perCoreUtilizationPct)
        assertEquals(1_800_000L, result.cpu.meanPolicyFrequencyKHz)
        assertEquals(SensorAvailability.AVAILABLE, result.cpu.frequencyAvailability)
        assertNull(result.gpu.utilizationPct)
        assertEquals(SensorAvailability.UNAVAILABLE, result.gpu.utilizationAvailability)
        assertEquals("no trusted reader", result.gpu.reason)
        assertEquals(75.0, result.memory.usedPct!!, 0.001)
        assertEquals(MemoryPressure.LOW, result.memory.pressure)
        assertNull(result.thermal.osStatus)
        assertEquals(42.5, result.thermal.temperatureC!!, 0.0)
        assertEquals(67.0, result.battery.levelPct!!, 0.0)
        assertEquals(-850.0, result.battery.currentMilliAmps!!, 0.0)
        assertEquals(35.0, result.network.latencyMs!!, 0.0)
        assertNull(result.network.retransmissionsPerSec)
        assertNull(result.frame.fps)
        assertEquals(listOf(16.7, 33.0), result.frame.frameTimesMs)
        assertEquals(listOf(16.7, 33.3), result.frame.intendedVsyncIntervalsMs)
        assertEquals("Example", result.device.manufacturer)
        assertEquals(1_080, result.device.displayWidthPx)
        assertEquals(120.0, result.device.refreshRateHz!!, 0.0)
        assertNull(result.device.gpuModel)
        assertNull(result.device.renderer)
        assertEquals(122_000L, result.sourceTimestamps.cpuAtMs)
        assertNull(result.sourceTimestamps.cpuFrequencyAtMs) // Future observations are rejected.
        assertNull(result.sourceTimestamps.gpuAtMs) // No GPU reading was produced.
        assertEquals(121_000L, result.sourceTimestamps.memoryAtMs)
        assertNull(result.sourceTimestamps.thermalStatusAtMs) // Out-of-range status is not measured data.
        assertEquals(120_000L, result.sourceTimestamps.temperatureAtMs)
        assertEquals(121_000L, result.sourceTimestamps.batteryAtMs)
        assertEquals(118_000L, result.sourceTimestamps.networkLatencyAtMs)
        assertNull(result.sourceTimestamps.networkRetransmissionsAtMs)
        assertEquals(122_000L, result.sourceTimestamps.frameAtMs)
        assertEquals(123_000L, result.sourceTimestamps.deviceAtMs)
    }

    @Test fun `unavailable and inconsistent inputs are not converted into zero measurements`() {
        val result = SensorFusion.fuse(
            capturedAtMs = 0L,
            cpu = CpuSnapshot(utilizationPct = Double.NaN, meanPolicyFrequencyKHz = -1L),
            memory = MemorySnapshot(totalBytes = 1_000L, availableBytes = 2_000L, usedBytes = -1L),
            thermal = ThermalSnapshot(temperatureC = Double.NaN, slopeCPerMin = 2_000.0),
            battery = BatterySnapshot(levelPct = 101.0, currentMilliAmps = Double.NaN),
            network = NetworkSnapshot(latencyMs = -1.0),
            frame = FrameSnapshot(fps = null, targetFps = 0, frameTimesMs = emptyList())
        )

        assertEquals(0L, result.capturedAtMs)
        assertNull(result.cpu.utilizationPct)
        assertNull(result.cpu.meanPolicyFrequencyKHz)
        assertEquals(SensorAvailability.UNAVAILABLE, result.cpu.frequencyAvailability)
        assertNull(result.memory.usedBytes)
        assertNull(result.memory.availableBytes)
        assertNull(result.memory.usedPct)
        assertNull(result.thermal.temperatureC)
        assertNull(result.thermal.slopeCPerMin)
        assertNull(result.battery.levelPct)
        assertNull(result.battery.currentMilliAmps)
        assertNull(result.network.latencyMs)
        assertNull(result.frame.targetFps)
        assertNull(result.frame.frameTimesMs)
        assertTrue(result.device.model == null)
    }
}
