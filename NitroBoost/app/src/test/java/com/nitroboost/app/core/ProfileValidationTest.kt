package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidationTest {
    @Test
    fun `valid settings are accepted and unsafe package is rejected`() {
        val valid = AppProfile("com.example.game", "Game", dpi = 420, refreshRate = 120, gameMode = 4)
        assertTrue(ProfileValidation.invalidFields(valid).isEmpty())
        assertNull(ProfileValidation.validatedCopy(valid.copy(packageName = "com.example;reboot")))
    }

    @Test
    fun `out of range profile settings are reported before storage or shell use`() {
        val invalid = AppProfile(
            "com.example.game", "Game", dpi = 1, refreshRate = 999,
            gameMode = 99, fpsCap = -1
        )
        assertEquals(
            setOf(ProfileValidation.Field.DPI, ProfileValidation.Field.REFRESH_RATE,
                ProfileValidation.Field.GAME_MODE, ProfileValidation.Field.FPS_CAP),
            ProfileValidation.invalidFields(invalid)
        )
        assertNull(ProfileValidation.validatedCopy(invalid))
    }

    @Test
    fun `normalization trims names and filters protected package values`() {
        val source = AppProfile(
            "com.example.game", "  Example  ",
            extraProtected = mutableListOf("com.safe.app", "com.bad;app", "com.safe.app")
        )
        val normalized = ProfileValidation.validatedCopy(source)!!
        assertEquals("Example", normalized.name)
        assertEquals(listOf("com.safe.app"), normalized.extraProtected)
        assertFalse(normalized === source)
    }
}
