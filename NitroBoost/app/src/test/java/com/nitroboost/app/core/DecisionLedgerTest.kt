package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.TrialConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecisionLedgerTest {

    private fun file(): File = File.createTempFile("nitro_ledger", ".json").also { it.deleteOnExit() }

    private val cfg = TrialConfig(minPairs = 8, maxPairs = 40)

    @Test fun `round trip through disk keeps decisions and deltas`() {
        val f = file()
        val a = DecisionLedger(f, 40)
        val deltas = List(10) { 2.0 }
        a.record("walt_tuning", "WALT scheduler tuning", deltas,
            AdaptivePolicy.assess(deltas, cfg), 1_000L, cfg)
        a.save()

        val b = DecisionLedger(f, 40)
        b.load()
        val e = b.entries["walt_tuning"]
        assertTrue(e != null)
        assertEquals(Decision.KEEP, e!!.decision)
        assertEquals(10, e.pairs)
        assertEquals(2.0, e.meanDelta!!, 0.001)
        assertEquals(1, e.sessions)
        assertEquals(10, e.deltas.size)
    }

    @Test fun `new deltas accumulate across sessions`() {
        val f = file()
        val a = DecisionLedger(f, 40)
        a.record("cpu_floor", "CPU floor", List(8) { 1.0 },
            AdaptivePolicy.assess(List(8) { 1.0 }, cfg), 1_000L, cfg)

        val b = DecisionLedger(f, 40)
        b.load()
        val merged = b.record("cpu_floor", "CPU floor", List(8) { 1.0 },
            AdaptivePolicy.assess(List(16) { 1.0 }, cfg), 2_000L, cfg)
        assertEquals(16, merged.pairs)
        assertEquals(2, merged.sessions)
        assertEquals(Decision.KEEP, merged.decision)
    }

    @Test fun `deltas are bounded by max pairs`() {
        val a = DecisionLedger(file(), maxPairs = 40)
        a.record("x", "X", List(30) { 1.0 }, AdaptivePolicy.assess(List(30) { 1.0 }, cfg), 1L, cfg)
        val merged = a.record("x", "X", List(20) { 1.0 },
            AdaptivePolicy.assess(List(50) { 1.0 }, cfg), 2L, cfg)
        assertEquals(40, merged.pairs)
    }

    @Test fun `resolved and pending bookkeeping`() {
        val a = DecisionLedger(file(), 40)
        assertNull(a.decisionFor("missing"))
        assertFalse(a.isResolved("missing"))
        a.record("keepme", "K", List(10) { 5.0 },
            AdaptivePolicy.assess(List(10) { 5.0 }, cfg), 1L, cfg)
        a.record("pending", "P", List(3) { 1.0 },
            AdaptivePolicy.assess(List(3) { 1.0 }, cfg), 2L, cfg)
        assertTrue(a.isResolved("keepme"))
        assertFalse(a.isResolved("pending"))
        assertEquals(listOf("pending"), a.pendingIds())
    }

    @Test fun `corrupt file yields empty ledger, not a crash`() {
        val f = file()
        f.writeText("this is { not json")
        val a = DecisionLedger(f, 40)
        a.load()
        assertTrue(a.entries.isEmpty())
    }
}
