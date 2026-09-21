package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {

    @Test
    fun `parse aggregate cpu line`() {
        val s = CpuMath.parseLine("cpu  1000 200 300 5000 700 10 20 0 0 0")
        assertEquals(1000 + 200 + 300 + 5000 + 700 + 10 + 20, s!!.total)
        assertEquals(5000 + 700, s.idle)
    }

    @Test
    fun `parse per-core line`() {
        val s = CpuMath.parseLine("cpu2 10 5 15 90 1 0 0 0 0 0")
        assertEquals(121, s!!.total)
        assertEquals(91, s.idle)
    }

    @Test
    fun `non cpu lines are rejected`() {
        assertNull(CpuMath.parseLine("intr 12345"))
        assertNull(CpuMath.parseLine(""))
    }

    @Test
    fun `usage percent math`() {
        val prev = CpuMath.Sample(1000, 500)
        val now = CpuMath.Sample(2000, 600)
        // delta total 1000, delta idle 100 -> 90%
        assertEquals(90, CpuMath.usagePercent(prev, now))
        assertEquals(0, CpuMath.usagePercent(now, prev))
    }

    @Test
    fun `snmp retransmits parsed from two-line block`() {
        val lines = listOf(
            "",
            "Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts InCsumErrors",
            "Tcp: 1 200 120000 -1 12 34 5 6 7 800 900 42 0 3 0"
        )
        assertEquals(42, SnmpMath.retransmits(lines))
    }

    @Test
    fun `snmp without header returns null`() {
        assertNull(SnmpMath.retransmits(listOf("Tcp: 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15")))
    }
}
