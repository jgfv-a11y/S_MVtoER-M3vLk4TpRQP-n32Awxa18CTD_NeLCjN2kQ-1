package com.nitroboost.app.core

/**
 * Thermal safety outranks frame rate.
 *
 * Decides which modules must be de-escalated for a given thermal status so
 * the booster can never cook the device: at moderate heat we drop GPU boost,
 * at severe heat the CPU governor too, at critical heat everything aggressive.
 *
 * Status values mirror android.os.PowerManager.THERMAL_STATUS_* (0..6).
 */
object ThermalGuard {

    const val STATUS_NOMINAL = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4
    const val STATUS_EMERGENCY = 5
    const val STATUS_SHUTDOWN = 6

    /** Returns the modules that should be reverted for the given thermal status. */
    fun modulesToDrop(thermalStatus: Int): Set<Module> = when (thermalStatus) {
        STATUS_CRITICAL, STATUS_EMERGENCY, STATUS_SHUTDOWN ->
            setOf(Module.CPU, Module.GPU, Module.THERMAL)

        STATUS_SEVERE ->
            setOf(Module.GPU, Module.THERMAL)

        STATUS_MODERATE ->
            setOf(Module.GPU)

        else -> emptySet()
    }
}
