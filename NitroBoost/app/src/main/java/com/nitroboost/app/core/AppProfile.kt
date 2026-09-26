package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-game boost profile. JSON-compatible with the reference app's
 * game_profiles.json schema (a strict superset), so profiles stay portable.
 */
data class AppProfile(
    val packageName: String,
    val name: String,
    val enabledModules: MutableSet<Module> = mutableSetOf(
        Module.CPU, Module.DISPLAY, Module.DND, Module.POWER, Module.TWEAKS, Module.NETWORK
    ),
    var dpi: Int = 0,                     // 0 = keep device density
    var refreshRate: Int = 0,             // 0 = keep device refresh rate
    var fpsCap: Int = 0,                  // 0 = uncapped (informational)
    var dnd: Boolean = true,
    var killAnimations: Boolean = true,
    var aggressiveRamClean: Boolean = false,
    var thermalOverride: Boolean = false, // aggressive: disable thermal throttling — off by default
    var gameMode: Int = 4,                // 0 = do not touch game mode
    var extraProtected: MutableList<String> = mutableListOf()
) {
    fun isEnabled(task: BoostTask): Boolean {
        if (task.module !in enabledModules) return false
        return when (task.id) {
            "dnd" -> dnd
            "animations" -> killAnimations
            "ram_kill" -> aggressiveRamClean
            "thermal_override" -> thermalOverride
            else -> true
        }
    }

    fun copyProfile(): AppProfile = AppProfile(
        packageName = packageName,
        name = name,
        enabledModules = enabledModules.toMutableSet(),
        dpi = dpi,
        refreshRate = refreshRate,
        fpsCap = fpsCap,
        dnd = dnd,
        killAnimations = killAnimations,
        aggressiveRamClean = aggressiveRamClean,
        thermalOverride = thermalOverride,
        gameMode = gameMode,
        extraProtected = extraProtected.toMutableList()
    )

    companion object {
        fun fromJson(o: JSONObject): AppProfile = AppProfile(
            packageName = o.optString("packageName"),
            name = o.optString("name", o.optString("packageName")),
            enabledModules = mutableSetOf<Module>().apply {
                val arr = o.optJSONArray("enabledModules")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        Module.fromKey(arr.optString(i))?.let { add(it) }
                    }
                }
            },
            dpi = o.optInt("dpi", 0),
            refreshRate = o.optInt("refreshRate", 0),
            fpsCap = o.optInt("fpsCap", 0),
            dnd = o.optBoolean("dnd", true),
            killAnimations = o.optBoolean("killAnimations", true),
            aggressiveRamClean = o.optBoolean("aggressiveRamClean", false),
            thermalOverride = o.optBoolean("thermalOverride", false),
            gameMode = o.optInt("gameMode", 4),
            extraProtected = mutableListOf<String>().apply {
                val arr = o.optJSONArray("extraProtected")
                if (arr != null) {
                    for (i in 0 until arr.length()) add(arr.optString(i))
                }
            }
        )

        fun toJson(p: AppProfile): JSONObject = JSONObject().apply {
            put("packageName", p.packageName)
            put("name", p.name)
            put(
                "enabledModules",
                JSONArray().apply { p.enabledModules.forEach { put(it.key) } }
            )
            put("dpi", p.dpi)
            put("refreshRate", p.refreshRate)
            put("fpsCap", p.fpsCap)
            put("dnd", p.dnd)
            put("killAnimations", p.killAnimations)
            put("aggressiveRamClean", p.aggressiveRamClean)
            put("thermalOverride", p.thermalOverride)
            put("gameMode", p.gameMode)
            put("extraProtected", JSONArray().apply { p.extraProtected.forEach { put(it) } })
        }
    }
}
