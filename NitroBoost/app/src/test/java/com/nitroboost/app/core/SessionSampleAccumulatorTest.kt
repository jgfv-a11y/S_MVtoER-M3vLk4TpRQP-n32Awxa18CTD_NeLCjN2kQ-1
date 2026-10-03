package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSampleAccumulatorTest {
    @Test
    fun `streaming summary matches list-based session report`() {
        val samples = SessionSampleAccumulator()
        samples.add(50, 35, 24, 2_048)
        samples.add(60, 42, 12, 2_300)
        samples.add(70, 38, 30, 2_100)
        val summary = samples.snapshot()
        val report = SessionReportBuilder.summarize(
            1_000L, 61_000L, summary, applied = 12, failed = 1, previousAvgFps = 55
        )
        assertEquals(60, report.avgFps)
        assertEquals(50, report.minFps)
        assertEquals(42, report.peakTempC)
        assertEquals(12, report.minPingMs)
        assertEquals(2_300, report.peakRamMb)
        assertEquals(5, report.deltaFps)
    }

    @Test
    fun `empty and missing measurements stay absent`() {
        val samples = SessionSampleAccumulator()
        samples.add(null, null, null, null)
        val summary = samples.snapshot()
        assertNull(summary.avgFps)
        assertNull(summary.minFps)
        assertNull(summary.peakTempC)
        assertNull(summary.minPingMs)
        assertEquals(0, summary.peakRamMb)
    }

    @Test
    fun `clear releases session statistics`() {
        val samples = SessionSampleAccumulator()
        repeat(100_000) { samples.add(60, 40, 20, 1_024) }
        assertEquals(100_000L, samples.snapshot().fpsCount)
        samples.clear()
        assertEquals(0L, samples.snapshot().fpsCount)
        assertNull(samples.snapshot().avgFps)
    }
}
