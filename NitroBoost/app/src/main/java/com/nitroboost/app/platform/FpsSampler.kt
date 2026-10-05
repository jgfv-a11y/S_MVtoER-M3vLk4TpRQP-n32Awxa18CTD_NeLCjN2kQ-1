package com.nitroboost.app.platform

import android.os.SystemClock
import com.nitroboost.app.core.ShellInput
import com.nitroboost.app.core.SystemExecutor
import com.nitroboost.app.core.adaptive.GfxFrameStatsParser
import com.nitroboost.app.core.telemetry.IntendedVsyncIntervalTracker

/** One bounded privileged observation; frame times exist only when gfxinfo supplied them. */
data class FpsObservation(
    val fps: Int?,
    val frameTimesMs: List<Double>,
    val processEpoch: Long,
    val measuredAtMs: Long,
    val sourceAvailable: Boolean,
    /** Consecutive gaps from real IntendedVsync timestamps across polls. */
    val frameIntervalsMs: List<Double> = emptyList()
)

/**
 * FPS and (when exposed by the ROM) real frame-time samples via
 * `dumpsys gfxinfo <package> framestats`. The command is throttled to 0.5Hz;
 * monitor/UI ticks do not run a heavy dumpsys on every tick.
 */
class FpsSampler(
    minPollIntervalMs: Long = MIN_POLL_INTERVAL_MS,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val pollIntervalMs = minPollIntervalMs.coerceAtLeast(0L)
    private var lastFrames: Long = -1L
    private var lastTime = 0L
    private var lastPollAt = 0L
    private var hasPolled = false
    private var lastCompletedNs = Long.MIN_VALUE
    private val vsyncIntervals = IntendedVsyncIntervalTracker()
    private var lastPackage: String? = null
    private var processEpoch = 0L

    fun poll(gamePackage: String, executor: SystemExecutor): Int? =
        pollObservation(gamePackage, executor).fps

    @Synchronized
    fun pollObservation(
        gamePackage: String,
        executor: SystemExecutor
    ): FpsObservation {
        val now = clockMs()
        if (now < 0L) return FpsObservation(null, emptyList(), processEpoch, 0L, false)
        if (!ShellInput.isPackageName(gamePackage)) {
            resetForPackage(null)
            return FpsObservation(null, emptyList(), processEpoch, now, false)
        }
        if (gamePackage != lastPackage) {
            resetForPackage(gamePackage)
        }
        if (hasPolled && (now < lastPollAt || now - lastPollAt < pollIntervalMs)) {
            return FpsObservation(null, emptyList(), processEpoch, now, false)
        }
        lastPollAt = now
        hasPolled = true
        val result = executor.shell("dumpsys gfxinfo \"$gamePackage\" framestats 2>/dev/null")
        if (!result.ok) return FpsObservation(null, emptyList(), processEpoch, now, false)

        val frames = TOTAL_FRAMES.find(result.stdout)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (frames != null && lastFrames >= 0L && frames < lastFrames) {
            // gfxinfo's counter reset is evidence that the game process/session
            // changed; do not pair across that boundary.
            processEpoch += 1L
            lastFrames = frames
            lastTime = now
            lastCompletedNs = Long.MIN_VALUE
            vsyncIntervals.reset()
            return FpsObservation(null, emptyList(), processEpoch, now, true)
        }
        val parsed = GfxFrameStatsParser.parse(result.stdout, lastCompletedNs)
        var fps: Int? = null
        if (frames != null) {
            val elapsed = now - lastTime
            if (lastFrames >= 0L && frames > lastFrames && elapsed >= MIN_RATE_INTERVAL_MS) {
                val measured = (frames - lastFrames).toDouble() * 1_000.0 / elapsed.toDouble()
                if (measured.isFinite() && measured >= 1.0 && measured <= MAX_FPS) {
                    fps = measured.toInt()
                }
            }
            lastFrames = frames
            lastTime = now
        }
        if (parsed.lastCompletedNs > lastCompletedNs) lastCompletedNs = parsed.lastCompletedNs
        val frameIntervalsMs = vsyncIntervals.append(parsed.intendedVsyncNs)
        return FpsObservation(
            fps = fps,
            frameTimesMs = parsed.frameTimesMs,
            processEpoch = processEpoch,
            measuredAtMs = now,
            sourceAvailable = frames != null || parsed.rowsSeen > 0,
            frameIntervalsMs = frameIntervalsMs
        )
    }

    private fun resetForPackage(packageName: String?) {
        if (packageName == lastPackage) return
        if (lastPackage != null) processEpoch += 1L
        lastPackage = packageName
        lastFrames = -1L
        lastTime = 0L
        lastPollAt = 0L
        hasPolled = false
        lastCompletedNs = Long.MIN_VALUE
        vsyncIntervals.reset()
    }

    companion object {
        private val TOTAL_FRAMES = Regex("Total frames rendered:\\s*(\\d+)")
        const val MIN_POLL_INTERVAL_MS = 2_000L
        const val MIN_RATE_INTERVAL_MS = 800L
        const val MAX_FPS = 1_000
    }
}
