package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.AnimationsTask
import com.nitroboost.app.core.tasks.GameModeTask
import com.nitroboost.app.core.tasks.NetworkTask
import com.nitroboost.app.core.tasks.PowerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsTasksTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_settings_tasks", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `power task records a real previous value and restore puts it back`() {
        val ex = FakeExecutor()
        ex.global["low_power"] = "1"
        val j = journal()
        val ctx = BoostContext(testProfile(Module.POWER), ex, j)

        val result = PowerTask().apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("1", result.entries.single().oldValue)
        assertEquals("0", ex.globalSettingGet("low_power"))
        assertTrue(Journal.restore(result.entries.single(), ex))
        assertEquals("1", ex.globalSettingGet("low_power"))
    }

    @Test
    fun `power task records null for an unset key and restore deletes it`() {
        val ex = FakeExecutor()
        val j = journal()
        val ctx = BoostContext(testProfile(Module.POWER), ex, j)

        val result = PowerTask().apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertNull(result.entries.single().oldValue)
        assertEquals("0", ex.globalSettingGet("low_power"))
        assertTrue(Journal.restore(result.entries.single(), ex))
        assertNull("restore must remove the key the session created", ex.globalSettingGet("low_power"))
    }

    @Test
    fun `network task records null for an unset key and restore deletes it`() {
        val ex = FakeExecutor()
        val j = journal()
        val ctx = BoostContext(testProfile(Module.NETWORK), ex, j)

        val result = NetworkTask().apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertNull(result.entries.single().oldValue)
        assertEquals("0", ex.globalSettingGet("no_background_data"))
        assertTrue(Journal.restore(result.entries.single(), ex))
        assertNull(ex.globalSettingGet("no_background_data"))
    }

    @Test
    fun `game mode task records null for an unset key and restore deletes it`() {
        val ex = FakeExecutor()
        val j = journal()
        val profile = testProfile(Module.CPU, gameMode = 4)
        val ctx = BoostContext(profile, ex, j)

        val result = GameModeTask().apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertNull(result.entries.single().oldValue)
        assertEquals("4", ex.secureSettingGet("game_mode"))
        assertTrue(Journal.restore(result.entries.single(), ex))
        assertNull(ex.secureSettingGet("game_mode"))
    }

    @Test
    fun `animations task keeps unset keys unset on restore`() {
        val ex = FakeExecutor()
        ex.sys["window_animation_scale"] = "1.0"
        val j = journal()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        val result = AnimationsTask().apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        val byKey = result.entries.associateBy { it.key }
        assertEquals("1.0", byKey["window_animation_scale"]?.oldValue)
        assertNull(byKey["transition_animation_scale"]?.oldValue)
        for (entry in result.entries) {
            assertTrue(Journal.restore(entry, ex))
        }
        assertEquals("1.0", ex.sysSettingGet("window_animation_scale"))
        assertNull(ex.sysSettingGet("transition_animation_scale"))
    }
}
