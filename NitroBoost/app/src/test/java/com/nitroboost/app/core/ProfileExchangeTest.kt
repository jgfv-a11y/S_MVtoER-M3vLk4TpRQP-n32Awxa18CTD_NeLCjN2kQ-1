package com.nitroboost.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExchangeTest {
    private fun profile(pkg: String, name: String): AppProfile =
        AppProfile(
            packageName = pkg,
            name = name,
            enabledModules = mutableSetOf(Module.CPU, Module.DISPLAY),
            dpi = 0,
            refreshRate = 120,
            gameMode = 0
        )

    @Test
    fun `export and import round trip is deterministic`() {
        val text = ProfileExchange.encode(
            listOf(profile("com.example.z", "Z"), profile("com.example.a", "A")),
            exportedAtMs = 123L
        )
        val result = ProfileExchange.decode(text)
        assertTrue(result is ProfileExchange.ImportResult.Accepted)
        val accepted = result as ProfileExchange.ImportResult.Accepted
        assertEquals(listOf("com.example.a", "com.example.z"), accepted.profiles.map { it.packageName })
        assertEquals("A", accepted.profiles.first().name)
    }

    @Test
    fun `checksum mismatch rejects before validation commit`() {
        val root = JSONObject(
            ProfileExchange.encode(listOf(profile("com.example.game", "Game")), 1L)
        )
        root.getJSONArray("profiles").getJSONObject(0).put("name", "tampered")
        val result = ProfileExchange.decode(root.toString())
        assertTrue(result is ProfileExchange.ImportResult.Rejected)
        assertTrue((result as ProfileExchange.ImportResult.Rejected).reason.contains("checksum"))
    }

    @Test
    fun `unsupported schema and duplicate packages are rejected`() {
        val unsupported = JSONObject()
            .put("schemaVersion", 99)
            .put("profiles", org.json.JSONArray())
        assertTrue(ProfileExchange.decode(unsupported.toString()) is ProfileExchange.ImportResult.Rejected)

        val one = profile("com.example.game", "Game")
        val duplicateText = ProfileExchange.encode(listOf(one), 1L)
        val duplicateRoot = JSONObject(duplicateText)
        duplicateRoot.getJSONArray("profiles").put(AppProfile.toJson(one))
        // The checksum was calculated for one entry, so this is safely rejected
        // before any duplicate could reach ProfileStore.
        assertTrue(ProfileExchange.decode(duplicateRoot.toString()) is ProfileExchange.ImportResult.Rejected)
    }
}
