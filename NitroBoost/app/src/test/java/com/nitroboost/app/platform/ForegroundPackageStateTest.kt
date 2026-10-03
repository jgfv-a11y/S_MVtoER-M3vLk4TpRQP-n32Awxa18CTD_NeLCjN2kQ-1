package com.nitroboost.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPackageStateTest {
    @Test
    fun `latest resumed activity wins and pause returns remaining activity`() {
        val state = ForegroundPackageState()
        state.activityResumed("com.game", "GameActivity")
        state.activityResumed("com.game", "OverlayActivity")
        assertEquals("com.game", state.currentPackage())
        state.activityPaused("com.game", "OverlayActivity")
        assertEquals("com.game", state.currentPackage())
        state.activityPaused("com.game", "GameActivity")
        assertNull(state.currentPackage())
    }

    @Test
    fun `all resumed packages are exposed for multi window protection`() {
        val state = ForegroundPackageState()
        state.activityResumed("com.game", "GameActivity")
        state.activityResumed("com.overlay", "FloatingActivity")
        assertEquals(setOf("com.game", "com.overlay"), state.foregroundPackages())
    }

    @Test
    fun `background event clears all activities for that package`() {
        val state = ForegroundPackageState()
        state.activityResumed("com.game", "GameActivity")
        state.activityResumed("com.game", "ResultsActivity")
        state.packageBackground("com.game")
        assertNull(state.currentPackage())
    }

    @Test
    fun `latest activity is returned so callers can reject their own package`() {
        val state = ForegroundPackageState()
        state.activityResumed("com.game", "GameActivity")
        state.activityPaused("com.game", "GameActivity")
        state.activityResumed("com.booster", "MainActivity")
        assertEquals("com.booster", state.currentPackage())
    }

    @Test
    fun `legacy package transitions are tracked`() {
        val state = ForegroundPackageState()
        state.packageForeground("com.game")
        assertEquals("com.game", state.currentPackage())
        state.packageBackground("com.game")
        assertNull(state.currentPackage())
    }
}
