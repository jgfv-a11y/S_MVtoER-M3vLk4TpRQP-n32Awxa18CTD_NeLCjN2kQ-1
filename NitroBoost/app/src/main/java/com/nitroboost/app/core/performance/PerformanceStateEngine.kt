package com.nitroboost.app.core.performance

import com.nitroboost.app.core.ThermalGuard
import com.nitroboost.app.core.adaptive.FramePacingAnalyzer
import com.nitroboost.app.core.telemetry.FramePacingMetrics
import com.nitroboost.app.core.telemetry.FrameSnapshot
import com.nitroboost.app.core.telemetry.PerformanceSnapshot
import com.nitroboost.app.core.telemetry.SensorAvailability
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A single, explainable classification. It never drives system mutations. */
enum class PerformanceState {
    CPU_BOUND,
    GPU_BOUND,
    MEMORY_BOUND,
    THERMAL_BOUND,
    NETWORK_BOUND,
    DISPLAY_BOUND,
    MIXED_BOUND,
    HEALTHY,
    UNKNOWN
}

enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

data class Confidence(
    /** A calibrated support score in [0, 1], not a statistical probability. */
    val score: Double,
    val level: ConfidenceLevel
)

enum class EvidenceDomain { CPU, GPU, MEMORY, THERMAL, NETWORK, DISPLAY, FRAME_PACING }
enum class EvidenceScope {
    SYSTEM_WIDE,
    GPU_TELEMETRY,
    THERMAL_ZONE,
    SYSTEM_NETWORK,
    TCP_PROBE,
    DISPLAY_MODE,
    GFXINFO
}
enum class TelemetrySource { CPU, GPU, MEMORY, THERMAL, NETWORK, FRAME, DISPLAY }
enum class DataQualityLevel { POOR, PARTIAL, GOOD }

data class PerformanceEvidence(
    /** Stable machine-readable reason identifier for tests, logs, and UI mapping. */
    val code: String,
    val domain: EvidenceDomain,
    val observedValue: Double?,
    val unit: String?,
    val comparisonValue: Double? = null,
    val sampleCount: Int = 1,
    val sourceTimestampMs: Long? = null,
    val scope: EvidenceScope,
    /** False for contextual measurements that did not support the classification. */
    val supportsClassification: Boolean = true
)

data class SourceQuality(
    val source: TelemetrySource,
    val fresh: Boolean,
    val ageMs: Long?,
    val sampleCount: Int,
    val availability: SensorAvailability? = null,
    val reason: String? = null
)

data class DataQuality(
    /** Weighted source coverage and temporal sample adequacy in [0, 1]. */
    val score: Double,
    val level: DataQualityLevel,
    val sources: List<SourceQuality>,
    val warnings: List<String>,
    val observationCount: Int,
    val frameSampleCount: Int,
    val networkProbeCount: Int
)

data class PerformancePressures(
    val cpu: Double? = null,
    val gpu: Double? = null,
    val memory: Double? = null,
    val thermal: Double? = null,
    val network: Double? = null,
    val display: Double? = null,
    val framePacing: Double? = null,
    val frameRateDeficit: Double? = null
)

data class NetworkDiagnostics(
    val tcpProbeMedianLatencyMs: Double? = null,
    /** Variability across TCP connect probes, not packet-level game jitter. */
    val tcpProbeVariabilityMs: Double? = null,
    /** Null: the current platform sampler has no packet-loss counter/denominator. */
    val packetLossPct: Double? = null,
    /** System-wide TCP retransmission rate; it is not a packet-loss percentage. */
    val systemRetransmissionsPerSec: Double? = null,
    val tcpProbeSampleCount: Int = 0
)

data class PerformanceStateResult(
    val state: PerformanceState,
    val confidence: Confidence,
    val evidence: List<PerformanceEvidence>,
    /** Same monotonic time domain as MonitorSnapshot/elapsedRealtime. */
    val timestampMs: Long,
    val dataQuality: DataQuality,
    val pressures: PerformancePressures,
    val network: NetworkDiagnostics,
    val framePacing: FramePacingMetrics?
)

/**
 * Pure-Kotlin, bounded-window performance classifier over existing telemetry.
 *
 * It does not infer GPU load from FPS, does not call TCP retransmissions packet
 * loss, and does not treat the external TCP probe as the game's network path.
 * Aggregated CPU/memory and thermal readings retain their system/device scope.
 * The result is diagnostic only and must not be used as a boost command.
 */
