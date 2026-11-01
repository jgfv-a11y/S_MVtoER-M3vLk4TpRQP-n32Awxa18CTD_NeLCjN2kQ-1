package com.nitroboost.app.core

/** UI-safe classification for a nullable measurement and its source timestamp. */
enum class MeasurementState {
    MEASURED,
    STALE,
    UNAVAILABLE
}

data class MeasurementProvenance(
    val state: MeasurementState,
    val source: String,
    val observedAtMs: Long?,
    val ageMs: Long?
)

object MeasurementProvenanceEvaluator {
    fun evaluate(
        available: Boolean,
        source: String,
        observedAtMs: Long?,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): MeasurementProvenance {
        if (!available || observedAtMs == null || observedAtMs <= 0L) {
            return MeasurementProvenance(MeasurementState.UNAVAILABLE, source, observedAtMs, null)
        }
        val age = (nowMs - observedAtMs).coerceAtLeast(0L)
        return MeasurementProvenance(
            state = if (age <= maxAgeMs) MeasurementState.MEASURED else MeasurementState.STALE,
            source = source,
            observedAtMs = observedAtMs,
            ageMs = age
        )
    }

    const val DEFAULT_MAX_AGE_MS = 5_000L
}
