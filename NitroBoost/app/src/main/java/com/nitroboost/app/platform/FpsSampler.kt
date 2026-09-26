package com.nitroboost.app.platform

import android.os.SystemClock
import com.nitroboost.app.core.SystemExecutor

/**
 * Real FPS for the running game via `dumpsys gfxinfo <package>`.
 * Requires a privileged shell (Shizuku). Poll every >= 1s; the first poll
 * only primes the counter and returns null.
 */
class FpsSampler {

    private var lastFrames: Long = -1L
    private var lastTime = 0L

    fun poll(gamePackage: String, executor: SystemExecutor): Int? {
        if (gamePackage.isBlank()) return null
        val r = executor.shell("dumpsys gfxinfo \"$gamePackage\" 2>/dev/null")
        if (!r.ok) return null
        val m = Regex("Total frames rendered:\\s*(\\d+)").find(r.stdout) ?: return null
        val frames = m.groupValues[1].toLongOrNull() ?: return null
        val now = SystemClock.elapsedRealtime()
        val fps: Int? = if (lastFrames in 0 until frames && now - lastTime >= 800) {
            ((frames - lastFrames) * 1000L / (now - lastTime)).toInt()
                .coerceIn(1, 240)
        } else {
            null
        }
        lastFrames = frames
        lastTime = now
        return fps
    }
}
