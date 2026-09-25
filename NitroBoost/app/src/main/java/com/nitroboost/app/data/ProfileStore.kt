package com.nitroboost.app.data

import android.content.Context
import com.nitroboost.app.core.AppProfile
import org.json.JSONArray
import java.io.File

/**
 * Profiles: built-ins shipped in assets (reference-compatible schema) plus
 * user-created profiles persisted in app files.
 */
class ProfileStore(private val ctx: Context) {

    private fun customFile(): File = File(ctx.filesDir, "profiles_custom.json")

    fun builtins(): List<AppProfile> {
        return try {
            val text = ctx.assets.open("game_profiles.json").bufferedReader().use { it.readText() }
            parseArray(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun customs(): List<AppProfile> {
        val f = customFile()
        if (!f.exists()) return emptyList()
        return try {
            parseArray(f.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Built-ins + custom profiles. A custom profile SHADOWS a built-in with
     * the same package name (the user's settings win) — the list never shows
     * the same game twice.
     */
    fun all(): List<AppProfile> =
        mergeProfiles(builtins(), customs())

    companion object {
        /** Pure, unit-testable merge: customs shadow builtins by packageName. */
        fun mergeProfiles(builtins: List<AppProfile>, customs: List<AppProfile>): List<AppProfile> {
            val customPkgs = customs.mapTo(HashSet()) { it.packageName }
            val shadowed = builtins.filter { it.packageName !in customPkgs }
            return shadowed + customs
        }
    }

    fun resolve(pkg: String?): AppProfile {
        val all = all()
        val wanted = if (pkg.isNullOrBlank()) Prefs.activeProfile(ctx) else pkg
        return all.firstOrNull { it.packageName == wanted }
            ?: AppProfile(packageName = wanted ?: "", name = wanted ?: "General")
    }

    fun upsertCustom(p: AppProfile) {
        val list = customs().toMutableList()
        list.removeAll { it.packageName == p.packageName }
        list.add(p)
        save(list)
    }

    fun removeCustom(pkg: String) {
        save(customs().filterNot { it.packageName == pkg })
    }

    fun save(list: List<AppProfile>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(AppProfile.toJson(it)) }
            customFile().writeText(arr.toString(2))
        } catch (e: Exception) {
            // never crash on persistence
        }
    }

    private fun parseArray(text: String): List<AppProfile> {
        val arr = JSONArray(text)
        val out = mutableListOf<AppProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = AppProfile.fromJson(o)
            if (p.packageName.isNotBlank()) out.add(p)
        }
        return out
    }
}
