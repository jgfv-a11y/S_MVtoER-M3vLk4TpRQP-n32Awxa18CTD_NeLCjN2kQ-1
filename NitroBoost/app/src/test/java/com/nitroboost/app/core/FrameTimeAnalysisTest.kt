package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.FrameTimeAnalysis
import com.nitroboost.app.core.adaptive.GfxFrameStatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimeAnalysisTest {

    @Test fun `gfxinfo parser reads source timestamps as milliseconds`() {
        val output = """
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,FrameCompleted
            0,1000000000,1000000000,1010000000
            0,1020000000,1020000000,1035000000
            ---PROFILEDATA---
        """.trimIndent()
        val parsed = GfxFrameStatsParser.parse(output)
        assertEquals(listOf(10.0, 15.0), parsed.frameTimesMs)
        assertEquals(listOf(1_000_000_000L, 1_020_000_000L), parsed.intendedVsyncNs)
        assertEquals(1_035_000_000L, parsed.lastCompletedNs)
        assertEquals(2, parsed.rowsSeen)
    }

    @Test fun `parser only returns frames newer than the prior observation`() {
        val output = """
            Flags,IntendedVsync,Vsync,FrameCompleted
            0,1000000000,1000000000,1010000000
            0,1020000000,1020000000,1035000000
        """.trimIndent()
        val parsed = GfxFrameStatsParser.parse(output, afterCompletedNs = 1_010_000_000L)
        assertEquals(listOf(15.0), parsed.frameTimesMs)
        assertEquals(listOf(1_020_000_000L), parsed.intendedVsyncNs)
    }

    @Test fun `frame analysis reports percentiles variance and actual hitches`() {
        val result = FrameTimeAnalysis.summarize(listOf(10.0, 16.7, 34.0, 50.0), targetFps = 60)!!
        assertEquals(4, result.frameCount)
        assertEquals(25.35, result.medianMs, 0.01)
        assertEquals(50.0, result.p95Ms, 0.001)
        assertEquals(50.0, result.p99Ms, 0.001)
        assertEquals(2, result.hitchCount)
        assertEquals(0.5, result.hitchRate, 0.001)
        assertTrue(result.varianceMs2 > 0.0)
    }

    @Test fun `unavailable or invalid frame times are not fabricated`() {
        assertNull(FrameTimeAnalysis.summarize(emptyList(), 60))
        assertNull(FrameTimeAnalysis.summarize(listOf(Double.NaN, -1.0), 60))
        assertNull(FrameTimeAnalysis.summarize(listOf(16.0), 0))
    }
}
