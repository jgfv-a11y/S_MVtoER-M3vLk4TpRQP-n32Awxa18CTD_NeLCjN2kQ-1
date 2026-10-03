package com.nitroboost.app.core

/**
 * Keeps the last real FPS reading visible briefly between slower sampler polls.
 * This helper is for presentation only; it must never feed adaptive analysis.
 */
class FpsDisplayCache(private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
    private var gamePackage: String? = null
    private var measuredFps: Int? = null
    private var measuredAtMs: Long = 0L

    fun value(fps: Int?, packageName: String?, nowMs: Long): Int? {
        if (packageName != gamePackage) {
            gamePackage = packageName
            measuredFps = null
            measuredAtMs = 0L
        }
        if (packageName.isNullOrBlank()) return null
        if (fps != null && fps in 1..240) {
            measuredFps = fps
            measuredAtMs = nowMs
        }
        val last = measuredFps ?: return null
        val age = nowMs - measuredAtMs
        return last.takeIf { age in 0..maxAgeMs }
    }

    fun clear() {
        gamePackage = null
        measuredFps = null
        measuredAtMs = 0L
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 5_000L
    }
}
