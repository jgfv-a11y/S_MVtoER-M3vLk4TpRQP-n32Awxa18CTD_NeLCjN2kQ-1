package com.nitroboost.app.core

import com.nitroboost.app.core.telemetry.FramePacingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingDisplayTest {
    @Test fun `missing metrics remain an honest empty state`() {
        val display = FramePacingDisplay.from(null)

        assertFalse(display.hasMeasuredData)
        assertEquals(0, display.sampleCount)
    }

    @Test fun `display exposes only measured pacing values`() {
        val display = FramePacingDisplay.from(FramePacingMetrics(
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
        ))

        assertTrue(display.hasMeasuredData)
        assertEquals(42.5, display.onePercentLowFps!!, 0.001)
        assertEquals(25.0, display.p95FrameTimeMs!!, 0.001)
        assertEquals(34.0, display.p99FrameTimeMs!!, 0.001)
        assertEquals(20, display.sampleCount)
    }
}
***
