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

    /**
     * Raw-temperature hard floor (Celsius), sampled from the thermal zones.
     *
     * Why: the opt-in thermal override makes the OS REPORT
     * THERMAL_STATUS_NONE, which blinds any guard that only reads
     * PowerManager status. The thermistors cannot be overridden, so the
     * watch also maps raw temperature to a status and takes the MAX of
     * both — a heat limit the UI, profiles and the override itself can
     * never disable.
     *
     * 44°C ≈ comfortable skin-heat ceiling (most OEMs throttle around
     * here); 48/52°C are the severe/critical floors.
     */
    const val RAW_MODERATE_C = 44.0
    const val RAW_SEVERE_C = 48.0
    const val RAW_CRITICAL_C = 52.0

    /** Pure mapping: raw max-zone temperature -> guard status. */
    fun rawStatusFor(tempC: Double?): Int = when {
        tempC == null -> STATUS_NOMINAL
        tempC >= RAW_CRITICAL_C -> STATUS_CRITICAL
        tempC >= RAW_SEVERE_C -> STATUS_SEVERE
        tempC >= RAW_MODERATE_C -> STATUS_MODERATE
        else -> STATUS_NOMINAL
    }

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
