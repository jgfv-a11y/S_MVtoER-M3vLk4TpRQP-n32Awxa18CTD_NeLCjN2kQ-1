package com.nitroboost.app.core

import com.nitroboost.app.data.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0: the profile list used to show a game twice when the user created
 * a custom profile for a built-in game (e.g. Free Fire). mergeProfiles()
 * is the pure rule: customs SHADOW builtins by package name.
 */
class ProfileMergeTest {

    private fun p(pkg: String, name: String = pkg): AppProfile =
        AppProfile(packageName = pkg, name = name)

    @Test
    fun `no customs returns builtins in order`() {
        val b = listOf(p("a"), p("b"), p("c"))
        val m = ProfileStore.mergeProfiles(b, emptyList())
        assertEquals(listOf("a", "b", "c"), m.map { it.packageName })
    }

    @Test
    fun `no builtins returns customs in order`() {
        val c = listOf(p("x"), p("y"))
        val m = ProfileStore.mergeProfiles(emptyList(), c)
        assertEquals(listOf("x", "y"), m.map { it.packageName })
    }

    @Test
    fun `custom shadows the builtin of the same game`() {
        val builtins = listOf(p("com.freefire", "Free Fire"))
        val customs = listOf(p("com.freefire", "Free Fire (my settings)"))
        val m = ProfileStore.mergeProfiles(builtins, customs)

        // Exactly ONE Free Fire — the user's own profile wins.
        assertEquals(1, m.size)
        assertEquals("Free Fire (my settings)", m[0].name)
    }

    @Test
    fun `shadowing keeps other builtins intact`() {
        val builtins = listOf(p("com.foxbaby.freefire", "Free Fire"), p("com.pubg", "PUBG"))
        val customs = listOf(p("com.foxbaby.freefire", "Free Fire custom"))
        val m = ProfileStore.mergeProfiles(builtins, customs)

        assertEquals(2, m.size)
        assertEquals(setOf("com.foxbaby.freefire", "com.pubg"), m.map { it.packageName }.toSet())
        val ff = m.first { it.packageName == "com.foxbaby.freefire" }
        assertEquals("Free Fire custom", ff.name)
    }

    @Test
    fun `merge never duplicates a package name`() {
        val builtins = (1..10).map { p("pkg$it") }
        val customs = (1..10).map { p("pkg$it", "custom$it") }
        val m = ProfileStore.mergeProfiles(builtins, customs)
        assertEquals(10, m.size)
        assertTrue(m.map { it.packageName }.toSet().size == 10)
        // every row is the custom one
        assertTrue(m.all { it.name.startsWith("custom") })
    }
}
