package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One persisted boost-session summary, scoped to a single game package.
 * Stored as part of the bounded history array (see [SessionHistoryStore]).
 */
data class SessionHistoryEntry(
    val gamePackage: String,
    val endedAt: Long,
    val durationSec: Int,
    val avgFps: Int?,
    val minFps: Int?,
    val peakTempC: Int?,
    val applied: Int,
    val failed: Int
)

/**
 * Descriptive FPS trend between the most recent sessions of one game and the
 * sessions before them. Sessions are NOT controlled A/B trials: workload,
 * settings, and thermal conditions may differ, so this is never an
 * improvement claim — only a same-game observational comparison.
 */
data class FpsTrend(
    val recentAvgFps: Int,
    val olderAvgFps: Int,
    val deltaFps: Int,
    val sampleCount: Int
)

/**
 * Small, local-only, bounded history of per-game session summaries.
 *
 * Invariants:
 *  - package names are validated ([ShellInput.isPackageName]);
 *  - at most [MAX_ENTRIES_PER_GAME] entries per game (most recent kept);
 *  - at most [MAX_GAMES] games retained (most recently active kept);
 *  - unreadable/invalid FPS values are stored as null, never as 0;
 *  - malformed stored JSON parses to an empty list, never throws;
 *  - a trend is reported only with enough samples ([MIN_TREND_SAMPLES]) so a
 *    single lucky session cannot masquerade as a trend.
 *
 * Pure functions + org.json only, so everything is unit-testable on the JVM.
 */
object SessionHistoryStore {

    const val MAX_ENTRIES_PER_GAME = 10
    const val MAX_GAMES = 32

    /** Sessions compared per side of the trend (recent vs older). */
    const val TREND_WINDOW = 2

    /** Minimum sessions with a valid FPS before any trend is reported. */
    const val MIN_TREND_SAMPLES = 4

    private const val MIN_VALID_FPS = 1
    private const val MAX_VALID_FPS = 1_000
    private const val MAX_DURATION_SEC = 7 * 24 * 3600
    private const val MAX_TASKS = 10_000
    private const val MIN_TEMP_C = -50
    private const val MAX_TEMP_C = 120

