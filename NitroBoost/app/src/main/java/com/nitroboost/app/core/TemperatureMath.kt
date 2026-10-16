package com.nitroboost.app.core

/** Pure normalization for the raw formats exposed by Android thermal zones. */
object TemperatureMath {

    /**
     * Converts a thermal-zone value to Celsius.
     *
     * Android kernels normally expose millidegrees (for example `44500`),
     * while some OEM kernels expose Celsius directly (`44` or `44.5`).
     * Unknown, non-finite, and physically implausible values remain null so
     * callers never turn a broken sensor into a trusted measurement.
     */
    fun normalizeThermalZoneCelsius(raw: String): Double? {
        val parsed = raw.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return null
        val celsius = if (kotlin.math.abs(parsed) >= MILLIDEGREE_THRESHOLD) {
            parsed / 1_000.0
        } else {
            parsed
        }
        return celsius.takeIf { it in MIN_SENSOR_C..MAX_SENSOR_C }
    }

    private const val MILLIDEGREE_THRESHOLD = 1_000.0
    private const val MIN_SENSOR_C = -40.0
    private const val MAX_SENSOR_C = 150.0
}
