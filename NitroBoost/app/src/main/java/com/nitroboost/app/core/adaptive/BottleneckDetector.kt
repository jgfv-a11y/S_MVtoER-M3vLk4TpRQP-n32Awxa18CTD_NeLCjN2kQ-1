package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.ThermalGuard

/**
 * Classifies where frames are being lost.
 *
 * Order of rules = order of certainty:
 *  1. no FPS source            -> UNKNOWN (decisions would be blind)
 *  2. OS thermal status        -> THERMAL (the SoC is already throttling)
 *  3. retransmissions / ping   -> NETWORK (frames wait on the wire, not the SoC)
 *  4. CPU saturation           -> CPU
 *  5. RAM pressure             -> MEMORY (LMK/swap storms)
 *  6. below target, nothing hot -> GPU/render-bound (by elimination)
 *  7. at/above target          -> NONE
 *
 * Pure on purpose: every threshold is unit-tested.
 */
object BottleneckDetector {

    const val PING_HIGH_MS = 120
    const val RETRANS_HIGH = 20
    const val CPU_SATURATED = 90
    const val RAM_PRESSURED = 92

    fun detect(m: FrameMetrics): Bottleneck {
        if (m.fps == null) return Bottleneck.UNKNOWN
        if (m.thermalStatus >= ThermalGuard.STATUS_MODERATE) return Bottleneck.THERMAL
        if ((m.pingMs ?: 0) > PING_HIGH_MS || (m.retransPerSec ?: 0) > RETRANS_HIGH) {
            return Bottleneck.NETWORK
        }
        if (m.cpuPct >= CPU_SATURATED) return Bottleneck.CPU
        if (m.ramPct >= RAM_PRESSURED) return Bottleneck.MEMORY
        return if (m.fps < m.targetFps) Bottleneck.GPU else Bottleneck.NONE
    }
}