    /**
     * Record one session summary. Returns the new serialized history, or null
     * when the input is invalid (caller must keep the previous value).
     */
    fun record(
        serialized: String?,
        gamePackage: String?,
        endedAt: Long,
        durationSec: Int,
        avgFps: Int?,
        minFps: Int?,
        peakTempC: Int?,
        applied: Int,
        failed: Int
    ): String? {
        val pkg = gamePackage?.takeIf(ShellInput::isPackageName) ?: return null
        if (endedAt < 0L) return null
        if (durationSec !in 0..MAX_DURATION_SEC) return null
        if (applied !in 0..MAX_TASKS || failed !in 0..MAX_TASKS) return null

        val entry = SessionHistoryEntry(
            gamePackage = pkg,
            endedAt = endedAt,
            durationSec = durationSec,
            avgFps = avgFps?.takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS },
            minFps = minFps?.takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS },
            peakTempC = peakTempC?.takeIf { it in MIN_TEMP_C..MAX_TEMP_C },
            applied = applied,
            failed = failed
        )

        // Keep ALL stored entries (including previous sessions of the same
        // game — unlike the single-value FPS baseline store); the per-game cap
        // below is what bounds the history, not a same-game filter.
        val perGame = (parse(serialized) + entry)
            .groupBy { it.gamePackage }
            .flatMap { (_, entries) ->
                entries
                    .sortedWith(entryOrder())
                    .take(MAX_ENTRIES_PER_GAME)
            }
        // Keep the most recently active games if the global cap is exceeded.
        val lastActiveByGame = perGame
            .groupBy { it.gamePackage }
            .mapValues { (_, entries) -> entries.maxOf { it.endedAt } }
        val keptGames = lastActiveByGame.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(MAX_GAMES)
            .map { it.key }
            .toSet()
        val out = perGame
            .filter { it.gamePackage in keptGames }
            .sortedWith(compareByDescending<SessionHistoryEntry> { it.endedAt }.thenBy { it.gamePackage })

        return JSONArray().apply { out.forEach { put(toJson(it)) } }.toString()
    }

    /** History of one game, most recent first. Invalid package → empty list. */
    fun historyFor(serialized: String?, gamePackage: String?): List<SessionHistoryEntry> {
        val pkg = gamePackage?.takeIf(ShellInput::isPackageName) ?: return emptyList()
        return parse(serialized)
            .filter { it.gamePackage == pkg }
            .sortedWith(entryOrder())
    }

    /**
     * Descriptive FPS trend for one game's entries (any order accepted).
     * Returns null unless at least [MIN_TREND_SAMPLES] entries carry a valid
     * FPS and both the recent and the older window have ≥ 2 samples.
     */
    fun trendFor(entries: List<SessionHistoryEntry>): FpsTrend? {
        val withFps = entries
            .filter { it.avgFps != null && it.avgFps in MIN_VALID_FPS..MAX_VALID_FPS }
            .sortedWith(entryOrder())
        if (withFps.size < MIN_TREND_SAMPLES) return null
        val recent = withFps.take(TREND_WINDOW)
        val older = withFps.drop(TREND_WINDOW).take(TREND_WINDOW)
        if (recent.size < 2 || older.size < 2) return null
        val recentAvg = recent.map { it.avgFps!! }.average()
        val olderAvg = older.map { it.avgFps!! }.average()
        return FpsTrend(
            recentAvgFps = recentAvg.toInt(),
            olderAvgFps = olderAvg.toInt(),
            deltaFps = (recentAvg - olderAvg).toInt(),
            sampleCount = withFps.size
        )
    }

    /** Defensive parse: malformed input → empty list, bad entries skipped. */
    fun parse(serialized: String?): List<SessionHistoryEntry> {
        if (serialized.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(serialized)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<SessionHistoryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            fromJson(obj)?.let { out.add(it) }
        }
        return out
    }

    private fun entryOrder() =
        compareByDescending<SessionHistoryEntry> { it.endedAt }
            .thenByDescending { it.durationSec }
            .thenBy { it.gamePackage }

    private fun toJson(e: SessionHistoryEntry): JSONObject = JSONObject()
        .put("gamePackage", e.gamePackage)
        .put("endedAt", e.endedAt)
        .put("durationSec", e.durationSec)
        .put("avgFps", e.avgFps ?: JSONObject.NULL)
        .put("minFps", e.minFps ?: JSONObject.NULL)
        .put("peakTempC", e.peakTempC ?: JSONObject.NULL)
        .put("applied", e.applied)
        .put("failed", e.failed)

    private fun fromJson(obj: JSONObject): SessionHistoryEntry? {
        val pkg = obj.optString("gamePackage").takeIf(ShellInput::isPackageName) ?: return null
        val endedAt = obj.optLong("endedAt", -1L).takeIf { it >= 0L } ?: return null
        val durationSec = obj.optInt("durationSec", -1).takeIf { it in 0..MAX_DURATION_SEC } ?: return null
        val applied = obj.optInt("applied", -1).takeIf { it in 0..MAX_TASKS } ?: return null
        val failed = obj.optInt("failed", -1).takeIf { it in 0..MAX_TASKS } ?: return null
        val avgFps = if (obj.isNull("avgFps")) null else obj.optInt("avgFps", -1).takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS }
        val minFps = if (obj.isNull("minFps")) null else obj.optInt("minFps", -1).takeIf { it in MIN_VALID_FPS..MAX_VALID_FPS }
        val peakTempC = if (obj.isNull("peakTempC")) null else obj.optInt("peakTempC", -1).takeIf { it in MIN_TEMP_C..MAX_TEMP_C }
        return SessionHistoryEntry(pkg, endedAt, durationSec, avgFps, minFps, peakTempC, applied, failed)
    }
}
