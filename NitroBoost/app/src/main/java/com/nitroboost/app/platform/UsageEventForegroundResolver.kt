package com.nitroboost.app.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.nitroboost.app.core.ShellInput

/** Resolves currently resumed packages from lifecycle events, never last-use timestamps. */
object UsageEventForegroundResolver {
    private const val HISTORY_MS = 24L * 60L * 60L * 1_000L

    fun currentPackage(context: Context, nowMs: Long = System.currentTimeMillis()): String? =
        readState(context, nowMs)?.currentPackage()
            ?.takeIf(ShellInput::isPackageName)

    /** All resumed packages, including split-screen/PiP activities where supported. */
    fun foregroundPackages(context: Context, nowMs: Long = System.currentTimeMillis()): Set<String> =
        readState(context, nowMs)?.foregroundPackages()
            ?.filterTo(LinkedHashSet(), ShellInput::isPackageName)
            ?: emptySet()

    private fun readState(context: Context, nowMs: Long): ForegroundPackageState? {
        val appContext = context.applicationContext
        if (!UsageEventPackageTracker.hasUsageAccess(appContext)) return null
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val start = (nowMs - HISTORY_MS).coerceAtLeast(0L)
        return try {
            val events = manager.queryEvents(start, nowMs) ?: return null
            val state = ForegroundPackageState()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED ->
                            state.activityResumed(pkg, event.className)
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED ->
                            state.activityPaused(pkg, event.className)
                    }
                } else {
                    when (event.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> state.packageForeground(pkg)
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> state.packageBackground(pkg)
                    }
                }
            }
            state
        } catch (_: Exception) {
            null
        }
    }
}
