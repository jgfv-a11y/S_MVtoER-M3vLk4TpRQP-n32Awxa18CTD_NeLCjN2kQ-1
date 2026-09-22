package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.core.adaptive.TrialConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePolicyTest {

    private val cfg = TrialConfig(minPairs = 8, maxPairs = 40)

    @Test fun `consistent positive delta is kept`() {
        val baseline = List(12) { 60 }
        val arm = List(12) { 63 }
        val d = AdaptivePolicy.deltasOf(baseline, arm)
        assertEquals(12, d.size)
        val o = AdaptivePolicy.assess(d, cfg)
        assertEquals(Decision.KEEP, o.decision)
        assertEquals(3.0, o.meanDelta!!, 0.001)
        // zero variance -> zero-width CI entirely above the effect threshold
        assertTrue(o.ciLow!! > cfg.minEffectFps)
    }

    @Test fun `consistent negative delta is dropped`() {
        val d = List(12) { -2.0 }
        val o = AdaptivePolicy.assess(d, cfg)
        assertEquals(Decision.DROP, o.decision)
        assertTrue(o.ciHigh!! < -cfg.minEffectFps)
    }

    @Test fun `noisy data below min pairs needs more`() {
        val d = (1..5).map { (it % 3 - 1).toDouble() } // 5 noisy pairs
        val o = AdaptivePolicy.assess(d, cfg)
        assertEquals(Decision.NEEDS_MORE, o.decision)
        assertEquals(5, o.pairs)
    }

    @Test fun `max pairs with straddling interval is neutral`() {
        // 40 pairs alternating +1 / -1 -> mean 0, wide CI
        val d = (1..40).map { if (it % 2 == 0) 1.0 else -1.0 }
        val o = AdaptivePolicy.assess(d, cfg)
        assertEquals(Decision.NEUTRAL, o.decision)
    }

    @Test fun `fewer than two pairs is needs more`() {
        val o = AdaptivePolicy.assess(emptyList(), cfg)
        assertEquals(Decision.NEEDS_MORE, o.decision)
        assertEquals(0, o.pairs)
        val o2 = AdaptivePolicy.assess(listOf(1.0), cfg)
        assertEquals(Decision.NEEDS_MORE, o2.decision)
    }

    @Test fun `t table sanity`() {
        assertEquals(Double.POSITIVE_INFINITY, AdaptivePolicy.tQuantile(0), 0.0)
        assertEquals(12.706, AdaptivePolicy.tQuantile(1), 0.001)
        assertEquals(2.228, AdaptivePolicy.tQuantile(10), 0.001)
        assertEquals(2.042, AdaptivePolicy.tQuantile(30), 0.001)
        assertEquals(2.0, AdaptivePolicy.tQuantile(31), 0.001)
    }

    @Test fun `deltas truncate to the shorter list`() {
        // arm - baseline per pair, truncated to the shorter list
        val d = AdaptivePolicy.deltasOf(listOf(1, 2, 3), listOf(5, 5))
        assertEquals(listOf(4.0, 3.0), d)
    }

    @Test fun `small samples are not trimmed`() {
        val d = listOf(-1.0, 0.0, 0.5, 1.0, 99.0)
        assertEquals(d, AdaptivePolicy.trimmedDeltas(d))
        assertEquals(emptyList(), AdaptivePolicy.trimmedDeltas(emptyList()))
    }

    @Test fun `outlier spikes are trimmed before the statistics`() {
        // 20 pairs: one huge hitch spike, one huge dip, the rest ~+2.
        // The naive mean would be 1.8; after trimming the engine sees a clean
        // +2.0 and the confidence interval is not dragged wide open.
        val d = (0 until 18).map { 2.0 } + listOf(90.0, -90.0)
        val trimmed = AdaptivePolicy.trimmedDeltas(d)
        assertEquals(18, trimmed.size)
        assertTrue(!trimmed.contains(-90.0))
        assertTrue(trimmed.none { it > 10.0 })

        val outcome = AdaptivePolicy.assess(d, TrialConfig(minPairs = 10, minEffectFps = 0.5))
        assertEquals(Decision.KEEP, outcome.decision)
        assertEquals(2.0, outcome.meanDelta!!, 0.001)
        assertEquals(18, outcome.pairs)
    }

    @Test fun `trimmedDeltas is order independent`() {
        val a = (0 until 20).map { (it * 7) % 13 - 6 }.map { it.toDouble() }
        val trimmedA = AdaptivePolicy.trimmedDeltas(a).sorted()
        val trimmedB = AdaptivePolicy.trimmedDeltas(a.reversed()).sorted()
        assertEquals(trimmedA, trimmedB)
    }
}
