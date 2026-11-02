package com.nitroboost.app.core

/**
 * Idle power policy (v1.24.1 thermal/CPU efficiency).
 *
 * Pure functions deciding how often background work runs depending on what
 * is actually active. Rationale: while the app is open but no boost session
 * and no overlay is running, 1-second sampling (proc/stat, sysfs, TCP ping)
 * is unnecessary work that heats the device and drains battery. All values
 * are code-level decisions; no on-device measurement is claimed here.
 */
object IdlePolicy {

    /** Monitor sampling interval. 1s while something live consumes it, 5s for UI-only idle. */
    const val MONITOR_ACTIVE_MS = 1_000L
    const val MONITOR_IDLE_MS = 5_000L

    fun monitorIntervalMs(uiVisible: Boolean, sessionActive: Boolean, overlayVisible: Boolean): Long? {
        if (sessionActive || overlayVisible) return MONITOR_ACTIVE_MS
        if (uiVisible) return MONITOR_IDLE_MS
        return null // nothing is consuming samples: stop
    }

    /** Network probing (TCP ping / retrans reads) only makes sense during live use. */
    fun networkProbeAllowed(sessionActive: Boolean, overlayVisible: Boolean): Boolean =
        sessionActive || overlayVisible

    /** Shizuku card poller: 2s while the card is shown / not ready, 15s as a disconnect backstop. */
    const val SHIZUKU_POLL_ACTIVE_MS = 2_000L
    const val SHIZUKU_POLL_IDLE_MS = 15_000L

    fun shizukuPollDelayMs(connectionReady: Boolean, cardVisible: Boolean): Long =
        if (!connectionReady || cardVisible) SHIZUKU_POLL_ACTIVE_MS else SHIZUKU_POLL_IDLE_MS

    /** PackageManager.getPackageInfo is expensive IPC — cache it for this long. */
    const val INSTALLED_CACHE_MS = 30_000L

    /**
     * A "usable" check must not execute a real shell command just to answer;
     * binder-alive + service-bound is enough. The expensive `run("true")`
     * verification is reserved for the moment before a boost actually starts.
     */
    fun usableWithoutCommand(binderAlive: Boolean, bound: Boolean): Boolean =
        binderAlive && bound
}
