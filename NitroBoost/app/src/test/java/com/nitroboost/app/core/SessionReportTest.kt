package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.nitroboost.app.core.telemetry.FramePacingMetrics

class SessionReportTest {

    @Test
    fun `summarize computes averages peaks and delta`() {
        val rep = SessionReportBuilder.summarize(
            startedAt = 1_000L,
            endedAt = 61_000L,
            fpsSamples = listOf(50, 60, 70),
            tempSamples = listOf(35, 42, 38),
            pingSamples = listOf(24, 12, 30),
            ramMbSamples = listOf(2048, 2300, 2100),
            applied = 12,
            failed = 1,
            previousAvgFps = 55,
            gamePackage = "com.example.game"
        )
        assertEquals(60, rep.avgFps)
        assertEquals(50, rep.minFps)
        assertEquals(42, rep.peakTempC)
        assertEquals(12, rep.minPingMs)
        assertEquals(2300, rep.peakRamMb)
        assertEquals(60, rep.durationSec)
        assertEquals(5, rep.deltaFps)
        assertEquals(12, rep.applied)
        assertEquals(1, rep.failed)
        assertEquals(55, rep.previousAvgFps)
    }

    @Test
    fun `empty samples produce nulls and no delta`() {
        val rep = SessionReportBuilder.summarize(
            0L, 5_000L, emptyList(), emptyList(), emptyList(), emptyList(),
            applied = 0, failed = 0, previousAvgFps = null
        )
        assertNull(rep.avgFps)
        assertNull(rep.minFps)
        assertNull(rep.peakTempC)
        assertNull(rep.minPingMs)
        assertNull(rep.deltaFps)
        assertEquals(0, rep.peakRamMb)
        assertEquals(5, rep.durationSec)
    }

    @Test
    fun `same-game session comparison can be negative`() {
        val rep = SessionReportBuilder.summarize(
            0L, 10_000L,
            fpsSamples = listOf(30, 32),
            tempSamples = emptyList(),
            pingSamples = emptyList(),
            ramMbSamples = emptyList(),
            applied = 0, failed = 0,
            previousAvgFps = 60,
            gamePackage = "com.example.game"
        )
        assertEquals(-29, rep.deltaFps)
    }

    @Test
    fun `unscoped legacy FPS values do not produce a comparison`() {
        val rep = SessionReportBuilder.summarize(
            startedAt = 0L,
            endedAt = 10_000L,
            fpsSamples = listOf(50, 60),
            tempSamples = emptyList(),
            pingSamples = emptyList(),
            ramMbSamples = emptyList(),
            applied = 0,
            failed = 0,
            previousAvgFps = 55
        )

        assertNull(rep.previousAvgFps)
        assertNull(rep.deltaFps)
        assertNull(rep.gamePackage)
    }

    @Test
    fun `duration never negative on clock skew`() {
        val rep = SessionReportBuilder.summarize(
            100L, 50L, emptyList(), emptyList(), emptyList(), emptyList(),
            applied = 0, failed = 0, previousAvgFps = null
        )
        assertEquals(0, rep.durationSec)
    }

    @Test
    fun `session report keeps latest measured frame pacing`() {
        val metrics = FramePacingMetrics(
            targetFps = 60,
            averageFps = 58.0,
            medianFps = 59.0,
            meanFrameTimeMs = 17.0,
            medianFrameTimeMs = 16.7,
            p95FrameTimeMs = 25.0,
            p99FrameTimeMs = 34.0,
            frameTimeVarianceMs2 = 2.0,
            jankRate = 0.02,
            estimatedDroppedFrameRate = null,
            stabilityScore = 90.0,
            smoothnessScore = 88.0,
            fpsSampleCount = 20,
            frameSampleCount = 100,
            intendedVsyncIntervalCount = 99,
            onePercentLowFps = 42.5
        )
        val samples = SessionSampleSummary(1, 60, 60, null, null, 0, metrics)

        val report = SessionReportBuilder.summarize(0L, 1_000L, samples, 0, 0, null)

        assertEquals(42.5, report.framePacing?.onePercentLowFps!!, 0.001)
        assertEquals(25.0, report.framePacing?.p95FrameTimeMs!!, 0.001)
    }
}
