package com.nitroboost.app.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Tracks foreground/background transitions for one game using UsageEvents.
 * UsageStats.lastTimeUsed is a timestamp of the last activity use, not a live
 * process heartbeat; treating it as one can incorrectly end long game sessions.
 *
 * The tracker reads recent history once, then advances an overlapping cursor
 * so each five-second poll only processes newly recorded events. A null result
 * means the state cannot be verified (for example, Usage Access is missing),
 * and callers must not interpret that as the game having exited.
 */
class UsageEventPackageTracker(context: Context, private val packageName: String) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val state = UsageEventState()

    @Volatile
    private var nextQueryStartMs = System.currentTimeMillis() - INITIAL_HISTORY_MS

    @Synchronized
    fun isForeground(): Boolean? {
        val manager = usageStats ?: return null
        if (!hasUsageAccess(appContext)) return null

        val now = System.currentTimeMillis()
        return try {
            val events = manager.queryEvents(nextQueryStartMs, now) ?: return null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                apply(event)
            }
            // Keep a small overlap to avoid losing events at the query boundary.
            // Replaying an event is safe because the reducer is idempotent.
            nextQueryStartMs = (now - QUERY_OVERLAP_MS).coerceAtLeast(nextQueryStartMs)
            state.isForeground
        } catch (_: Exception) {
            null
        }
    }

    private fun apply(event: UsageEvents.Event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android Q+, ACTIVITY_RESUMED/PAUSED replace the older
            // package-level events (their integer values may alias), so do not
            // run both reducers for the same event.
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> state.activityResumed(event.className)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> state.activityPaused(event.className)
            }
        } else {
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> state.packageForeground()
                UsageEvents.Event.MOVE_TO_BACKGROUND -> state.packageBackground()
            }
        }
    }

    companion object {
        private const val INITIAL_HISTORY_MS = 24L * 60L * 60L * 1000L
        private const val QUERY_OVERLAP_MS = 1_000L

        /** Usage Access is a special AppOp, not a runtime permission dialog. */
        fun hasUsageAccess(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }
}
