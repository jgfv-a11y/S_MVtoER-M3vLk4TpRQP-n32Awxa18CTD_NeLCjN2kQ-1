package com.nitroboost.app.core

import com.nitroboost.app.core.telemetry.IntendedVsyncIntervalTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class IntendedVsyncIntervalTrackerTest {

    @Test fun `intervals continue across polling chunks using measured timestamps`() {
        val tracker = IntendedVsyncIntervalTracker()
        assertEquals(emptyList<Double>(), tracker.append(listOf(1_000_000_000L)))
        assertEquals(listOf(20.0), tracker.append(listOf(1_020_000_000L)))
        assertEquals(
            listOf(20.0, 20.0),
            tracker.append(listOf(1_040_000_000L, 1_060_000_000L))
        )
    }

    @Test fun `duplicate backward and implausibly long timestamps do not create intervals`() {
        val tracker = IntendedVsyncIntervalTracker()
        tracker.append(listOf(2_000_000_000L))
        assertEquals(emptyList<Double>(), tracker.append(listOf(2_000_000_000L, 1_900_000_000L)))
        assertEquals(emptyList<Double>(), tracker.append(listOf(4_000_000_000L)))
        assertEquals(listOf(16.0), tracker.append(listOf(4_016_000_000L)))
    }

    @Test fun `backward timestamp starts a new epoch and resumes intervals`() {
        val tracker = IntendedVsyncIntervalTracker()
        tracker.append(listOf(2_000_000_000L))
        assertEquals(emptyList<Double>(), tracker.append(listOf(1_000_000_000L)))
        assertEquals(listOf(16.7), tracker.append(listOf(1_016_700_000L)))
    }

    @Test fun `reset prevents pairing timestamps across process epochs`() {
        val tracker = IntendedVsyncIntervalTracker()
        tracker.append(listOf(1_000_000_000L))
        tracker.reset()
        assertEquals(emptyList<Double>(), tracker.append(listOf(9_000_000_000L)))
        assertEquals(listOf(16.7), tracker.append(listOf(9_016_700_000L)))
    }
}
