package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSpaceDetectorTest {

    @Test
    fun `match returns only installed known packages`() {
        val found = GameSpaceDetector.match(
            setOf("com.miui.securitycenter", "com.samsung.android.game.gos", "com.unknown.app")
        )
        assertEquals(2, found.size)
        assertTrue(found.any { it.first == "com.miui.securitycenter" })
        assertTrue(found.any { it.first == "com.samsung.android.game.gos" })
        assertTrue(found.all { it.second.isNotEmpty() })
    }

    @Test
    fun `nothing installed means no hint`() {
        assertEquals(0, GameSpaceDetector.match(emptySet()).size)
    }

    @Test
    fun `every known package has a display name`() {
        assertTrue(GameSpaceDetector.KNOWN.isNotEmpty())
        GameSpaceDetector.KNOWN.forEach { (_, name) -> assertTrue(name.isNotBlank()) }
    }
}
