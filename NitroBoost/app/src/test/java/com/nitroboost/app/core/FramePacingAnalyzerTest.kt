package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.FramePacingAnalyzer
import com.nitroboost.app.core.telemetry.FrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingAnalyzerTest {

    @Test fun `pacing summary uses measured FPS frame durations and vsync gaps`() {
        val result = FramePacingAnalyzer.analyze(
            samples = listOf(
                FrameSnapshot(
                    fps = 60.0,
                    targetFps = 60,
                    frameTimesMs = listOf(16.0, 16.5, 16.7)
                ),
                FrameSnapshot(
                    fps = 54.0,
                    targetFps = 60,
                    frameTimesMs = listOf(16.8, 17.0, 40.0),
                    intendedVsyncIntervalsMs = listOf(1_000.0 / 60.0, 1_000.0 / 30.0)
                ),
                FrameSnapshot(fps = 60.0, targetFps = 60)
            )
        )!!

        assertEquals(58.0, result.averageFps!!, 0.001)
        assertEquals(60.0, result.medianFps!!, 0.001)
        assertEquals(6, result.frameSampleCount)
        assertEquals(40.0, result.p95FrameTimeMs!!, 0.001)
        assertEquals(40.0, result.p99FrameTimeMs!!, 0.001)
        assertEquals(1.0 / 6.0, result.jankRate!!, 0.001)
        assertEquals(1.0 / 3.0, result.estimatedDroppedFrameRate!!, 0.001)
        assertTrue(result.frameTimeVarianceMs2!! > 0.0)
        assertTrue(result.stabilityScore!! in 0.0..100.0)
        assertTrue(result.smoothnessScore!! in 0.0..100.0)
    }

    @Test fun `FPS is never calculated from frame times and missing gaps stay null`() {
        val result = FramePacingAnalyzer.analyze(
            listOf(FrameSnapshot(targetFps = 60, frameTimesMs = List(8) { 16.6 }))
        )!!

        assertNull(result.averageFps)
        assertNull(result.medianFps)
        assertEquals(16.6, result.meanFrameTimeMs!!, 0.001)
        assertNull(result.estimatedDroppedFrameRate)
        assertEquals(100.0, result.stabilityScore!!, 0.001)
        assertEquals(100.0, result.smoothnessScore!!, 0.001)
    }

    @Test fun `frame distribution remains useful without an FPS target but target scores stay null`() {
        val result = FramePacingAnalyzer.analyze(
            listOf(FrameSnapshot(frameTimesMs = listOf(10.0, 12.0, 14.0, 16.0, 18.0))),
            targetFps = null
        )!!

        assertNull(result.targetFps)
        assertEquals(14.0, result.medianFrameTimeMs!!, 0.001)
        assertEquals(18.0, result.p95FrameTimeMs!!, 0.001)
        assertNull(result.jankRate)
        assertNull(result.smoothnessScore)
        assertTrue(result.stabilityScore!! < 100.0)
    }

    @Test fun `composite scores are withheld for fewer than five measured frames`() {
        val result = FramePacingAnalyzer.analyze(
            listOf(FrameSnapshot(fps = 60.0, targetFps = 60, frameTimesMs = listOf(16.0, 17.0)))
        )!!

        assertNull(result.stabilityScore)
        assertNull(result.smoothnessScore)
        assertEquals(2, result.frameSampleCount)
    }

    @Test fun `one percent low uses only the slowest measured FPS samples`() {
        val result = FramePacingAnalyzer.analyze(
            List(100) { index -> FrameSnapshot(fps = (index + 1).toDouble()) }
        )!!

        assertEquals(1.0, result.onePercentLowFps!!, 0.001)
    }

    @Test fun `no measured FPS frame time or vsync input means no analysis`() {
        assertNull(FramePacingAnalyzer.analyze(emptyList(), 60))
        assertNull(FramePacingAnalyzer.analyze(listOf(FrameSnapshot(fps = 0.0)), 60))
        assertNull(
            FramePacingAnalyzer.analyze(
                listOf(FrameSnapshot(frameTimesMs = listOf(Double.NaN, -1.0))),
                60
            )
        )
    }
}