class PerformanceStateEngine(
    private val config: Config = Config()
) {
    data class Config(
        val historyWindowMs: Long = 90_000L,
        val metricWindowMs: Long = 20_000L,
        val maxHistorySnapshots: Int = 96,
        val cpuFreshMs: Long = 3_000L,
        val memoryFreshMs: Long = 3_000L,
        val thermalFreshMs: Long = 12_000L,
        val networkFreshMs: Long = 30_000L,
        val frameFreshMs: Long = 6_000L,
        val displayFreshMs: Long = 15_000L,
        val minimumCpuSamples: Int = 3,
        val minimumGpuSamples: Int = 2,
        val minimumFrameSamples: Int = FramePacingAnalyzer.MIN_SCORE_FRAME_SAMPLES,
        val minimumNetworkProbes: Int = 3
    ) {
        init {
            require(historyWindowMs > 0L)
            require(metricWindowMs > 0L && metricWindowMs <= historyWindowMs)
            require(maxHistorySnapshots > 0)
            require(cpuFreshMs > 0L && memoryFreshMs > 0L && thermalFreshMs > 0L)
            require(networkFreshMs > 0L && frameFreshMs > 0L && displayFreshMs > 0L)
            require(minimumCpuSamples > 0 && minimumGpuSamples > 0)
            require(minimumFrameSamples > 0 && minimumNetworkProbes > 0)
        }
    }

    private data class ContextKey(val gamePackage: String?, val processEpoch: Long)
    private data class Observation<T>(val atMs: Long, val value: T)
    private data class CpuLoad(val aggregatePct: Double?, val perCorePct: List<Double>?)
    private data class MemoryLoad(
        val usedPct: Double?,
        val availableBytes: Long?,
        val thresholdBytes: Long?,
        val pressure: com.nitroboost.app.core.telemetry.MemoryPressure?
    )
    private data class NetworkLoad(val latencyMs: Double?, val retransmissionsPerSec: Double?)
    private data class Candidate(
        val state: PerformanceState,
        val strength: Double,
        val confidence: Confidence
    )

    private val history = ArrayList<PerformanceSnapshot>()
    private var contextKey: ContextKey? = null
    private var lastCaptureMs: Long = 0L

    /**
     * Analyze a fused snapshot. [gamePackage]/[processEpoch] fence history so
     * samples from another game process are never combined with this result.
     * Passing null yields UNKNOWN while retaining only bounded recent history.
     */
    @Synchronized
    fun analyze(
        snapshot: PerformanceSnapshot?,
        timestampMs: Long = snapshot?.capturedAtMs ?: 0L,
        gamePackage: String? = null,
        processEpoch: Long = 0L
    ): PerformanceStateResult {
        val now = timestampMs.takeIf { it > 0L } ?: snapshot?.capturedAtMs?.takeIf { it > 0L } ?: 0L
        val nextContext = ContextKey(gamePackage, processEpoch)
        if (contextKey != nextContext || (lastCaptureMs > 0L && now > 0L && now < lastCaptureMs)) {
            history.clear()
        }
        contextKey = nextContext

        if (snapshot != null && now > 0L && snapshot.capturedAtMs in 1L..now) {
            if (history.lastOrNull()?.capturedAtMs == snapshot.capturedAtMs) {
                history.removeAt(history.lastIndex)
            }
            history.add(snapshot)
            lastCaptureMs = snapshot.capturedAtMs
            while (history.isNotEmpty() && now - history.first().capturedAtMs > config.historyWindowMs) {
                history.removeAt(0)
            }
            while (history.size > config.maxHistorySnapshots) history.removeAt(0)
        } else if (now > 0L) {
            lastCaptureMs = now
        }

        return classify(snapshot, now, history.toList())
    }

    @Synchronized
    fun reset() {
        history.clear()
        contextKey = null
        lastCaptureMs = 0L
    }

    private fun classify(
        current: PerformanceSnapshot?,
        now: Long,
        snapshots: List<PerformanceSnapshot>
    ): PerformanceStateResult {
        val evidence = ArrayList<PerformanceEvidence>()
        val warnings = linkedSetOf<String>()
        val safeNow = now.coerceAtLeast(0L)
        val sampleCount = snapshots.size
        if (current == null) warnings += "CURRENT_SNAPSHOT_MISSING"

        val cpuObservations = observations(
            snapshots,
            safeNow,
            config.metricWindowMs,
            { it.sourceTimestamps.cpuAtMs },
            { snapshot ->
                val aggregate = snapshot.cpu.utilizationPct.validPercent()
                val cores = snapshot.cpu.perCoreUtilizationPct
                    ?.mapNotNull { it.validPercent() }
                    ?.takeIf { it.isNotEmpty() }
                CpuLoad(aggregate, cores).takeIf { aggregate != null || cores != null }
            }
        )
        val gpuObservations = observations(
            snapshots,
            safeNow,
            config.metricWindowMs,
            { it.sourceTimestamps.gpuAtMs },
            { it.gpu.utilizationPct.validPercent() }
        )
        val memoryObservations = observations(
            snapshots,
            safeNow,
            config.metricWindowMs,
            { it.sourceTimestamps.memoryAtMs },
            { snapshot ->
                val memory = snapshot.memory
                val totalBytes = memory.totalBytes?.takeIf { it > 0L }
                MemoryLoad(
                    usedPct = memory.usedPct.validPercent(),
                    availableBytes = memory.availableBytes?.takeIf {
                        it >= 0L && (totalBytes == null || it <= totalBytes)
                    },
                    thresholdBytes = memory.thresholdBytes?.takeIf {
                        it > 0L && (totalBytes == null || it <= totalBytes)
                    },
                    pressure = memory.pressure
                ).takeIf {
                    it.usedPct != null || it.availableBytes != null || it.thresholdBytes != null || it.pressure != null
                }
            }
        )
        val thermalStatusObservations = observations(
            snapshots,
            safeNow,
            config.thermalFreshMs,
            { it.sourceTimestamps.thermalStatusAtMs },
            { snapshot ->
                (snapshot.thermal.effectiveStatus ?: snapshot.thermal.osStatus)
                    ?.takeIf { it in 0..6 }
            }
        )
        val thermalTemperatureObservations = observations(
            snapshots,
            safeNow,
            config.thermalFreshMs,
            { it.sourceTimestamps.temperatureAtMs },
            { it.thermal.temperatureC?.takeIf { value -> value.isFinite() && value in -40.0..200.0 } }
        )
        // ThermalTrend is derived from the same raw temperature observations.
        val thermalSlopeObservations = observations(
            snapshots,
            safeNow,
            config.thermalFreshMs,
            { it.sourceTimestamps.temperatureAtMs },
            { it.thermal.slopeCPerMin?.takeIf { value -> value.isFinite() && value in -100.0..100.0 } }
        )
        val networkLatencyObservations = observations(
            snapshots,
            safeNow,
            config.networkFreshMs,
            { it.sourceTimestamps.networkLatencyAtMs },
            { it.network.latencyMs?.takeIf { value -> value.isFinite() && value in 0.0..MAX_LATENCY_MS } }
        )
        val retransmissionObservations = observations(
            snapshots,
            safeNow,
            config.metricWindowMs,
            { it.sourceTimestamps.networkRetransmissionsAtMs },
            { it.network.retransmissionsPerSec?.takeIf { value -> value.isFinite() && value in 0.0..MAX_RETRANSMISSIONS_PER_SEC } }
        )
        val frameObservations = observations(
            snapshots,
            safeNow,
            config.metricWindowMs,
            { it.sourceTimestamps.frameAtMs },
            { snapshot ->
                val frame = snapshot.frame
                FrameSnapshot(
                    fps = frame.fps?.takeIf { it.isFinite() && it in 0.0..MAX_FPS.toDouble() && it > 0.0 },
                    targetFps = frame.targetFps?.takeIf { it in 1..MAX_FPS.toInt() },
                    frameTimesMs = frame.frameTimesMs?.filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }
                        ?.takeIf { it.isNotEmpty() },
                    intendedVsyncIntervalsMs = frame.intendedVsyncIntervalsMs
                        ?.filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }
                        ?.takeIf { it.isNotEmpty() },
                    renderer = frame.renderer
                ).takeIf {
                    it.fps != null || it.frameTimesMs != null || it.intendedVsyncIntervalsMs != null
                }
            }
        )
        val displayObservations = observations(
            snapshots,
            safeNow,
            config.historyWindowMs,
            { it.sourceTimestamps.deviceAtMs },
            { snapshot ->
                snapshot.device.refreshRateHz
                    ?.takeIf { it.isFinite() && it in MIN_REFRESH_HZ..MAX_REFRESH_HZ }
            }
        )

        val cpuQuality = sourceQuality(
            TelemetrySource.CPU, cpuObservations, config.cpuFreshMs, safeNow,
            reason = "CPU_UTILIZATION_UNAVAILABLE"
        )
        val gpuAvailability = if (gpuObservations.isNotEmpty()) SensorAvailability.AVAILABLE
        else current?.gpu?.utilizationAvailability
        val gpuUnavailableReason = when (gpuAvailability) {
            SensorAvailability.UNSUPPORTED -> "GPU_UTILIZATION_UNSUPPORTED"
            SensorAvailability.RESTRICTED -> "GPU_UTILIZATION_RESTRICTED"
            SensorAvailability.ERROR -> "GPU_TELEMETRY_ERROR"
            else -> "GPU_UTILIZATION_UNAVAILABLE"
        }
        val gpuQuality = sourceQuality(
            TelemetrySource.GPU, gpuObservations, config.cpuFreshMs, safeNow,
            availability = gpuAvailability,
            reason = gpuUnavailableReason
        )
        val memoryQuality = sourceQuality(
            TelemetrySource.MEMORY, memoryObservations, config.memoryFreshMs, safeNow,
            reason = "SYSTEM_MEMORY_PRESSURE_UNAVAILABLE"
        )
        val thermalQuality = combinedSourceQuality(
            TelemetrySource.THERMAL,
            thermalStatusObservations.map { it.atMs } + thermalTemperatureObservations.map { it.atMs },
            config.thermalFreshMs,
            safeNow,
            reason = "THERMAL_TELEMETRY_UNAVAILABLE"
        )
        val networkQuality = combinedSourceQuality(
            TelemetrySource.NETWORK,
            networkLatencyObservations.map { it.atMs } + retransmissionObservations.map { it.atMs },
            config.networkFreshMs,
            safeNow,
            reason = "NETWORK_TELEMETRY_UNAVAILABLE"
        )
        val frameQuality = sourceQuality(
            TelemetrySource.FRAME, frameObservations, config.frameFreshMs, safeNow,
            reason = "FRAME_TELEMETRY_UNAVAILABLE"
        )
        val displayQuality = sourceQuality(
            TelemetrySource.DISPLAY, displayObservations, config.displayFreshMs, safeNow,
            reason = "DISPLAY_REFRESH_UNAVAILABLE"
        )

        val cpuValues = cpuObservations.mapNotNull { it.value.aggregatePct }
        val perCoreValues = cpuObservations.flatMap { it.value.perCorePct.orEmpty() }
        val cpuAggregateP90 = percentile(cpuValues, 0.90)
        val cpuPerCoreP90 = percentile(perCoreValues, 0.90)
        val cpuPressure = listOfNotNull(
            cpuAggregateP90?.let { pressureRamp(it, CPU_BASELINE_PCT, CPU_ELEVATED_PCT, CPU_CRITICAL_PCT) },
            cpuPerCoreP90?.let {
                pressureRamp(it, CORE_BASELINE_PCT, CORE_ELEVATED_PCT, CORE_CRITICAL_PCT) * CORE_PRESSURE_WEIGHT
            }
        ).maxOrNull()
        val cpuSourceAt = cpuObservations.lastOrNull()?.atMs
        cpuAggregateP90?.let {
            evidence += PerformanceEvidence(
                code = "CPU_SYSTEM_UTILIZATION_P90",
                domain = EvidenceDomain.CPU,
                observedValue = it,
                unit = "%",
                comparisonValue = CPU_ELEVATED_PCT,
                sampleCount = cpuValues.size,
                sourceTimestampMs = cpuSourceAt,
                scope = EvidenceScope.SYSTEM_WIDE,
                supportsClassification = it >= CPU_BASELINE_PCT
            )
        }
        cpuPerCoreP90?.let {
            evidence += PerformanceEvidence(
                code = "CPU_PER_CORE_UTILIZATION_P90",
                domain = EvidenceDomain.CPU,
                observedValue = it,
                unit = "%",
                comparisonValue = CORE_ELEVATED_PCT,
                sampleCount = perCoreValues.size,
                sourceTimestampMs = cpuSourceAt,
                scope = EvidenceScope.SYSTEM_WIDE,
                supportsClassification = it >= CORE_BASELINE_PCT
            )
        }
        current?.let { snapshot ->
            snapshot.cpu.meanPolicyFrequencyKHz?.let { frequency ->
                evidence += PerformanceEvidence(
                    code = "CPU_POLICY_FREQUENCY",
                    domain = EvidenceDomain.CPU,
                    observedValue = frequency.toDouble(),
                    unit = "kHz",
                    sampleCount = 1,
                    sourceTimestampMs = snapshot.sourceTimestamps.cpuFrequencyAtMs,
                    scope = EvidenceScope.SYSTEM_WIDE,
                    supportsClassification = false
                )
            }
        }

        val gpuValues = gpuObservations.map { it.value }
        val gpuMedian = median(gpuValues)
        val gpuPressure = gpuMedian?.let {
            pressureRamp(it, GPU_BASELINE_PCT, GPU_ELEVATED_PCT, GPU_CRITICAL_PCT)
        }
        gpuMedian?.let {
            evidence += PerformanceEvidence(
                code = "GPU_UTILIZATION_MEDIAN",
                domain = EvidenceDomain.GPU,
                observedValue = it,
                unit = "%",
                comparisonValue = GPU_ELEVATED_PCT,
                sampleCount = gpuValues.size,
                sourceTimestampMs = gpuObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.GPU_TELEMETRY,
                supportsClassification = it >= GPU_BASELINE_PCT
            )
        }
        if (gpuObservations.isEmpty()) warnings += gpuQuality.reason ?: "GPU_UTILIZATION_UNAVAILABLE"

        val memoryUsedValues = memoryObservations.mapNotNull { it.value.usedPct }
        val memoryUsedP90 = percentile(memoryUsedValues, 0.90)
        val latestMemoryObservation = memoryObservations.lastOrNull()
        val lowMemorySeen = latestMemoryObservation?.value?.pressure ==
            com.nitroboost.app.core.telemetry.MemoryPressure.LOW
        val thresholdObservation = latestMemoryObservation?.takeIf { observation ->
            val available = observation.value.availableBytes
            val threshold = observation.value.thresholdBytes
            available != null && threshold != null && threshold > 0L && available <= threshold
        }
        val thresholdCrossing = thresholdObservation != null
        val usedMemoryPressure = memoryUsedP90?.let {
            pressureRamp(it, MEMORY_BASELINE_PCT, MEMORY_ELEVATED_PCT, MEMORY_CRITICAL_PCT)
        }
        val memoryPressure = when {
            lowMemorySeen -> max(MEMORY_PRESSURE_SIGNAL, usedMemoryPressure ?: 0.0)
            thresholdCrossing -> max(MEMORY_THRESHOLD_SIGNAL, usedMemoryPressure ?: 0.0)
            usedMemoryPressure != null -> usedMemoryPressure * MEMORY_USAGE_ONLY_WEIGHT
            else -> null
        }
        memoryUsedP90?.let {
            evidence += PerformanceEvidence(
                code = "SYSTEM_MEMORY_USED_P90",
                domain = EvidenceDomain.MEMORY,
                observedValue = it,
                unit = "%",
                comparisonValue = MEMORY_ELEVATED_PCT,
                sampleCount = memoryUsedValues.size,
                sourceTimestampMs = memoryObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.SYSTEM_WIDE,
                supportsClassification = lowMemorySeen || thresholdCrossing
            )
        }
        if (lowMemorySeen) {
            evidence += PerformanceEvidence(
                code = "ANDROID_LOW_MEMORY_FLAG",
                domain = EvidenceDomain.MEMORY,
                observedValue = 1.0,
                unit = "flag",
                sampleCount = memoryObservations.count {
                    it.value.pressure == com.nitroboost.app.core.telemetry.MemoryPressure.LOW
                },
                sourceTimestampMs = memoryObservations.lastOrNull {
                    it.value.pressure == com.nitroboost.app.core.telemetry.MemoryPressure.LOW
                }?.atMs,
                scope = EvidenceScope.SYSTEM_WIDE
            )
        }
        if (thresholdCrossing) {
            val crossing = thresholdObservation
            evidence += PerformanceEvidence(
                code = "AVAILABLE_MEMORY_AT_SYSTEM_THRESHOLD",
                domain = EvidenceDomain.MEMORY,
                observedValue = crossing?.value?.availableBytes?.toDouble(),
                unit = "bytes",
                comparisonValue = crossing?.value?.thresholdBytes?.toDouble(),
                sampleCount = memoryObservations.size,
                sourceTimestampMs = crossing?.atMs,
                scope = EvidenceScope.SYSTEM_WIDE
            )
        }

        val thermalStatusForPressure = thermalStatusObservations.lastOrNull()?.value
        val thermalStatusAt = thermalStatusObservations.lastOrNull()?.atMs
        val maxTemperatureObservation = thermalTemperatureObservations.maxByOrNull { it.value }
        val maxTemperature = maxTemperatureObservation?.value
        val latestSlopeObservation = thermalSlopeObservations.lastOrNull()
        val latestSlope = latestSlopeObservation?.value
        val statusPressure = thermalStatusForPressure?.let(::thermalStatusPressure)
        val temperaturePressure = maxTemperature?.let { temperaturePressure(it) }
        val slopePressure = latestSlope?.let { slopePressure(it) }
        val thermalPressure = listOfNotNull(
            statusPressure,
            temperaturePressure,
            slopePressure
        ).maxOrNull()
        thermalStatusForPressure?.let {
            evidence += PerformanceEvidence(
                code = "EFFECTIVE_THERMAL_STATUS",
                domain = EvidenceDomain.THERMAL,
                observedValue = it.toDouble(),
                unit = "status",
                comparisonValue = ThermalGuard.STATUS_MODERATE.toDouble(),
                sampleCount = thermalStatusObservations.size,
                sourceTimestampMs = thermalStatusAt,
                scope = EvidenceScope.SYSTEM_WIDE,
                supportsClassification = it >= ThermalGuard.STATUS_LIGHT
            )
        }
        maxTemperature?.let {
            evidence += PerformanceEvidence(
                code = "HIGHEST_READABLE_THERMAL_ZONE_C",
                domain = EvidenceDomain.THERMAL,
                observedValue = it,
                unit = "°C",
                comparisonValue = ThermalGuard.RAW_MODERATE_C,
                sampleCount = thermalTemperatureObservations.size,
                sourceTimestampMs = maxTemperatureObservation?.atMs,
                scope = EvidenceScope.THERMAL_ZONE,
                supportsClassification = it >= THERMAL_ELEVATED_C
            )
        }
        latestSlope?.let {
            evidence += PerformanceEvidence(
                code = "THERMAL_SLOPE_C_PER_MIN",
                domain = EvidenceDomain.THERMAL,
                observedValue = it,
                unit = "°C/min",
                comparisonValue = ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN,
                sampleCount = thermalSlopeObservations.size,
                sourceTimestampMs = latestSlopeObservation?.atMs,
                scope = EvidenceScope.THERMAL_ZONE,
                supportsClassification = it >= ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN
            )
        }

        val latencyValues = networkLatencyObservations.map { it.value }
        val tcpMedianLatency = median(latencyValues)
        val tcpVariability = consecutiveMedianDelta(latencyValues)
        val retransValues = retransmissionObservations.map { it.value }
        val retransMedian = median(retransValues)
        val networkComponents = buildList {
            tcpMedianLatency?.let {
                add(pressureRamp(it, NETWORK_LATENCY_BASELINE_MS, NETWORK_LATENCY_ELEVATED_MS,
                    NETWORK_LATENCY_CRITICAL_MS) to NETWORK_LATENCY_WEIGHT)
            }
            tcpVariability?.takeIf { latencyValues.size >= config.minimumNetworkProbes }?.let {
                add(pressureRamp(it, NETWORK_VARIABILITY_BASELINE_MS, NETWORK_VARIABILITY_ELEVATED_MS,
                    NETWORK_VARIABILITY_CRITICAL_MS) to NETWORK_VARIABILITY_WEIGHT)
            }
            retransMedian?.let {
                add(pressureRamp(it, NETWORK_RETRANS_BASELINE_PER_SEC, NETWORK_RETRANS_ELEVATED_PER_SEC,
                    NETWORK_RETRANS_CRITICAL_PER_SEC) to NETWORK_RETRANS_WEIGHT)
            }
        }
        val networkPressure = weightedMean(networkComponents)
        val networkDiagnostics = NetworkDiagnostics(
            tcpProbeMedianLatencyMs = tcpMedianLatency,
            tcpProbeVariabilityMs = tcpVariability.takeIf {
                latencyValues.size >= config.minimumNetworkProbes
            },
            packetLossPct = null,
            systemRetransmissionsPerSec = retransMedian,
            tcpProbeSampleCount = latencyValues.size
        )
        if (tcpMedianLatency != null) {
            evidence += PerformanceEvidence(
                code = "TCP_PROBE_MEDIAN_CONNECT_LATENCY",
                domain = EvidenceDomain.NETWORK,
                observedValue = tcpMedianLatency,
                unit = "ms",
                comparisonValue = NETWORK_LATENCY_ELEVATED_MS,
                sampleCount = latencyValues.size,
                sourceTimestampMs = networkLatencyObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.TCP_PROBE,
                supportsClassification = tcpMedianLatency >= NETWORK_LATENCY_ELEVATED_MS
            )
            warnings += "TCP_PROBE_IS_NOT_THE_GAME_NETWORK_PATH"
        }
        if (tcpVariability != null && latencyValues.size >= config.minimumNetworkProbes) {
            evidence += PerformanceEvidence(
                code = "TCP_PROBE_LATENCY_VARIABILITY",
                domain = EvidenceDomain.NETWORK,
                observedValue = tcpVariability,
                unit = "ms",
                comparisonValue = NETWORK_VARIABILITY_ELEVATED_MS,
                sampleCount = latencyValues.size,
                sourceTimestampMs = networkLatencyObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.TCP_PROBE,
                supportsClassification = tcpVariability >= NETWORK_VARIABILITY_ELEVATED_MS
            )
        } else if (latencyValues.isNotEmpty()) {
            warnings += "NETWORK_VARIABILITY_NEEDS_MORE_DISTINCT_PROBES"
        }
        retransMedian?.let {
            evidence += PerformanceEvidence(
                code = "SYSTEM_TCP_RETRANSMISSIONS_PER_SEC",
                domain = EvidenceDomain.NETWORK,
                observedValue = it,
                unit = "segments/s",
                comparisonValue = NETWORK_RETRANS_ELEVATED_PER_SEC,
                sampleCount = retransValues.size,
                sourceTimestampMs = retransmissionObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.SYSTEM_NETWORK,
                supportsClassification = it >= NETWORK_RETRANS_ELEVATED_PER_SEC
            )
        }
        warnings += "PACKET_LOSS_NOT_MEASURED"
        if (latencyValues.isEmpty()) warnings += networkQuality.reason ?: "NETWORK_TELEMETRY_UNAVAILABLE"

        val frameValues = frameObservations.map { it.value }
        val targetFps = frameValues.lastOrNull()?.targetFps?.takeIf { it in 1..MAX_FPS.toInt() }
        val framePacing = if (frameValues.isNotEmpty()) {
            FramePacingAnalyzer.analyze(frameValues, targetFps)
        } else null
        val fpsDeficit = framePacing?.let { metrics ->
            val target = metrics.targetFps ?: return@let null
            val measured = metrics.medianFps ?: return@let null
            ((target - measured) / target.toDouble()).coerceAtLeast(0.0)
        }
        val fpsDeficitPressure = fpsDeficit?.let {
            pressureRamp(it, FRAME_DEFICIT_BASELINE, FRAME_DEFICIT_ELEVATED, FRAME_DEFICIT_CRITICAL)
        }
        val framePacingPressure = framePacingPressure(framePacing, targetFps)
        val frameSymptoms = listOfNotNull(fpsDeficitPressure, framePacingPressure).maxOrNull()
        if (targetFps != null) warnings += "FPS_TARGET_IS_PROFILE_OR_SESSION_CONFIG"
        if (framePacing != null && framePacing.frameSampleCount > 0) {
            framePacing.p95FrameTimeMs?.let { p95 ->
                val budget = targetFps?.let { 1_000.0 / it }
                evidence += PerformanceEvidence(
                    code = "FRAME_TIME_P95",
                    domain = EvidenceDomain.FRAME_PACING,
                    observedValue = p95,
                    unit = "ms",
                    comparisonValue = budget,
                    sampleCount = framePacing.frameSampleCount,
                    sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                    scope = EvidenceScope.GFXINFO,
                    supportsClassification = budget != null && p95 > budget
                )
            }
            framePacing.jankRate?.let {
                evidence += PerformanceEvidence(
                    code = "MEASURED_GFXINFO_JANK_RATE",
                    domain = EvidenceDomain.FRAME_PACING,
                    observedValue = it * 100.0,
                    unit = "%",
                    sampleCount = framePacing.frameSampleCount,
                    sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                    scope = EvidenceScope.GFXINFO,
                    supportsClassification = it >= FRAME_JANK_ELEVATED
                )
            }
            framePacing.estimatedDroppedFrameRate?.let {
                evidence += PerformanceEvidence(
                    code = "ESTIMATED_MISSED_VSYNC_SLOT_RATE",
                    domain = EvidenceDomain.FRAME_PACING,
                    observedValue = it * 100.0,
                    unit = "% (estimated)",
                    sampleCount = framePacing.intendedVsyncIntervalCount,
                    sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                    scope = EvidenceScope.GFXINFO,
                    supportsClassification = it >= FRAME_DROP_ELEVATED
                )
            }
        }
        fpsDeficit?.let {
            evidence += PerformanceEvidence(
                code = "FPS_BELOW_CONFIGURED_TARGET",
                domain = EvidenceDomain.FRAME_PACING,
                observedValue = it * 100.0,
                unit = "% below target",
                comparisonValue = FRAME_DEFICIT_ELEVATED * 100.0,
                sampleCount = framePacing?.fpsSampleCount ?: 0,
                sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.GFXINFO,
                supportsClassification = it >= FRAME_DEFICIT_BASELINE
            )
        }
        if (frameObservations.isEmpty()) warnings += frameQuality.reason ?: "FRAME_TELEMETRY_UNAVAILABLE"

        val refreshHz = displayObservations.lastOrNull()?.value
            ?.takeIf { displayQuality.fresh }
        val outputFps = framePacing?.medianFps
        val measuredIntervals = frameValues.flatMap { it.intendedVsyncIntervalsMs.orEmpty() }
            .filter { it.isFinite() && it in MIN_FRAME_MS..MAX_FRAME_MS }
        val medianVsyncInterval = median(measuredIntervals)
        val cadenceNearRefresh = refreshHz?.let { hz ->
            val periodMs = 1_000.0 / hz
            val fpsNearPanel = outputFps?.let {
                abs(it - hz) <= max(DISPLAY_FPS_TOLERANCE, hz * DISPLAY_FPS_TOLERANCE_RATIO)
            } == true
            val intervalsNearPanel = medianVsyncInterval?.let {
                abs(it - periodMs) <= max(DISPLAY_INTERVAL_TOLERANCE_MS, periodMs * DISPLAY_INTERVAL_TOLERANCE_RATIO)
            } == true
            fpsNearPanel || intervalsNearPanel
        } ?: false
        val targetDisplayRatio = if (targetFps != null && refreshHz != null && refreshHz > 0.0) {
            targetFps / refreshHz
        } else null
        val displayPressure = when {
            targetDisplayRatio == null -> null
            targetDisplayRatio <= DISPLAY_TARGET_EXCESS_START -> 0.0
            cadenceNearRefresh -> pressureRamp(
                targetDisplayRatio,
                DISPLAY_TARGET_EXCESS_START,
                DISPLAY_TARGET_EXCESS_ELEVATED,
                DISPLAY_TARGET_EXCESS_CRITICAL
            )
            else -> pressureRamp(
                targetDisplayRatio,
                DISPLAY_TARGET_EXCESS_START,
                DISPLAY_TARGET_EXCESS_ELEVATED,
                DISPLAY_TARGET_EXCESS_CRITICAL
            ) * DISPLAY_UNCONFIRMED_TARGET_WEIGHT
        }
        if (refreshHz != null && targetDisplayRatio != null && targetDisplayRatio > DISPLAY_TARGET_EXCESS_START) {
            evidence += PerformanceEvidence(
                code = "CONFIGURED_TARGET_ABOVE_ACTIVE_REFRESH",
                domain = EvidenceDomain.DISPLAY,
                observedValue = targetFps?.toDouble(),
                unit = "FPS",
                comparisonValue = refreshHz,
                sampleCount = framePacing?.fpsSampleCount ?: 0,
                sourceTimestampMs = displayObservations.lastOrNull()?.atMs,
                scope = EvidenceScope.DISPLAY_MODE,
                supportsClassification = cadenceNearRefresh
            )
            if (cadenceNearRefresh) {
                evidence += PerformanceEvidence(
                    code = "MEASURED_FRAME_CADENCE_NEAR_ACTIVE_REFRESH",
                    domain = EvidenceDomain.DISPLAY,
                    observedValue = outputFps ?: medianVsyncInterval?.let { 1_000.0 / it },
                    unit = if (outputFps != null) "FPS" else "Hz from measured vsync interval",
                    comparisonValue = refreshHz,
                    sampleCount = if (outputFps != null) framePacing?.fpsSampleCount ?: 0
                    else measuredIntervals.size,
                    sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                    scope = EvidenceScope.GFXINFO
                )
            }
        }
        if (refreshHz == null) warnings += displayQuality.reason ?: "DISPLAY_REFRESH_UNAVAILABLE"

        val sourceQualities = listOf(
            cpuQuality,
            gpuQuality,
            memoryQuality,
            thermalQuality,
            networkQuality,
            frameQuality,
            displayQuality
        )
        sourceQualities.filterNot { it.fresh }.forEach { quality ->
            warnings.add(quality.reason ?: "${quality.source.name}_UNAVAILABLE")
        }
        val dataQuality = dataQuality(
            sources = sourceQualities,
            warnings = warnings,
            observationCount = sampleCount,
            frameSampleCount = framePacing?.frameSampleCount ?: 0,
            networkProbeCount = latencyValues.size
        )
        if (cpuQuality.fresh) warnings += "CPU_UTILIZATION_IS_SYSTEM_WIDE"
        if (memoryQuality.fresh) warnings += "MEMORY_METRICS_ARE_SYSTEM_WIDE"
        // Rebuild after adding the scope warnings so they are visible in the result.
        val finalDataQuality = dataQuality.copy(
            warnings = warnings.toList(),
            level = dataQualityLevel(dataQuality.score)
        )

        val pressures = PerformancePressures(
            cpu = cpuPressure,
            gpu = gpuPressure,
            memory = memoryPressure,
            thermal = thermalPressure,
            network = networkPressure,
            display = displayPressure,
            framePacing = framePacingPressure,
            frameRateDeficit = fpsDeficitPressure
        )

        val candidates = ArrayList<Candidate>()
        if (cpuQuality.fresh && frameQuality.fresh && cpuPressure != null && frameSymptoms != null &&
            frameSymptoms >= CPU_FRAME_SYMPTOM_MIN &&
            (cpuObservations.size >= config.minimumCpuSamples || cpuPressure >= CPU_EXTREME_PRESSURE) &&
            cpuPressure >= CPU_PRESSURE_MIN
        ) {
            val strength = CPU_PRESSURE_WEIGHT * cpuPressure + (1.0 - CPU_PRESSURE_WEIGHT) * frameSymptoms
            val countFactor = sampleFactor(cpuObservations.size, config.minimumCpuSamples)
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.CPU && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.CPU_BOUND,
                strength,
                candidateConfidence(strength, countFactor, evidenceCount, finalDataQuality.score,
                    CPU_CONFIDENCE_CAP, CPU_SCOPE_CONFIDENCE_FACTOR)
            )
        }
        if (gpuQuality.fresh && gpuPressure != null && frameSymptoms != null &&
            frameSymptoms >= GPU_FRAME_SYMPTOM_MIN && gpuObservations.size >= config.minimumGpuSamples &&
            gpuPressure >= GPU_PRESSURE_MIN
        ) {
            val strength = GPU_PRESSURE_WEIGHT * gpuPressure + (1.0 - GPU_PRESSURE_WEIGHT) * frameSymptoms
            val countFactor = sampleFactor(gpuObservations.size, config.minimumGpuSamples)
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.GPU && it.supportsClassification } +
                evidence.count { it.domain == EvidenceDomain.FRAME_PACING && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.GPU_BOUND,
                strength,
                candidateConfidence(strength, countFactor, evidenceCount, finalDataQuality.score,
                    GPU_CONFIDENCE_CAP, 1.0)
            )
        }
        if (memoryQuality.fresh && memoryPressure != null && (lowMemorySeen || thresholdCrossing) &&
            memoryPressure >= MEMORY_PRESSURE_MIN
        ) {
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.MEMORY && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.MEMORY_BOUND,
                memoryPressure,
                candidateConfidence(memoryPressure, sampleFactor(memoryObservations.size, 2), evidenceCount,
                    finalDataQuality.score, MEMORY_CONFIDENCE_CAP, MEMORY_SCOPE_CONFIDENCE_FACTOR)
            )
        }
        val thermalBound = thermalQuality.fresh && (
            (thermalStatusForPressure ?: ThermalGuard.STATUS_NOMINAL) >= ThermalGuard.STATUS_MODERATE ||
                (maxTemperature ?: Double.NEGATIVE_INFINITY) >= THERMAL_ELEVATED_C ||
                (latestSlope ?: Double.NEGATIVE_INFINITY) >= ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN
            )
        if (thermalBound && thermalPressure != null) {
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.THERMAL && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.THERMAL_BOUND,
                thermalPressure,
                candidateConfidence(thermalPressure, sampleFactor(thermalQuality.sampleCount, 2), evidenceCount,
                    finalDataQuality.score, THERMAL_CONFIDENCE_CAP, 1.0)
            )
        }
        val networkBound = networkQuality.fresh && latencyValues.size >= config.minimumNetworkProbes &&
            networkPressure != null && networkPressure >= NETWORK_PRESSURE_MIN &&
            ((tcpMedianLatency ?: 0.0) >= NETWORK_LATENCY_ELEVATED_MS ||
                (networkDiagnostics.tcpProbeVariabilityMs ?: 0.0) >= NETWORK_VARIABILITY_ELEVATED_MS)
        if (networkBound) {
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.NETWORK && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.NETWORK_BOUND,
                networkPressure ?: 0.0,
                candidateConfidence(networkPressure ?: 0.0,
                    sampleFactor(latencyValues.size, config.minimumNetworkProbes), evidenceCount,
                    finalDataQuality.score, NETWORK_CONFIDENCE_CAP, NETWORK_SCOPE_CONFIDENCE_FACTOR)
            )
        }
        val displayBound = displayQuality.fresh && targetDisplayRatio != null &&
            targetDisplayRatio >= DISPLAY_TARGET_EXCESS_ELEVATED && cadenceNearRefresh &&
            (framePacing?.fpsSampleCount ?: 0) + measuredIntervals.size >= 2
        if (displayBound && displayPressure != null) {
            val evidenceCount = evidence.count { it.domain == EvidenceDomain.DISPLAY && it.supportsClassification }
            candidates += Candidate(
                PerformanceState.DISPLAY_BOUND,
                displayPressure,
                candidateConfidence(displayPressure, sampleFactor(
                    (framePacing?.fpsSampleCount ?: 0) + measuredIntervals.size, 2
                ), evidenceCount, finalDataQuality.score, DISPLAY_CONFIDENCE_CAP, 1.0)
            )
        }

        val rankedCandidates = candidates.sortedByDescending { it.strength }
        // Each candidate already passed its domain-specific multi-signal gates;
        // retain moderate but valid domains when reporting mixed constraints.
        val mixedCandidates = rankedCandidates
        val state: PerformanceState
        val confidence: Confidence
        when {
            current == null -> {
                state = PerformanceState.UNKNOWN
                confidence = confidenceFor(UNKNOWN_CONFIDENCE_SCORE)
            }
            mixedCandidates.size >= 2 -> {
                state = PerformanceState.MIXED_BOUND
                val top = mixedCandidates.take(2)
                confidence = confidenceFor(
                    min(MIXED_CONFIDENCE_CAP, (top[0].confidence.score + top[1].confidence.score) / 2.0 +
                        MIXED_CONFIDENCE_BONUS)
                )
            }
            rankedCandidates.isNotEmpty() -> {
                state = rankedCandidates.first().state
                confidence = rankedCandidates.first().confidence
            }
            isHealthy(
                current = current,
                framePacing = framePacing,
                targetFps = targetFps,
                frameQuality = frameQuality,
                cpuQuality = cpuQuality,
                memoryQuality = memoryQuality,
                thermalQuality = thermalQuality,
                cpuPressure = cpuPressure,
                gpuPressure = gpuPressure,
                memoryPressure = memoryPressure,
                thermalPressure = thermalPressure,
                networkPressure = networkPressure,
                displayPressure = displayPressure,
                dataQuality = finalDataQuality
            ) -> {
                state = PerformanceState.HEALTHY
                val fit = healthyFrameFit(framePacing, targetFps)
                confidence = confidenceFor((finalDataQuality.score * (HEALTHY_CONFIDENCE_BASE + fit * HEALTHY_CONFIDENCE_FIT_WEIGHT))
                    .coerceAtMost(HEALTHY_CONFIDENCE_CAP))
                evidence += PerformanceEvidence(
                    code = if (framePacing?.medianFps != null) "MEASURED_FPS_AT_CONFIGURED_TARGET"
                    else "MEASURED_FRAME_TIME_WITHIN_TARGET_BUDGET",
                    domain = EvidenceDomain.FRAME_PACING,
                    observedValue = framePacing?.medianFps ?: framePacing?.p95FrameTimeMs,
                    unit = if (framePacing?.medianFps != null) "FPS" else "ms",
                    comparisonValue = targetFps?.toDouble(),
                    sampleCount = framePacing?.fpsSampleCount ?: framePacing?.frameSampleCount ?: 0,
                    sourceTimestampMs = frameObservations.lastOrNull()?.atMs,
                    scope = EvidenceScope.GFXINFO
                )
            }
            else -> {
                state = PerformanceState.UNKNOWN
                confidence = confidenceFor(UNKNOWN_CONFIDENCE_SCORE)
            }
        }

        return PerformanceStateResult(
            state = state,
            confidence = confidence,
            evidence = evidence.take(MAX_EVIDENCE_COUNT),
            timestampMs = safeNow,
            dataQuality = finalDataQuality,
            pressures = pressures,
            network = networkDiagnostics,
            framePacing = framePacing
        )
    }

    private fun isHealthy(
        current: PerformanceSnapshot?,
        framePacing: FramePacingMetrics?,
        targetFps: Int?,
        frameQuality: SourceQuality,
        cpuQuality: SourceQuality,
        memoryQuality: SourceQuality,
        thermalQuality: SourceQuality,
        cpuPressure: Double?,
        gpuPressure: Double?,
        memoryPressure: Double?,
        thermalPressure: Double?,
        networkPressure: Double?,
        displayPressure: Double?,
        dataQuality: DataQuality
    ): Boolean {
        if (current == null || !frameQuality.fresh || !cpuQuality.fresh || !memoryQuality.fresh ||
            !thermalQuality.fresh || dataQuality.score < HEALTHY_MIN_DATA_QUALITY
        ) return false
        val target = targetFps ?: return false
        val frameGood = (framePacing?.medianFps?.let { it >= target * HEALTHY_FPS_TARGET_RATIO } == true &&
            (framePacing.fpsSampleCount >= HEALTHY_MIN_FPS_SAMPLES)) ||
            (framePacing?.frameSampleCount ?: 0) >= config.minimumFrameSamples &&
                framePacing?.p95FrameTimeMs?.let { it <= 1_000.0 / target * HEALTHY_P95_BUDGET_RATIO } == true
        if (!frameGood) return false
        if ((cpuPressure ?: 0.0) >= HEALTHY_MAX_CPU_PRESSURE ||
            (gpuPressure ?: 0.0) >= HEALTHY_MAX_GPU_PRESSURE ||
            (memoryPressure ?: 0.0) >= HEALTHY_MAX_MEMORY_PRESSURE ||
            (thermalPressure ?: 0.0) >= HEALTHY_MAX_THERMAL_PRESSURE ||
            (networkPressure ?: 0.0) >= HEALTHY_MAX_NETWORK_PRESSURE ||
            (displayPressure ?: 0.0) >= HEALTHY_MAX_DISPLAY_PRESSURE
        ) return false
        return true
    }

    private fun healthyFrameFit(framePacing: FramePacingMetrics?, targetFps: Int?): Double {
        val target = targetFps ?: return 0.0
        val fpsFit = framePacing?.medianFps?.let { (it / target.toDouble()).coerceIn(0.0, 1.0) } ?: 0.0
        val timeFit = framePacing?.let { metrics ->
            val p95 = metrics.p95FrameTimeMs ?: return@let 0.0
            (1.0 - max(0.0, p95 / (1_000.0 / target) - 1.0)).coerceIn(0.0, 1.0)
        } ?: 0.0
        return max(fpsFit, timeFit)
    }

    private fun sourceQuality(
        source: TelemetrySource,
        values: List<Observation<*>>,
        freshMs: Long,
        now: Long,
        availability: SensorAvailability? = null,
        reason: String
    ): SourceQuality {
        val latestAt = values.maxOfOrNull { it.atMs }
        val age = latestAt?.let { now - it }
        val fresh = age != null && age in 0L..freshMs
        return SourceQuality(
            source = source,
            fresh = fresh,
            ageMs = age,
            sampleCount = values.size,
            availability = availability,
            reason = if (fresh) null else if (latestAt != null) "${source.name}_STALE" else reason
        )
    }

    private fun combinedSourceQuality(
        source: TelemetrySource,
        timestamps: List<Long>,
        freshMs: Long,
        now: Long,
        reason: String
    ): SourceQuality {
        val unique = timestamps.distinct().sorted()
        val latestAt = unique.lastOrNull()
        val age = latestAt?.let { now - it }
        val fresh = age != null && age in 0L..freshMs
        return SourceQuality(
            source = source,
            fresh = fresh,
            ageMs = age,
            sampleCount = unique.size,
            reason = if (fresh) null else if (latestAt != null) "${source.name}_STALE" else reason
        )
    }

    private fun dataQuality(
        sources: List<SourceQuality>,
        warnings: Set<String>,
        observationCount: Int,
        frameSampleCount: Int,
        networkProbeCount: Int
    ): DataQuality {
        val weights = mapOf(
            TelemetrySource.CPU to 0.22,
            TelemetrySource.GPU to 0.08,
            TelemetrySource.MEMORY to 0.18,
            TelemetrySource.THERMAL to 0.20,
            TelemetrySource.NETWORK to 0.10,
            TelemetrySource.FRAME to 0.18,
            TelemetrySource.DISPLAY to 0.04
        )
        val requiredSamples = mapOf(
            TelemetrySource.CPU to config.minimumCpuSamples,
            TelemetrySource.GPU to config.minimumGpuSamples,
            TelemetrySource.MEMORY to 3,
            TelemetrySource.THERMAL to 2,
            TelemetrySource.NETWORK to config.minimumNetworkProbes,
            TelemetrySource.FRAME to 2,
            TelemetrySource.DISPLAY to 1
        )
        val bySource = sources.associateBy { it.source }
        val coverage = weights.entries.sumOf { (source, weight) ->
            val quality = bySource[source]
            if (quality?.fresh != true) 0.0 else {
                val ageFactor = quality.ageMs?.let { age ->
                    val ttl = freshnessLimit(source).toDouble()
                    1.0 - (age / ttl).coerceIn(0.0, 1.0) * SOURCE_AGE_PENALTY
                } ?: 0.0
                weight * ageFactor
            }
        }
        val temporalAdequacy = weights.entries.sumOf { (source, weight) ->
            val quality = bySource[source] ?: return@sumOf 0.0
            if (!quality.fresh) return@sumOf 0.0
            val required = requiredSamples[source] ?: 1
            weight * sampleFactor(quality.sampleCount, required)
        }
        val score = (coverage * DATA_COVERAGE_WEIGHT + temporalAdequacy * DATA_TEMPORAL_WEIGHT)
            .coerceIn(0.0, 1.0)
        return DataQuality(
            score = score,
            level = dataQualityLevel(score),
            sources = sources,
            warnings = warnings.toList(),
            observationCount = observationCount,
            frameSampleCount = frameSampleCount,
            networkProbeCount = networkProbeCount
        )
    }

    private fun freshnessLimit(source: TelemetrySource): Long = when (source) {
        TelemetrySource.CPU -> config.cpuFreshMs
        TelemetrySource.GPU -> config.cpuFreshMs
        TelemetrySource.MEMORY -> config.memoryFreshMs
        TelemetrySource.THERMAL -> config.thermalFreshMs
        TelemetrySource.NETWORK -> config.networkFreshMs
        TelemetrySource.FRAME -> config.frameFreshMs
        TelemetrySource.DISPLAY -> config.displayFreshMs
    }

    private fun <T : Any> observations(
        snapshots: List<PerformanceSnapshot>,
        now: Long,
        windowMs: Long,
        time: (PerformanceSnapshot) -> Long?,
        value: (PerformanceSnapshot) -> T?
    ): List<Observation<T>> {
        val byTime = LinkedHashMap<Long, T>()
        snapshots.forEach { snapshot ->
            val at = time(snapshot) ?: return@forEach
            if (at < 0L || at > now || now - at > windowMs) return@forEach
            val measured = value(snapshot) ?: return@forEach
            byTime[at] = measured
        }
        return byTime.entries
            .sortedBy { it.key }
            .map { Observation(it.key, it.value) }
    }

    private fun Double?.validPercent(): Double? = this?.takeIf { it.isFinite() && it in 0.0..100.0 }

    private fun percentile(values: List<Double>, p: Double): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val position = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lowerIndex = position.toInt().coerceIn(0, sorted.lastIndex)
        val upperIndex = ceil(position).toInt().coerceIn(0, sorted.lastIndex)
        val fraction = position - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }

    private fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun consecutiveMedianDelta(values: List<Double>): Double? {
        if (values.size < 2) return null
        return median(values.zipWithNext { first, second -> abs(second - first) })
    }

    private fun weightedMean(values: List<Pair<Double, Double>>): Double? {
        val totalWeight = values.sumOf { it.second }
        if (totalWeight <= 0.0) return null
        return (values.sumOf { (value, weight) -> value.coerceIn(0.0, 1.0) * weight } / totalWeight)
            .coerceIn(0.0, 1.0)
    }

    private fun pressureRamp(value: Double, baseline: Double, elevated: Double, critical: Double): Double {
        if (!value.isFinite()) return 0.0
        if (value <= baseline) return 0.0
        if (elevated <= baseline || critical <= elevated) return 0.0
        return when {
            value <= elevated -> PRESSURE_MIDPOINT * (value - baseline) / (elevated - baseline)
            value >= critical -> 1.0
            else -> PRESSURE_MIDPOINT + (1.0 - PRESSURE_MIDPOINT) *
                (value - elevated) / (critical - elevated)
        }.coerceIn(0.0, 1.0)
    }

    private fun thermalStatusPressure(status: Int): Double = when (status.coerceIn(0, 6)) {
        0 -> 0.0
        1 -> THERMAL_LIGHT_PRESSURE
        2 -> THERMAL_MODERATE_PRESSURE
        3 -> THERMAL_SEVERE_PRESSURE
        4 -> THERMAL_CRITICAL_PRESSURE
        else -> 1.0
    }

    private fun temperaturePressure(value: Double): Double = when {
        value < THERMAL_TEMP_BASELINE_C -> 0.0
        value < ThermalGuard.RAW_MODERATE_C -> pressureRamp(
            value,
            THERMAL_TEMP_BASELINE_C,
            ThermalGuard.RAW_MODERATE_C - 2.0,
            ThermalGuard.RAW_MODERATE_C
        ) * THERMAL_PRE_MODERATE_WEIGHT
        else -> max(
            THERMAL_MODERATE_PRESSURE,
            pressureRamp(value, ThermalGuard.RAW_MODERATE_C, ThermalGuard.RAW_SEVERE_C,
                ThermalGuard.RAW_CRITICAL_C)
        )
    }

    private fun slopePressure(value: Double): Double = pressureRamp(
        value.coerceAtLeast(0.0), THERMAL_SLOPE_BASELINE, ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN,
        ThermalGuard.STRONG_HEAT_SLOPE_PER_MIN
    ) * THERMAL_SLOPE_WEIGHT

    private fun framePacingPressure(metrics: FramePacingMetrics?, targetFps: Int?): Double? {
        if (metrics == null) return null
        val terms = ArrayList<Pair<Double, Double>>()
        val target = targetFps?.takeIf { it in 1..MAX_FPS.toInt() }
        if (target != null && metrics.medianFps != null) {
            val deficit = ((target - metrics.medianFps) / target.toDouble()).coerceAtLeast(0.0)
            terms += pressureRamp(deficit, FRAME_DEFICIT_BASELINE, FRAME_DEFICIT_ELEVATED,
                FRAME_DEFICIT_CRITICAL) to FRAME_DEFICIT_WEIGHT
        }
        if (target != null && metrics.p95FrameTimeMs != null &&
            metrics.frameSampleCount >= config.minimumFrameSamples
        ) {
            val ratio = metrics.p95FrameTimeMs / (1_000.0 / target)
            terms += pressureRamp(ratio, FRAME_P95_BASELINE_RATIO, FRAME_P95_ELEVATED_RATIO,
                FRAME_P95_CRITICAL_RATIO) to FRAME_P95_WEIGHT
        }
        if (target != null && metrics.frameSampleCount >= config.minimumFrameSamples &&
            metrics.meanFrameTimeMs != null && metrics.frameTimeVarianceMs2 != null
        ) {
            val cv = sqrt(metrics.frameTimeVarianceMs2) / metrics.meanFrameTimeMs.coerceAtLeast(0.1)
            terms += pressureRamp(cv, FRAME_CV_BASELINE, FRAME_CV_ELEVATED, FRAME_CV_CRITICAL) to FRAME_CV_WEIGHT
        }
        if (metrics.jankRate != null && metrics.frameSampleCount >= config.minimumFrameSamples) {
            terms += pressureRamp(metrics.jankRate, FRAME_JANK_BASELINE, FRAME_JANK_ELEVATED,
                FRAME_JANK_CRITICAL) to FRAME_JANK_WEIGHT
        }
        if (metrics.estimatedDroppedFrameRate != null &&
            metrics.intendedVsyncIntervalCount >= config.minimumFrameSamples
        ) {
            terms += pressureRamp(metrics.estimatedDroppedFrameRate, FRAME_DROP_BASELINE,
                FRAME_DROP_ELEVATED, FRAME_DROP_CRITICAL) to FRAME_DROP_WEIGHT
        }
        return weightedMean(terms)
    }

    private fun candidateConfidence(
        strength: Double,
        sampleAdequacy: Double,
        evidenceCount: Int,
        qualityScore: Double,
        cap: Double,
        scopeFactor: Double
    ): Confidence {
        val independentEvidence = sampleFactor(evidenceCount, 2)
        val raw = (
            strength.coerceIn(0.0, 1.0) * CONFIDENCE_STRENGTH_WEIGHT +
                sampleAdequacy * CONFIDENCE_SAMPLES_WEIGHT +
                independentEvidence * CONFIDENCE_EVIDENCE_WEIGHT +
                qualityScore.coerceIn(0.0, 1.0) * CONFIDENCE_DATA_QUALITY_WEIGHT
            ) * scopeFactor
        return confidenceFor(min(cap, raw.coerceIn(0.0, 1.0)))
    }

    private fun sampleFactor(count: Int, required: Int): Double =
        (count.toDouble() / required.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    private fun confidenceFor(score: Double): Confidence {
        val safe = score.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val level = when {
            safe >= CONFIDENCE_HIGH_MIN -> ConfidenceLevel.HIGH
            safe >= CONFIDENCE_MEDIUM_MIN -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        return Confidence(safe, level)
    }

    private fun dataQualityLevel(score: Double): DataQualityLevel = when {
        score >= DATA_QUALITY_GOOD_MIN -> DataQualityLevel.GOOD
        score >= DATA_QUALITY_PARTIAL_MIN -> DataQualityLevel.PARTIAL
        else -> DataQualityLevel.POOR
    }

    companion object {
        private const val MAX_EVIDENCE_COUNT = 32
        private const val MAX_LATENCY_MS = 60_000.0
        private const val MAX_RETRANSMISSIONS_PER_SEC = 1_000_000.0
        private const val MAX_FPS = 1_000
        private const val MIN_FRAME_MS = 0.1
        private const val MAX_FRAME_MS = 1_000.0
        private const val MIN_REFRESH_HZ = 1.0
        private const val MAX_REFRESH_HZ = 1_000.0

        private const val PRESSURE_MIDPOINT = 0.5
        private const val CPU_BASELINE_PCT = 55.0
        private const val CPU_ELEVATED_PCT = 82.0
        private const val CPU_CRITICAL_PCT = 97.0
        private const val CORE_BASELINE_PCT = 70.0
        private const val CORE_ELEVATED_PCT = 92.0
        private const val CORE_CRITICAL_PCT = 100.0
        private const val CORE_PRESSURE_WEIGHT = 0.85
        private const val CPU_FRAME_SYMPTOM_MIN = 0.25
        private const val CPU_EXTREME_PRESSURE = 0.94
        private const val CPU_PRESSURE_MIN = 0.60
        private const val CPU_PRESSURE_WEIGHT = 0.62
        private const val CPU_CONFIDENCE_CAP = 0.82
        private const val CPU_SCOPE_CONFIDENCE_FACTOR = 0.88

        private const val GPU_BASELINE_PCT = 55.0
        private const val GPU_ELEVATED_PCT = 82.0
        private const val GPU_CRITICAL_PCT = 97.0
        private const val GPU_FRAME_SYMPTOM_MIN = 0.25
        private const val GPU_PRESSURE_MIN = 0.60
        private const val GPU_PRESSURE_WEIGHT = 0.65
        private const val GPU_CONFIDENCE_CAP = 0.92

        private const val MEMORY_BASELINE_PCT = 78.0
        private const val MEMORY_ELEVATED_PCT = 91.0
        private const val MEMORY_CRITICAL_PCT = 98.0
        private const val MEMORY_PRESSURE_SIGNAL = 0.92
        private const val MEMORY_THRESHOLD_SIGNAL = 0.88
        private const val MEMORY_USAGE_ONLY_WEIGHT = 0.45
        private const val MEMORY_PRESSURE_MIN = 0.75
        private const val MEMORY_CONFIDENCE_CAP = 0.78
        private const val MEMORY_SCOPE_CONFIDENCE_FACTOR = 0.84

        private const val THERMAL_TEMP_BASELINE_C = 38.0
        private const val THERMAL_ELEVATED_C = 44.0
        private const val THERMAL_SLOPE_BASELINE = 0.3
        private const val THERMAL_SLOPE_WEIGHT = 0.75
        private const val THERMAL_PRE_MODERATE_WEIGHT = 0.48
        private const val THERMAL_LIGHT_PRESSURE = 0.18
        private const val THERMAL_MODERATE_PRESSURE = 0.48
        private const val THERMAL_SEVERE_PRESSURE = 0.72
        private const val THERMAL_CRITICAL_PRESSURE = 0.90
        private const val THERMAL_CONFIDENCE_CAP = 0.94

        private const val NETWORK_LATENCY_BASELINE_MS = 40.0
        private const val NETWORK_LATENCY_ELEVATED_MS = 120.0
        private const val NETWORK_LATENCY_CRITICAL_MS = 300.0
        private const val NETWORK_VARIABILITY_BASELINE_MS = 5.0
        private const val NETWORK_VARIABILITY_ELEVATED_MS = 30.0
        private const val NETWORK_VARIABILITY_CRITICAL_MS = 80.0
        private const val NETWORK_RETRANS_BASELINE_PER_SEC = 1.0
        private const val NETWORK_RETRANS_ELEVATED_PER_SEC = 8.0
        private const val NETWORK_RETRANS_CRITICAL_PER_SEC = 30.0
        private const val NETWORK_LATENCY_WEIGHT = 0.55
        private const val NETWORK_VARIABILITY_WEIGHT = 0.30
        private const val NETWORK_RETRANS_WEIGHT = 0.15
        private const val NETWORK_PRESSURE_MIN = 0.60
        private const val NETWORK_CONFIDENCE_CAP = 0.66
        private const val NETWORK_SCOPE_CONFIDENCE_FACTOR = 0.72

        private const val FRAME_DEFICIT_BASELINE = 0.03
        private const val FRAME_DEFICIT_ELEVATED = 0.15
        private const val FRAME_DEFICIT_CRITICAL = 0.35
        private const val FRAME_DEFICIT_WEIGHT = 0.25
        private const val FRAME_P95_BASELINE_RATIO = 1.0
        private const val FRAME_P95_ELEVATED_RATIO = 1.35
        private const val FRAME_P95_CRITICAL_RATIO = 2.0
        private const val FRAME_P95_WEIGHT = 0.25
        private const val FRAME_CV_BASELINE = 0.06
        private const val FRAME_CV_ELEVATED = 0.20
        private const val FRAME_CV_CRITICAL = 0.50
        private const val FRAME_CV_WEIGHT = 0.18
        private const val FRAME_JANK_BASELINE = 0.01
        private const val FRAME_JANK_ELEVATED = 0.10
        private const val FRAME_JANK_CRITICAL = 0.30
        private const val FRAME_JANK_WEIGHT = 0.17
        private const val FRAME_DROP_BASELINE = 0.01
        private const val FRAME_DROP_ELEVATED = 0.10
        private const val FRAME_DROP_CRITICAL = 0.30
        private const val FRAME_DROP_WEIGHT = 0.15

        private const val DISPLAY_FPS_TOLERANCE = 3.0
        private const val DISPLAY_FPS_TOLERANCE_RATIO = 0.06
        private const val DISPLAY_INTERVAL_TOLERANCE_MS = 1.5
        private const val DISPLAY_INTERVAL_TOLERANCE_RATIO = 0.10
        private const val DISPLAY_TARGET_EXCESS_START = 1.05
        private const val DISPLAY_TARGET_EXCESS_ELEVATED = 1.20
        private const val DISPLAY_TARGET_EXCESS_CRITICAL = 1.50
        private const val DISPLAY_UNCONFIRMED_TARGET_WEIGHT = 0.40
        private const val DISPLAY_CONFIDENCE_CAP = 0.82

        private const val MIXED_CONFIDENCE_CAP = 0.90
        private const val MIXED_CONFIDENCE_BONUS = 0.04
        private const val HEALTHY_MIN_DATA_QUALITY = 0.52
        private const val HEALTHY_FPS_TARGET_RATIO = 0.93
        private const val HEALTHY_P95_BUDGET_RATIO = 1.12
        private const val HEALTHY_MIN_FPS_SAMPLES = 2
        private const val HEALTHY_MAX_CPU_PRESSURE = 0.62
        private const val HEALTHY_MAX_GPU_PRESSURE = 0.62
        private const val HEALTHY_MAX_MEMORY_PRESSURE = 0.70
        private const val HEALTHY_MAX_THERMAL_PRESSURE = 0.42
        private const val HEALTHY_MAX_NETWORK_PRESSURE = 0.62
        private const val HEALTHY_MAX_DISPLAY_PRESSURE = 0.62
        private const val HEALTHY_CONFIDENCE_BASE = 0.68
        private const val HEALTHY_CONFIDENCE_FIT_WEIGHT = 0.32
        private const val HEALTHY_CONFIDENCE_CAP = 0.88
        private const val UNKNOWN_CONFIDENCE_SCORE = 0.10

        private const val CONFIDENCE_STRENGTH_WEIGHT = 0.50
        private const val CONFIDENCE_SAMPLES_WEIGHT = 0.20
        private const val CONFIDENCE_EVIDENCE_WEIGHT = 0.15
        private const val CONFIDENCE_DATA_QUALITY_WEIGHT = 0.15
        private const val CONFIDENCE_HIGH_MIN = 0.80
        private const val CONFIDENCE_MEDIUM_MIN = 0.55
        private const val SOURCE_AGE_PENALTY = 0.25
        private const val DATA_COVERAGE_WEIGHT = 0.75
        private const val DATA_TEMPORAL_WEIGHT = 0.25
        private const val DATA_QUALITY_GOOD_MIN = 0.75
        private const val DATA_QUALITY_PARTIAL_MIN = 0.50
    }
}
