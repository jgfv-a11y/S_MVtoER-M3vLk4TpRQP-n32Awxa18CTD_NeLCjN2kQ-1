package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementProvenanceTest {
    @Test
    fun `fresh source is measured and reports age`() {
        val result = MeasurementProvenanceEvaluator.evaluate(
            available = true,
            source = "gfxinfo",
            observedAtMs = 9_000L,
            nowMs = 10_000L,
            maxAgeMs = 5_000L
        )
        assertEquals(MeasurementState.MEASURED, result.state)
        assertEquals(1_000L, result.ageMs)
    }

    @Test
    fun `old source is stale rather than live`() {
        val result = MeasurementProvenanceEvaluator.evaluate(
            available = true,
            source = "thermal zones",
            observedAtMs = 1_000L,
            nowMs = 10_000L
        )
        assertEquals(MeasurementState.STALE, result.state)
    }

    @Test
    fun `missing source is unavailable`() {
        val result = MeasurementProvenanceEvaluator.evaluate(
            available = false,
            source = "none",
            observedAtMs = null,
            nowMs = 10_000L
        )
        assertEquals(MeasurementState.UNAVAILABLE, result.state)
    }
}
