package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsDisplayCacheTest {
    @Test
    fun `keeps a measured value for display only until it becomes stale`() {
        val cache = FpsDisplayCache(maxAgeMs = 5_000L)
        assertEquals(59, cache.value(59, "com.game", 1_000L))
        // Null is a sampler gap, not a new measurement; only the UI cache reuses the reading.
        assertEquals(59, cache.value(null, "com.game", 3_000L))
        assertNull(cache.value(null, "com.game", 6_001L))
    }

    @Test
    fun `package change and missing game clear the previous display value`() {
        val cache = FpsDisplayCache()
        cache.value(90, "com.game.one", 1_000L)
        assertNull(cache.value(null, "com.game.two", 1_500L))
        assertNull(cache.value(null, null, 2_000L))
    }

    @Test
    fun `out of range reading is not displayed`() {
        val cache = FpsDisplayCache()
        assertNull(cache.value(0, "com.game", 1_000L))
        assertNull(cache.value(241, "com.game", 1_000L))
    }
}
