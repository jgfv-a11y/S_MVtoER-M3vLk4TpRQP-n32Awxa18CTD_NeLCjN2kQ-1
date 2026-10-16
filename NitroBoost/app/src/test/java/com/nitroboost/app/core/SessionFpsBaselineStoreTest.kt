package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFpsBaselineStoreTest {

    @Test
    fun `baselines are isolated by game package`() {
        val oneGame = SessionFpsBaselineStore.record(
            serialized = null,
            gamePackage = "com.example.alpha",
            averageFps = 60,
            endedAtMs = 1_000L
        )

        assertEquals(60, SessionFpsBaselineStore.previousAverageFps(oneGame, "com.example.alpha"))
        assertNull(SessionFpsBaselineStore.previousAverageFps(oneGame, "com.example.beta"))
    }

    @Test
    fun `record updates the same package and keeps the newest value`() {
        val first = SessionFpsBaselineStore.record(null, "com.example.alpha", 60, 1_000L)
        val second = SessionFpsBaselineStore.record(first, "com.example.alpha", 72, 2_000L)
        val parsed = org.json.JSONArray(requireNotNull(second))

        assertEquals(1, parsed.length())
        assertEquals(72, SessionFpsBaselineStore.previousAverageFps(second, "com.example.alpha"))
    }

    @Test
    fun `legacy malformed and invalid entries are ignored`() {
        assertNull(SessionFpsBaselineStore.previousAverageFps("{bad", "com.example.alpha"))
        assertNull(SessionFpsBaselineStore.previousAverageFps("[]", "not a package"))
        assertNull(SessionFpsBaselineStore.record(null, "not a package", 60, 1_000L))
        assertNull(SessionFpsBaselineStore.record(null, "com.example.alpha", 0, 1_000L))
        assertNull(SessionFpsBaselineStore.record(null, "com.example.alpha", 60, -1L))
    }

    @Test
    fun `baseline cache is bounded and evicts oldest packages`() {
        var serialized: String? = null
        repeat(SessionFpsBaselineStore.MAX_BASELINES) { index ->
            serialized = SessionFpsBaselineStore.record(
                serialized,
                "com.example.game$index",
                30 + index,
                index.toLong() + 1L
            )
        }
        serialized = SessionFpsBaselineStore.record(
            serialized,
            "com.example.newest",
            90,
            10_000L
        )
        val parsed = org.json.JSONArray(requireNotNull(serialized))

        assertEquals(SessionFpsBaselineStore.MAX_BASELINES, parsed.length())
        assertNull(SessionFpsBaselineStore.previousAverageFps(serialized, "com.example.game0"))
        assertEquals(90, SessionFpsBaselineStore.previousAverageFps(serialized, "com.example.newest"))
    }
}
