package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, local-only cache of the most recent measured average FPS per game.
 * Values are descriptive session comparisons, not controlled benchmark results.
 */
object SessionFpsBaselineStore {

    const val MAX_BASELINES = 32
    private const val MIN_VALID_FPS = 1
    private const val MAX_VALID_FPS = 1_000

    private data class Baseline(
        val gamePackage: String,
        val averageFps: Int,
        val endedAtMs: Long
    )

    /** Returns a previous value only for the same validated package name. */
    fun previousAverageFps(serialized: String?, gamePackage: String?): Int? {
        val pkg = gamePackage?.takeIf(ShellInput::isPackageName) ?: return null
        return parse(serialized).firstOrNull { it.gamePackage == pkg }?.averageFps
    }

    /**
     * Record the measured average for one game, replacing its old baseline and
     * retaining at most [MAX_BASELINES] most-recently-updated packages.
     * Invalid input returns null and leaves the caller's stored value untouched.
     */
    fun record(
        serialized: String?,
        gamePackage: String?,
        averageFps: Int,
        endedAtMs: Long
    ): String? {
        val pkg = gamePackage?.takeIf(ShellInput::isPackageName) ?: return null
        if (averageFps !in MIN_VALID_FPS..MAX_VALID_FPS || endedAtMs < 0L) return null

        val updated = parse(serialized)
            .filterNot { it.gamePackage == pkg }
            .plus(Baseline(pkg, averageFps, endedAtMs))
            .sortedWith(compareByDescending<Baseline> { it.endedAtMs }.thenBy { it.gamePackage })
            .take(MAX_BASELINES)

        return JSONArray().apply {
            updated.forEach { baseline ->
                put(
                    JSONObject()
                        .put("gamePackage", baseline.gamePackage)
                        .put("averageFps", baseline.averageFps)
                        .put("endedAt", baseline.endedAtMs)
                )
            }
        }.toString()
    }

    private fun parse(serialized: String?): List<Baseline> {
        if (serialized.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(serialized)
        } catch (_: Exception) {
            return emptyList()
        }

        val byPackage = linkedMapOf<String, Baseline>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val pkg = obj.optString("gamePackage").takeIf(ShellInput::isPackageName) ?: continue
            val fps = obj.optInt("averageFps", -1).takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS }
                ?: continue
            val endedAt = obj.optLong("endedAt", -1L).takeIf { it >= 0L } ?: continue
            val candidate = Baseline(pkg, fps, endedAt)
            val existing = byPackage[pkg]
            if (existing == null || candidate.endedAtMs >= existing.endedAtMs) {
                byPackage[pkg] = candidate
            }
        }
        return byPackage.values.toList()
    }
}
