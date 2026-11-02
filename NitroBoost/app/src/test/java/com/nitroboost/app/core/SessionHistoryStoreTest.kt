package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryStoreTest {

    private fun recordOne(
        serialized: String?,
        pkg: String = "com.example.game",
        endedAt: Long,
        avgFps: Int? = 60,
        durationSec: Int = 120,
        minFps: Int? = 30,
        peakTempC: Int? = 40,
        applied: Int = 10,
        failed: Int = 0
    ): String? = SessionHistoryStore.record(
        serialized, pkg, endedAt, durationSec, avgFps, minFps, peakTempC, applied, failed
    )

    // ---------- record / historyFor ----------

    @Test
    fun `record then historyFor round-trips fields most recent first`() {
        var s: String? = null
        s = recordOne(s, endedAt = 1_000L, avgFps = 50)
        s = recordOne(s, endedAt = 3_000L, avgFps = 70)
        s = recordOne(s, endedAt = 2_000L, avgFps = 60)
        val h = SessionHistoryStore.historyFor(s, "com.example.game")
        assertEquals(3, h.size)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), h.map { it.endedAt })
        assertEquals(70, h[0].avgFps)
        assertEquals(30, h[0].minFps)
        assertEquals(40, h[0].peakTempC)
        assertEquals(120, h[0].durationSec)
        assertEquals(10, h[0].applied)
        assertEquals("com.example.game", h[0].gamePackage)
    }

    @Test
    fun `invalid package name is rejected and previous value must be kept`() {
        val s = recordOne(null, endedAt = 1L)
        assertNotNull(s)
        assertNull(recordOne(s, pkg = "not a package; rm -rf", endedAt = 2L))
        assertNull(recordOne(s, pkg = "", endedAt = 2L))
        assertNull(SessionHistoryStore.record(s, "com.example.game", -5L, 10, 60, null, null, 1, 0))
        assertNull(SessionHistoryStore.record(s, "com.example.game", 5L, -1, 60, null, null, 1, 0))
        assertNull(SessionHistoryStore.record(s, "com.example.game", 5L, 10, 60, null, null, -1, 0))
    }

    @Test
    fun `out-of-range fps is stored as null not zero`() {
        var s = recordOne(null, endedAt = 1L, avgFps = 0)
        assertNotNull(s)
        val h = SessionHistoryStore.historyFor(s, "com.example.game")
        assertEquals(1, h.size)
        assertNull(h[0].avgFps)
        s = recordOne(s, endedAt = 2L, avgFps = 5_000)
        assertNull(SessionHistoryStore.historyFor(s, "com.example.game")[0].avgFps)
    }

    @Test
    fun `history is scoped per game`() {
        var s: String? = null
        s = recordOne(s, pkg = "com.a", endedAt = 1L, avgFps = 60)
        s = recordOne(s, pkg = "com.b", endedAt = 2L, avgFps = 90)
        assertEquals(1, SessionHistoryStore.historyFor(s, "com.a").size)
        assertEquals(90, SessionHistoryStore.historyFor(s, "com.b")[0].avgFps)
        assertTrue(SessionHistoryStore.historyFor(s, "com.c").isEmpty())
        assertTrue(SessionHistoryStore.historyFor(s, "bad package!").isEmpty())
        assertTrue(SessionHistoryStore.historyFor(null, "com.a").isEmpty())
    }

    @Test
    fun `per-game history is capped keeping most recent`() {
        var s: String? = null
        for (i in 1..12) s = recordOne(s, endedAt = i * 1_000L, avgFps = 50 + i)
        val h = SessionHistoryStore.historyFor(s, "com.example.game")
        assertEquals(SessionHistoryStore.MAX_ENTRIES_PER_GAME, h.size)
        assertEquals(12_000L, h.first().endedAt)
        assertEquals(3_000L, h.last().endedAt) // entries 1..2 evicted
    }

    @Test
    fun `global game cap keeps most recently active games`() {
        var s: String? = null
        for (i in 0 until SessionHistoryStore.MAX_GAMES + 1) {
            s = recordOne(s, pkg = "com.game.$i", endedAt = (i + 1) * 1_000L)
        }
        val all = SessionHistoryStore.parse(s)
        assertEquals(SessionHistoryStore.MAX_GAMES, all.map { it.gamePackage }.distinct().size)
        // the oldest game (com.game.0, endedAt=1000) is evicted
        assertTrue(all.none { it.gamePackage == "com.game.0" })
        assertTrue(all.any { it.gamePackage == "com.game.${SessionHistoryStore.MAX_GAMES}" })
    }

    // ---------- parse defensive ----------

    @Test
    fun `corrupted stored json parses to empty list`() {
        assertTrue(SessionHistoryStore.parse("not json").isEmpty())
        assertTrue(SessionHistoryStore.parse("").isEmpty())
        assertTrue(SessionHistoryStore.parse(null).isEmpty())
        assertTrue(SessionHistoryStore.parse("{}").isEmpty())
        assertTrue(SessionHistoryStore.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun `malformed entries are skipped individually`() {
        var s = recordOne(null, endedAt = 1L, avgFps = 55)
        // inject garbage elements manually
        val arr = org.json.JSONArray(s)
        arr.put(org.json.JSONObject().put("gamePackage", "bad pkg"))
        arr.put(org.json.JSONObject().put("gamePackage", "com.ok").put("endedAt", -1))
        arr.put("string-element")
        val parsed = SessionHistoryStore.parse(arr.toString())
        assertEquals(1, parsed.size)
        assertEquals("com.example.game", parsed[0].gamePackage)
    }

    @Test
    fun `null fields survive a json round-trip as null`() {
        var s = recordOne(null, endedAt = 7L, avgFps = null, minFps = null, peakTempC = null)
        val h = SessionHistoryStore.historyFor(s, "com.example.game")
        assertEquals(1, h.size)
        assertNull(h[0].avgFps)
        assertNull(h[0].minFps)
        assertNull(h[0].peakTempC)
    }

    // ---------- trend ----------

    // fpsNewestFirst: element 0 gets the LARGEST endedAt (most recent session).
    private fun entries(pkg: String, fpsNewestFirst: List<Int?>): List<SessionHistoryEntry> =
        fpsNewestFirst.mapIndexed { i, fps ->
            SessionHistoryEntry(pkg, endedAt = (fpsNewestFirst.size - i) * 1_000L, durationSec = 60,
                avgFps = fps, minFps = null, peakTempC = null, applied = 5, failed = 0)
        }

    @Test
    fun `trend is null below the minimum sample count`() {
        assertNull(SessionHistoryStore.trendFor(entries("com.g", listOf(50, 60, 70)))) // 3 samples
        assertNull(SessionHistoryStore.trendFor(entries("com.g", listOf(50, 60, null, 70)))) // 3 valid
        assertNull(SessionHistoryStore.trendFor(emptyList()))
    }

    @Test
    fun `trend compares recent window with older window`() {
        // newest → oldest: 70, 60, 50, 40 → recent (70,60) avg 65, older (50,40) avg 45, delta +20
        val t = SessionHistoryStore.trendFor(entries("com.g", listOf(70, 60, 50, 40)))
        assertNotNull(t)
        assertEquals(65, t!!.recentAvgFps)
        assertEquals(45, t.olderAvgFps)
        assertEquals(20, t.deltaFps)
        assertEquals(4, t.sampleCount)
    }

    @Test
    fun `trend ignores entries without fps and caps the windows`() {
        // newest → oldest with fps: 100, 90, 80, 70, 999 (999 falls outside both windows), plus a null
        val t = SessionHistoryStore.trendFor(
            entries("com.g", listOf(100, 90, 80, 70, 999, null))
        )
        assertNotNull(t)
        assertEquals(95, t!!.recentAvgFps) // (100+90)/2
        assertEquals(75, t.olderAvgFps)    // (80+70)/2, 999 not included
        assertEquals(20, t.deltaFps)
        assertEquals(5, t.sampleCount)
    }

    @Test
    fun `negative trend is reported as negative delta`() {
        // newest → oldest: 40, 50, 70, 80 → recent (40,50) avg 45, older (70,80) avg 75, delta -30
        val t = SessionHistoryStore.trendFor(entries("com.g", listOf(40, 50, 70, 80)))
        assertNotNull(t)
        assertEquals(45, t!!.recentAvgFps)
        assertEquals(75, t.olderAvgFps)
        assertEquals(-30, t.deltaFps)
    }

    @Test
    fun `trend accepts entries in any order`() {
        val shuffled = entries("com.g", listOf(70, 60, 50, 40)).shuffled()
        val t = SessionHistoryStore.trendFor(shuffled)
        assertNotNull(t)
        assertEquals(20, t!!.deltaFps)
    }
}
