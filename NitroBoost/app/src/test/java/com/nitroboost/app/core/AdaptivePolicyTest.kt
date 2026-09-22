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
}
