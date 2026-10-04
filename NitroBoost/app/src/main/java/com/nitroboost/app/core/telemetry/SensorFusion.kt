package com.nitroboost.app.core.telemetry

/**
 * Pure-Kotlin boundary for validating readings from independent platform
 * samplers. Missing, malformed, or out-of-range values stay null; per-source
 * observation times remain distinct from the snapshot capture time.
 */
object SensorFusion {

    fun fuse(
        capturedAtMs: Long,
        cpu: CpuSnapshot = CpuSnapshot(),
        gpu: GpuSnapshot = GpuSnapshot(),
        memory: MemorySnapshot = MemorySnapshot(),
        thermal: ThermalSnapshot = ThermalSnapshot(),
        battery: BatterySnapshot = BatterySnapshot(),
        network: NetworkSnapshot = NetworkSnapshot(),
        frame: FrameSnapshot = FrameSnapshot(),
        device: DeviceSnapshot = DeviceSnapshot(),
        sourceTimestamps: TelemetrySourceTimestamps = TelemetrySourceTimestamps()
    ): PerformanceSnapshot {
        val cpuUtilization = cpu.utilizationPct.percentOrNull()
        val perCore = cpu.perCoreUtilizationPct
            ?.mapNotNull { it.percentOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val cpuFrequency = cpu.meanPolicyFrequencyKHz?.takeIf { it in 1L..MAX_FREQUENCY_KHZ }
        val gpuUtilization = gpu.utilizationPct.percentOrNull()
        val gpuFrequency = gpu.frequencyKHz?.takeIf { it in 1L..MAX_FREQUENCY_KHZ }
        val frameTimes = frame.frameTimesMs
            ?.filter { it.isValidFrameTime() }
            ?.takeIf { it.isNotEmpty() }
        val vsyncIntervals = frame.intendedVsyncIntervalsMs
            ?.filter { it.isValidFrameTime() }
            ?.takeIf { it.isNotEmpty() }

        val totalBytes = memory.totalBytes?.takeIf { it in 1L..MAX_MEMORY_BYTES }
        val availableBytes = memory.availableBytes
            ?.takeIf { it in 0L..MAX_MEMORY_BYTES && totalBytes != null && it <= totalBytes }
        val usedBytes = memory.usedBytes
            ?.takeIf { it in 0L..MAX_MEMORY_BYTES && totalBytes != null && it <= totalBytes }
            ?: if (totalBytes != null && availableBytes != null) totalBytes - availableBytes else null
        val thresholdBytes = memory.thresholdBytes
            ?.takeIf { it in 0L..MAX_MEMORY_BYTES && totalBytes != null && it <= totalBytes }

        val latency = network.latencyMs?.takeIf { it.isFinite() && it in 0.0..MAX_LATENCY_MS }
        val retransmissions = network.retransmissionsPerSec
            ?.takeIf { it.isFinite() && it in 0.0..MAX_RETRANSMISSIONS_PER_SEC }
        val fps = frame.fps?.takeIf { it.isFinite() && it in 0.0..MAX_FPS && it > 0.0 }
        val targetFps = frame.targetFps?.takeIf { it in 1..MAX_FPS.toInt() }
        val captureTime = capturedAtMs.coerceAtLeast(0L)
        val thermalStatus = thermal.osStatus?.takeIf { it in MIN_THERMAL_STATUS..MAX_THERMAL_STATUS }
        val temperatureC = thermal.temperatureC?.takeIf { it.isFinite() && it in MIN_TEMP_C..MAX_TEMP_C }
        val thermalSlope = thermal.slopeCPerMin?.takeIf { it.isFinite() && it in MIN_SLOPE..MAX_SLOPE }
        val effectiveThermalStatus = thermal.effectiveStatus
            ?.takeIf { it in MIN_THERMAL_STATUS..MAX_THERMAL_STATUS }
        val batteryLevel = battery.levelPct?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val batteryTemperature = battery.temperatureC
            ?.takeIf { it.isFinite() && it in MIN_TEMP_C..MAX_TEMP_C }
        val batteryCurrent = battery.currentMilliAmps
            ?.takeIf { it.isFinite() && it in MIN_BATTERY_CURRENT_MA..MAX_BATTERY_CURRENT_MA }
        val batteryEnergy = battery.energyMah?.takeIf { it.isFinite() && it >= 0.0 }
        val gpuModel = cleanText(gpu.model)
        val gpuRenderer = cleanText(gpu.renderer)
        val frameRenderer = cleanText(frame.renderer)
        val safeDevice = device.copy(
            manufacturer = cleanText(device.manufacturer),
            model = cleanText(device.model),
            socModel = cleanText(device.socModel),
            androidApi = device.androidApi?.takeIf { it in 1..200 },
            androidRelease = cleanText(device.androidRelease),
            gpuModel = cleanText(device.gpuModel),
            renderer = cleanText(device.renderer),
            displayWidthPx = device.displayWidthPx?.takeIf { it in 1..MAX_DISPLAY_DIMENSION_PX },
            displayHeightPx = device.displayHeightPx?.takeIf { it in 1..MAX_DISPLAY_DIMENSION_PX },
            refreshRateHz = device.refreshRateHz
                ?.takeIf { it.isFinite() && it in MIN_REFRESH_HZ..MAX_REFRESH_HZ }
        )

        return PerformanceSnapshot(
            capturedAtMs = captureTime,
            cpu = cpu.copy(
                utilizationPct = cpuUtilization,
                perCoreUtilizationPct = perCore,
                meanPolicyFrequencyKHz = cpuFrequency,
                frequencyAvailability = availabilityFor(cpuFrequency, cpu.frequencyAvailability)
            ),
            gpu = gpu.copy(
                utilizationPct = gpuUtilization,
                frequencyKHz = gpuFrequency,
                model = gpuModel,
                renderer = gpuRenderer,
                utilizationAvailability = availabilityFor(
                    gpuUtilization,
                    gpu.utilizationAvailability
                ),
                frequencyAvailability = availabilityFor(
                    gpuFrequency,
                    gpu.frequencyAvailability
                ),
                reason = cleanText(gpu.reason)
            ),
            memory = MemorySnapshot(
                usedBytes = usedBytes,
                availableBytes = availableBytes,
                totalBytes = totalBytes,
                thresholdBytes = thresholdBytes,
                pressure = memory.pressure
            ),
            thermal = ThermalSnapshot(
                osStatus = thermalStatus,
                temperatureC = temperatureC,
                slopeCPerMin = thermalSlope,
                effectiveStatus = effectiveThermalStatus
            ),
            battery = BatterySnapshot(
                levelPct = batteryLevel,
                charging = battery.charging,
                temperatureC = batteryTemperature,
                currentMilliAmps = batteryCurrent,
                energyMah = batteryEnergy
            ),
            network = NetworkSnapshot(
                latencyMs = latency,
                retransmissionsPerSec = retransmissions
            ),
            frame = FrameSnapshot(
                fps = fps,
                targetFps = targetFps,
                frameTimesMs = frameTimes,
                intendedVsyncIntervalsMs = vsyncIntervals,
                renderer = frameRenderer
            ),
            device = safeDevice,
            sourceTimestamps = TelemetrySourceTimestamps(
                cpuAtMs = sourceAt(
                    sourceTimestamps.cpuAtMs,
                    cpuUtilization != null || perCore?.isNotEmpty() == true,
                    captureTime
                ),
                cpuFrequencyAtMs = sourceAt(
                    sourceTimestamps.cpuFrequencyAtMs,
                    cpuFrequency != null,
                    captureTime
                ),
                gpuAtMs = sourceAt(
                    sourceTimestamps.gpuAtMs,
                    gpuUtilization != null || gpuFrequency != null || gpuModel != null || gpuRenderer != null,
                    captureTime
                ),
                memoryAtMs = sourceAt(
                    sourceTimestamps.memoryAtMs,
                    usedBytes != null || availableBytes != null || totalBytes != null ||
                        thresholdBytes != null || memory.pressure != null,
                    captureTime
                ),
                thermalStatusAtMs = sourceAt(
                    sourceTimestamps.thermalStatusAtMs,
                    thermalStatus != null,
                    captureTime
                ),
                temperatureAtMs = sourceAt(
                    sourceTimestamps.temperatureAtMs,
                    temperatureC != null,
                    captureTime
                ),
                batteryAtMs = sourceAt(
                    sourceTimestamps.batteryAtMs,
                    batteryLevel != null || battery.charging != null || batteryTemperature != null ||
                        batteryCurrent != null || batteryEnergy != null,
                    captureTime
                ),
                networkLatencyAtMs = sourceAt(
                    sourceTimestamps.networkLatencyAtMs,
                    latency != null,
                    captureTime
                ),
                networkRetransmissionsAtMs = sourceAt(
                    sourceTimestamps.networkRetransmissionsAtMs,
                    retransmissions != null,
                    captureTime
                ),
                frameAtMs = sourceAt(
                    sourceTimestamps.frameAtMs,
                    fps != null || targetFps != null || frameTimes != null || vsyncIntervals != null ||
                        frameRenderer != null,
                    captureTime
                ),
                deviceAtMs = sourceAt(
                    sourceTimestamps.deviceAtMs,
                    listOf(
                        safeDevice.manufacturer,
                        safeDevice.model,
                        safeDevice.socModel,
                        safeDevice.androidApi,
                        safeDevice.androidRelease,
                        safeDevice.gpuModel,
                        safeDevice.renderer,
                        safeDevice.displayWidthPx,
                        safeDevice.displayHeightPx,
                        safeDevice.refreshRateHz
                    ).any { it != null },
                    captureTime
                )
            )
        )
    }

    private fun sourceAt(value: Long?, hasValue: Boolean, capturedAtMs: Long): Long? =
        if (hasValue) value?.takeIf { it in 0L..capturedAtMs } else null

    private fun Double?.percentOrNull(): Double? =
        this?.takeIf { it.isFinite() && it in 0.0..100.0 }

    private fun Double.isValidFrameTime(): Boolean =
        isFinite() && this in MIN_FRAME_MS..MAX_FRAME_MS

    private fun availabilityFor(
        value: Any?,
        reported: SensorAvailability
    ): SensorAvailability = when {
        value != null -> SensorAvailability.AVAILABLE
        reported == SensorAvailability.AVAILABLE -> SensorAvailability.UNAVAILABLE
        else -> reported
    }

    private fun cleanText(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        ?.take(MAX_TEXT_LENGTH)

    private const val MAX_FREQUENCY_KHZ = 20_000_000L
    private const val MAX_MEMORY_BYTES = 1_125_899_906_842_624L
    private const val MAX_LATENCY_MS = 60_000.0
    private const val MAX_RETRANSMISSIONS_PER_SEC = 1_000_000.0
    private const val MAX_FPS = 1_000.0
    private const val MIN_FRAME_MS = 0.1
    private const val MAX_FRAME_MS = 1_000.0
    private const val MIN_THERMAL_STATUS = 0
    private const val MAX_THERMAL_STATUS = 6
    private const val MIN_TEMP_C = -40.0
    private const val MAX_TEMP_C = 200.0
    private const val MIN_SLOPE = -100.0
    private const val MAX_SLOPE = 100.0
    private const val MIN_BATTERY_CURRENT_MA = -20_000.0
    private const val MAX_BATTERY_CURRENT_MA = 20_000.0
    private const val MIN_REFRESH_HZ = 1.0
    private const val MAX_REFRESH_HZ = 1_000.0
    private const val MAX_DISPLAY_DIMENSION_PX = 100_000
    private const val MAX_TEXT_LENGTH = 128
}
