package com.nitroboost.app.core.telemetry

/** State of an optional read-only sensor source; values remain null when absent. */
enum class SensorAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED,
    RESTRICTED,
    ERROR
}

enum class MemoryPressure {
    NORMAL,
    LOW
}

data class CpuSnapshot(
    val utilizationPct: Double? = null,
    val perCoreUtilizationPct: List<Double>? = null,
    /** Mean of the readable cpufreq policy readings; not a per-core guarantee. */
    val meanPolicyFrequencyKHz: Long? = null,
    val frequencyAvailability: SensorAvailability = SensorAvailability.UNAVAILABLE
)

data class GpuSnapshot(
    val utilizationPct: Double? = null,
    val frequencyKHz: Long? = null,
    val model: String? = null,
    val renderer: String? = null,
    val utilizationAvailability: SensorAvailability = SensorAvailability.UNAVAILABLE,
    val frequencyAvailability: SensorAvailability = SensorAvailability.UNAVAILABLE,
    val reason: String? = null
)

data class MemorySnapshot(
    val usedBytes: Long? = null,
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val thresholdBytes: Long? = null,
    /** Android's system-wide low-memory flag; not a per-game process metric. */
    val pressure: MemoryPressure? = null
) {
    val usedPct: Double?
        get() {
            val used = usedBytes ?: return null
            val total = totalBytes ?: return null
            if (total <= 0L || used !in 0L..total) return null
            return used * 100.0 / total
        }
}

data class ThermalSnapshot(
    /** Android PowerManager status; null when the API/source is unavailable. */
    val osStatus: Int? = null,
    /** Highest readable thermal-zone temperature, if the device exposes one. */
    val temperatureC: Double? = null,
    /** Filled only after at least two real temperature observations. */
    val slopeCPerMin: Double? = null,
    /** OS status combined with the existing non-disableable safety policy. */
    val effectiveStatus: Int? = null
)

data class BatterySnapshot(
    val levelPct: Double? = null,
    val charging: Boolean? = null,
    val temperatureC: Double? = null,
    /** BatteryManager current-now reading converted from microamps to milliamps. */
    val currentMilliAmps: Double? = null,
    /** Null unless a trustworthy charge/energy source is explicitly wired. */
    val energyMah: Double? = null
)

data class NetworkSnapshot(
    /** TCP connect latency from the existing optional probe. */
    val latencyMs: Double? = null,
    val retransmissionsPerSec: Double? = null
)

data class FrameSnapshot(
    /** FPS counter observation only; never back-calculated from frametime samples. */
    val fps: Double? = null,
    val targetFps: Int? = null,
    /** `FrameCompleted - IntendedVsync` measurements from gfxinfo. */
    val frameTimesMs: List<Double>? = null,
    /** Deltas between consecutive measured IntendedVsync timestamps. */
    val intendedVsyncIntervalsMs: List<Double>? = null,
    val renderer: String? = null
)

data class DeviceSnapshot(
    val manufacturer: String? = null,
    val model: String? = null,
    val socModel: String? = null,
    val androidApi: Int? = null,
    val androidRelease: String? = null,
    val gpuModel: String? = null,
    val renderer: String? = null,
    val displayWidthPx: Int? = null,
    val displayHeightPx: Int? = null,
    val refreshRateHz: Double? = null
)

/** Per-source observation times; cached values retain their original sample time. */
data class TelemetrySourceTimestamps(
    val cpuAtMs: Long? = null,
    val cpuFrequencyAtMs: Long? = null,
    val gpuAtMs: Long? = null,
    val memoryAtMs: Long? = null,
    val thermalStatusAtMs: Long? = null,
    val temperatureAtMs: Long? = null,
    val batteryAtMs: Long? = null,
    val networkLatencyAtMs: Long? = null,
    val networkRetransmissionsAtMs: Long? = null,
    val frameAtMs: Long? = null,
    val deviceAtMs: Long? = null
)

/** One local view of available measurements, with capture and per-source times. */
data class PerformanceSnapshot(
    /** Time the independent source values were fused into this object. */
    val capturedAtMs: Long = 0L,
    val cpu: CpuSnapshot = CpuSnapshot(),
    val gpu: GpuSnapshot = GpuSnapshot(),
    val memory: MemorySnapshot = MemorySnapshot(),
    val thermal: ThermalSnapshot = ThermalSnapshot(),
    val battery: BatterySnapshot = BatterySnapshot(),
    val network: NetworkSnapshot = NetworkSnapshot(),
    val frame: FrameSnapshot = FrameSnapshot(),
    val device: DeviceSnapshot = DeviceSnapshot(),
    /** Actual observation times; cached readings do not masquerade as fresh. */
    val sourceTimestamps: TelemetrySourceTimestamps = TelemetrySourceTimestamps()
)

/**
 * Windowed frame-pacing diagnostics derived only from measured FPS/frame data.
 * `estimatedDroppedFrameRate` is a missed-vsync-slot estimate from measured
 * IntendedVsync gaps and the configured target, not a platform drop counter.
 */
data class FramePacingMetrics(
    val targetFps: Int?,
    val averageFps: Double?,
    val medianFps: Double?,
    val meanFrameTimeMs: Double?,
    val medianFrameTimeMs: Double?,
    val p95FrameTimeMs: Double?,
    val p99FrameTimeMs: Double?,
    val frameTimeVarianceMs2: Double?,
    /** Same explicit >2x target-budget definition as FrameTimeAnalysis hitches. */
    val jankRate: Double?,
    val estimatedDroppedFrameRate: Double?,
    /** Deterministic 0..100 diagnostics; null until enough measured frame data exists. */
    val stabilityScore: Double?,
    val smoothnessScore: Double?,
    val fpsSampleCount: Int,
    val frameSampleCount: Int,
    val intendedVsyncIntervalCount: Int
)
