package com.nitroboost.app.core

import com.nitroboost.app.platform.CpuSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for /proc/stat parsing:
 *  - guest/guest_nice must not be double-counted into the busy total (the
 *    kernel already includes them in user/nice);
 *  - per-core "cpuN" rows must be parsed from EVERY line, not only the first
 *    line of the file. Two historical bugs (missing multiline anchoring and a
 *    captured-group count that never satisfied the field check) kept per-core
 *    utilisation empty forever; this test feeds a realistic /proc/stat
 *    through an injected file so it is deterministic.
 */
class ProcStatParsingTest {

    private fun statFile(dir: File, name: String, content: () -> String): File {
        val f = File(dir, name)
        f.writeText(content())
        return f
    }

    @Test
    fun `guest time is excluded from the busy total`() {
        // user=100 nice=200 system=300 idle=5000 iowait=700 irq=10 softirq=20
        // steal=0 guest=9000 guest_nice=9000 (already inside user/nice)
        val s = CpuMath.parseLine("cpu  100 200 300 5000 700 10 20 0 9000 9000")
        assertEquals(100 + 200 + 300 + 5000L + 700 + 10 + 20, s!!.total)
        assertEquals(5000 + 700, s.idle)
    }

    @Test
    fun `per-core utilization is parsed from later lines of proc stat`() {
        val dir = createTempDir("procstat")
        try {
            val f = statFile(dir, "stat") {
                """
                cpu  1000 200 300 5000 700 10 20 0 0 0
                cpu0 100 20 30 500 70 1 2 0 0 0
                cpu1 200 40 60 1000 140 2 4 0 0 0
                intr 12345
                """.trimIndent() + "\n"
            }
            val sampler = CpuSampler(f)
            sampler.sample() // prime the per-core previous-sample map

            // Busy time advances on every core; idle stays put.
            f.writeText(
                """
                cpu  2000 300 600 5000 700 30 40 0 0 0
                cpu0 200 40 60 500 70 3 4 0 0 0
                cpu1 400 80 120 1000 140 6 8 0 0 0
                intr 22345
                """.trimIndent() + "\n"
            )
            val reading = sampler.sample()
            assertNotNull(reading.perCoreUtilizationPct)
            val cores = reading.perCoreUtilizationPct!!
            assertEquals(2, cores.size)
            // cpu0: busy delta = (200+40+60+3+4) - (100+20+30+1+2) = 154,
            // idle delta = 0 → 100% busy. Values stay inside 0..100.
            assertTrue(cores.all { it in 0..100 })
            assertEquals(100, cores[0])
            assertEquals(100, cores[1])
            // Aggregate: busy delta = (2000+300+600+30+40)-(1000+200+300+10+20)
            // = 1740, idle delta = 0 → 100%.
            assertEquals(100, reading.utilizationPct)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unreadable stat file yields unavailable readings`() {
        val missing = File(createTempDir("procstat"), "does-not-exist")
        val reading = CpuSampler(missing).sample()
        assertEquals(null, reading.utilizationPct)
        assertEquals(null, reading.perCoreUtilizationPct)
    }

    @Test
    fun `live host proc stat keeps producing per-core rows`() {
        val stat = File("/proc/stat")
        if (!stat.canRead()) return // non-Linux host: nothing to assert
        val sampler = CpuSampler()
        sampler.sample() // prime the previous-sample maps
        var sawPerCore = false
        repeat(5) {
            Thread.sleep(50)
            val reading = sampler.sample()
            if (reading.perCoreUtilizationPct?.isNotEmpty() == true) {
                sawPerCore = true
                assertTrue(reading.perCoreUtilizationPct!!.all { it in 0..100 })
            }
        }
        assertTrue("per-core parsing must eventually produce rows", sawPerCore)
    }
}
