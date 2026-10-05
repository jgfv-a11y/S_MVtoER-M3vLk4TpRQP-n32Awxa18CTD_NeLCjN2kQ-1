package com.nitroboost.app.core

import com.nitroboost.app.core.performance.ConfidenceLevel
import com.nitroboost.app.core.performance.EvidenceDomain
import com.nitroboost.app.core.performance.PerformanceState
import com.nitroboost.app.core.performance.PerformanceStateEngine
import com.nitroboost.app.core.telemetry.BatterySnapshot
import com.nitroboost.app.core.telemetry.CpuSnapshot
import com.nitroboost.app.core.telemetry.DeviceSnapshot
import com.nitroboost.app.core.telemetry.FrameSnapshot
import com.nitroboost.app.core.telemetry.GpuSnapshot
import com.nitroboost.app.core.telemetry.MemoryPressure
import com.nitroboost.app.core.telemetry.MemorySnapshot
import com.nitroboost.app.core.telemetry.NetworkSnapshot
import com.nitroboost.app.core.telemetry.PerformanceSnapshot
import com.nitroboost.app.core.telemetry.SensorAvailability
import com.nitroboost.app.core.telemetry.ThermalSnapshot
import com.nitroboost.app.core.telemetry.TelemetrySourceTimestamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceStateEngineTest {

    @Test
    fun `missing input is unknown with explicit unavailable sources`() {
        val result = PerformanceStateEngine().analyze(null, timestampMs = 5_000L)

        assertEquals(PerformanceState.UNKNOWN, result.state)
        assertEquals(5_000L, result.timestampMs)
        assertEquals(ConfidenceLevel.LOW, result.confidence.level)
        assertTrue(result.dataQuality.warnings.contains("CURRENT_SNAPSHOT_MISSING"))
        assertTrue(result.dataQuality.warnings.contains("PACKET_LOSS_NOT_MEASURED"))
        assertNull(result.network.packetLossPct)
        assertTrue(result.dataQuality.score in 0.0..1.0)
    }

    @Test
    fun `sustained CPU pressure and measured frame deficit classify CPU bound`() {
        val engine = PerformanceStateEngine()
        val results = (1..5).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 94.0,
                    memoryPressure = MemoryPressure.NORMAL,
                    thermalStatus = 0,
                    fps = 30.0,
                    target = 60,
                    frameTimes = List(6) { 33.5 }
                ),
                gamePackage = "com.example.game",
                processEpoch = 1L
            )
        }
        val result = results.last()

        assertEquals(PerformanceState.CPU_BOUND, result.state)
        assertTrue(result.confidence.score > 0.55)
        assertTrue(result.evidence.any { it.code == "CPU_SYSTEM_UTILIZATION_P90" })
        assertTrue(result.evidence.any { it.code == "FPS_BELOW_CONFIGURED_TARGET" })
        assertTrue(result.evidence.filter { it.domain == EvidenceDomain.CPU }
            .all { it.scope.name == "SYSTEM_WIDE" })
    }

    @Test
    fun `GPU bound requires repeated direct GPU utilization and frame symptoms`() {
        val engine = PerformanceStateEngine()
        val result = (1..4).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 25.0,
                    gpu = 96.0,
                    fps = 35.0,
                    target = 60,
                    frameTimes = List(6) { 29.0 }
                ),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.GPU_BOUND, result.state)
        assertTrue(result.evidence.any { it.code == "GPU_UTILIZATION_MEDIAN" })
        assertTrue(result.evidence.any { it.domain == EvidenceDomain.FRAME_PACING && it.supportsClassification })
    }

    @Test
    fun `low FPS without GPU telemetry is not mislabeled GPU bound`() {
        val engine = PerformanceStateEngine()
        val result = (1..4).map { index ->
            engine.analyze(
                snapshot(at = index * 1_000L, cpu = 20.0, fps = 30.0, target = 60),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.UNKNOWN, result.state)
        assertNull(result.pressures.gpu)
        assertTrue(result.dataQuality.warnings.contains("GPU_UTILIZATION_UNAVAILABLE"))
        assertFalse(result.evidence.any { it.code == "GPU_UTILIZATION_MEDIAN" })
    }

    @Test
    fun `Android low memory signal supports memory bound classification`() {
        val engine = PerformanceStateEngine()
        val result = (1..3).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 35.0,
                    memoryPressure = MemoryPressure.LOW,
                    usedBytes = 9_600L,
                    totalBytes = 10_000L,
                    availableBytes = 400L,
                    thresholdBytes = 500L,
                    thermalStatus = 0
                ),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.MEMORY_BOUND, result.state)
        assertTrue(result.evidence.any { it.code == "ANDROID_LOW_MEMORY_FLAG" })
        assertTrue(result.evidence.any { it.scope.name == "SYSTEM_WIDE" && it.domain == EvidenceDomain.MEMORY })
    }

    @Test
    fun `moderate OS thermal status is not hidden below the mixed-state threshold`() {
        val result = PerformanceStateEngine().analyze(
            snapshot(at = 5_000L, thermalStatus = 2),
            gamePackage = "com.example.game"
        )

        assertEquals(PerformanceState.THERMAL_BOUND, result.state)
        assertTrue(result.evidence.any { it.code == "EFFECTIVE_THERMAL_STATUS" })
    }

    @Test
    fun `early thermal trend warning is not labeled healthy`() {
        val result = PerformanceStateEngine().analyze(
            snapshot(at = 5_000L, thermalStatus = 0, temperature = 38.0, thermalSlope = 1.3),
            gamePackage = "com.example.game"
        )

        assertEquals(PerformanceState.THERMAL_BOUND, result.state)
        assertTrue(result.evidence.any {
            it.code == "THERMAL_SLOPE_C_PER_MIN" && it.supportsClassification
        })
    }

    @Test
    fun `cached temperature is counted once and keeps its actual source timestamp`() {
        val engine = PerformanceStateEngine()
        engine.analyze(snapshot(at = 1_000L, thermalStatus = 0, temperature = 47.0),
            gamePackage = "com.example.game")
        val result = engine.analyze(
            snapshot(at = 2_000L, thermalStatus = 0, temperature = 47.0, temperatureAt = 1_000L),
            gamePackage = "com.example.game"
        )
        val temperatureEvidence = result.evidence.first { it.code == "HIGHEST_READABLE_THERMAL_ZONE_C" }

        assertEquals(1, temperatureEvidence.sampleCount)
        assertEquals(1_000L, temperatureEvidence.sourceTimestampMs)
        assertEquals(PerformanceState.THERMAL_BOUND, result.state)
    }

    @Test
    fun `thermal reading is classified and combines with CPU as mixed`() {
        val engine = PerformanceStateEngine()
        val result = (1..4).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 94.0,
                    thermalStatus = 0,
                    temperature = 47.0,
                    fps = 30.0,
                    target = 60,
                    frameTimes = List(6) { 34.0 }
                ),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.MIXED_BOUND, result.state)
        assertTrue(result.evidence.any { it.code == "HIGHEST_READABLE_THERMAL_ZONE_C" })
        assertTrue(result.evidence.any { it.code == "CPU_SYSTEM_UTILIZATION_P90" })
    }

    @Test
    fun `network probe variability is measured without inventing packet loss`() {
        val engine = PerformanceStateEngine()
        val latencies = listOf(110.0, 230.0, 125.0, 245.0)
        val result = latencies.mapIndexed { index, latency ->
            val at = (index + 1L) * 10_000L
            engine.analyze(
                snapshot(at = at, latency = latency, retransmissions = 1.0),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.NETWORK_BOUND, result.state)
        assertNotNull(result.network.tcpProbeVariabilityMs)
        assertTrue(result.network.tcpProbeVariabilityMs!! > 30.0)
        assertEquals(4, result.network.tcpProbeSampleCount)
        assertNull(result.network.packetLossPct)
        assertTrue(result.dataQuality.warnings.contains("PACKET_LOSS_NOT_MEASURED"))
        assertTrue(result.dataQuality.warnings.contains("TCP_PROBE_IS_NOT_THE_GAME_NETWORK_PATH"))
        assertTrue(result.evidence.filter { it.domain == EvidenceDomain.NETWORK }
            .all { it.scope.name == "TCP_PROBE" || it.scope.name == "SYSTEM_NETWORK" })
    }

    @Test
    fun `display bound requires a target above refresh and measured cadence at refresh`() {
        val engine = PerformanceStateEngine()
        val result = (1..4).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 30.0,
                    thermalStatus = 0,
                    fps = 60.0,
                    target = 120,
                    refresh = 60.0,
                    frameTimes = List(6) { 16.7 },
                    vsyncIntervals = List(6) { 16.7 }
                ),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.DISPLAY_BOUND, result.state)
        assertTrue(result.evidence.any { it.code == "CONFIGURED_TARGET_ABOVE_ACTIVE_REFRESH" })
        assertTrue(result.evidence.any { it.code == "MEASURED_FRAME_CADENCE_NEAR_ACTIVE_REFRESH" })
    }

    @Test
    fun `measured target performance can be healthy while GPU telemetry remains unavailable`() {
        val engine = PerformanceStateEngine()
        val result = (1..5).map { index ->
            engine.analyze(
                snapshot(
                    at = index * 1_000L,
                    cpu = 35.0,
                    memoryPressure = MemoryPressure.NORMAL,
                    usedBytes = 7_000L,
                    totalBytes = 10_000L,
                    availableBytes = 3_000L,
                    thresholdBytes = 500L,
                    thermalStatus = 0,
                    temperature = 35.0,
                    fps = 60.0,
                    target = 60,
                    refresh = 120.0,
                    frameTimes = List(6) { 16.6 },
                    vsyncIntervals = List(6) { 16.6 }
                ),
                gamePackage = "com.example.game"
            )
        }.last()

        assertEquals(PerformanceState.HEALTHY, result.state)
        assertEquals(ConfidenceLevel.HIGH, result.confidence.level)
        assertTrue(result.dataQuality.warnings.contains("GPU_UTILIZATION_UNAVAILABLE"))
        assertTrue(result.evidence.any { it.code == "MEASURED_FPS_AT_CONFIGURED_TARGET" })
    }

    @Test
    fun `stale CPU data does not support a current classification`() {
        val stale = snapshot(
            at = 10_000L,
            cpu = 99.0,
            thermalStatus = 0,
            fps = 20.0,
            target = 60,
            cpuAt = 1_000L,
            frameAt = 10_000L,
            frameTimes = List(8) { 50.0 }
        )
        val result = PerformanceStateEngine().analyze(stale, gamePackage = "com.example.game")

        assertEquals(PerformanceState.UNKNOWN, result.state)
        assertFalse(result.dataQuality.sources.first { it.source.name == "CPU" }.fresh)
        assertEquals("CPU_STALE", result.dataQuality.sources.first { it.source.name == "CPU" }.reason)
    }

    @Test
    fun `process epoch reset prevents network history leaking across game processes`() {
        val engine = PerformanceStateEngine()
        listOf(100.0, 220.0, 130.0).forEachIndexed { index, latency ->
            val at = (index + 1L) * 10_000L
            engine.analyze(snapshot(at = at, latency = latency), gamePackage = "com.example.game", processEpoch = 1L)
        }
        val newProcess = engine.analyze(
            snapshot(at = 40_000L, latency = 110.0),
            gamePackage = "com.example.game",
            processEpoch = 2L
        )

        assertEquals(1, newProcess.network.tcpProbeSampleCount)
        assertNull(newProcess.network.tcpProbeVariabilityMs)
        assertEquals(PerformanceState.UNKNOWN, newProcess.state)
    }

    private fun snapshot(
        at: Long,
        cpu: Double? = null,
        gpu: Double? = null,
        memoryPressure: MemoryPressure? = null,
        usedBytes: Long? = null,
        totalBytes: Long? = null,
        availableBytes: Long? = null,
        thresholdBytes: Long? = null,
        thermalStatus: Int? = null,
        temperature: Double? = null,
        temperatureAt: Long? = at,
        thermalSlope: Double? = null,
        fps: Double? = null,
        target: Int? = null,
        frameTimes: List<Double>? = null,
        vsyncIntervals: List<Double>? = null,
        latency: Double? = null,
        retransmissions: Double? = null,
        refresh: Double? = null,
        cpuAt: Long? = at,
        frameAt: Long? = at
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMs = at,
        cpu = CpuSnapshot(
            utilizationPct = cpu,
            perCoreUtilizationPct = cpu?.let { listOf(it) }
        ),
        gpu = GpuSnapshot(
            utilizationPct = gpu,
            utilizationAvailability = if (gpu == null) SensorAvailability.UNAVAILABLE
            else SensorAvailability.AVAILABLE
        ),
        memory = MemorySnapshot(
            usedBytes = usedBytes,
            availableBytes = availableBytes,
            totalBytes = totalBytes,
            thresholdBytes = thresholdBytes,
            pressure = memoryPressure
        ),
        thermal = ThermalSnapshot(
            osStatus = thermalStatus,
            temperatureC = temperature,
            slopeCPerMin = thermalSlope,
            effectiveStatus = thermalStatus
        ),
        battery = BatterySnapshot(),
        network = NetworkSnapshot(
            latencyMs = latency,
            retransmissionsPerSec = retransmissions
        ),
        frame = FrameSnapshot(
            fps = fps,
            targetFps = target,
            frameTimesMs = frameTimes,
            intendedVsyncIntervalsMs = vsyncIntervals
        ),
        device = DeviceSnapshot(refreshRateHz = refresh),
        sourceTimestamps = TelemetrySourceTimestamps(
            cpuAtMs = cpuAt,
            gpuAtMs = at.takeIf { gpu != null },
            memoryAtMs = at.takeIf {
                memoryPressure != null || usedBytes != null || availableBytes != null || totalBytes != null
            },
            thermalStatusAtMs = at.takeIf { thermalStatus != null },
            temperatureAtMs = temperatureAt.takeIf { temperature != null },
            networkLatencyAtMs = at.takeIf { latency != null },
            networkRetransmissionsAtMs = at.takeIf { retransmissions != null },
            frameAtMs = frameAt.takeIf { fps != null || frameTimes?.isNotEmpty() == true ||
                vsyncIntervals?.isNotEmpty() == true },
            deviceAtMs = at.takeIf { refresh != null }
        )
    )
}
