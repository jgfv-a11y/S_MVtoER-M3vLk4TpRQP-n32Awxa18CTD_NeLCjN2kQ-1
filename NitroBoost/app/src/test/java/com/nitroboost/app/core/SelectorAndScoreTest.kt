package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.BackgroundSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorAndScoreTest {

    @Test
    fun `selector protects game self and protected list`() {
        val now = 1_000_000L
        val background = mapOf(
            "com.game" to now - 1000,           // the game itself
            "com.booster" to now - 999_999,     // self
            "com.protected" to now - 999_999,   // user protected
            "com.idle1" to now - 999_999,       // killable
            "com.idle2" to now - 999_999,       // killable
            "com.recent" to now - 10_000        // too recent
        )
        val selected = BackgroundSelector.select(
            background,
            foregroundPackage = "com.game",
            selfPackage = "com.booster",
            protectedPackages = setOf("com.protected"),
            nowMs = now
        )
        assertEquals(listOf("com.idle1", "com.idle2"), selected)
    }

    @Test
    fun `selector returns empty when nothing idle`() {
        val now = 1_000_000L
        val selected = BackgroundSelector.select(
            mapOf("com.a" to now - 1000),
            null, null, emptySet(), now
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `score bounds`() {
        assertEquals(0, ScoreEngine.compute(ScoreEngine.Inputs.EMPTY))
        assertEquals(
            100,
            ScoreEngine.compute(
                ScoreEngine.Inputs(10, 10, 0, 1.0, 1.0)
            )
        )
        val mid = ScoreEngine.compute(ScoreEngine.Inputs(5, 10, 2, 0.5, 0.5))
        assertTrue(mid in 0..100)
    }

    @Test
    fun `profile json round trip`() {
        val p = AppProfile(
            packageName = "com.x",
            name = "X",
            enabledModules = mutableSetOf(Module.CPU, Module.GPU),
            dpi = 420,
            refreshRate = 120,
            gameMode = 3,
            aggressiveRamClean = true,
            extraProtected = mutableListOf("com.a", "com.b")
        )
        val o = AppProfile.toJson(p)
        val back = AppProfile.fromJson(o)
        assertEquals(p.packageName, back.packageName)
        assertEquals(p.name, back.name)
        assertEquals(p.enabledModules, back.enabledModules)
        assertEquals(420, back.dpi)
        assertEquals(120, back.refreshRate)
        assertEquals(3, back.gameMode)
        assertTrue(back.aggressiveRamClean)
        assertEquals(p.extraProtected, back.extraProtected)
    }

    @Test
    fun `profile gates tasks by switches`() {
        val p = testProfile(Module.DND, Module.TWEAKS, aggressive = false)
        val dnd = com.nitroboost.app.core.tasks.DndTask()
        val kill = com.nitroboost.app.core.tasks.RamKillTask()
        val anim = com.nitroboost.app.core.tasks.AnimationsTask()
        assertTrue(p.isEnabled(dnd))
        assertTrue(p.isEnabled(anim))
        // ram_kill needs the module AND the switch
        val p2 = testProfile(Module.RAM, aggressive = true)
        assertTrue(p2.isEnabled(kill))
        val p3 = testProfile(Module.RAM, aggressive = false)
        assertTrue(!p3.isEnabled(kill))
    }
}
