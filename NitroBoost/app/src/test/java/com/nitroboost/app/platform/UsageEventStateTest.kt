package com.nitroboost.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventStateTest {

    @Test
    fun `foreground state persists without a fresh heartbeat event`() {
        val state = UsageEventState()
        state.packageForeground()

        // A missing event in a later poll is not evidence that the app exited.
        assertEquals(true, state.isForeground)
        assertEquals(true, state.isForeground)
    }

    @Test
    fun `paused activity is foreground only while another game activity is resumed`() {
        val state = UsageEventState()
        state.activityResumed("GameActivity")
        state.activityResumed("GameResultsActivity")
        state.activityPaused("GameActivity")
        assertEquals(true, state.isForeground)

        state.activityPaused("GameResultsActivity")
        assertEquals(false, state.isForeground)
    }

    @Test
    fun `legacy background transition clears any resumed activity`() {
        val state = UsageEventState()
        state.activityResumed("GameActivity")
        state.packageBackground()
        assertEquals(false, state.isForeground)
    }
}
