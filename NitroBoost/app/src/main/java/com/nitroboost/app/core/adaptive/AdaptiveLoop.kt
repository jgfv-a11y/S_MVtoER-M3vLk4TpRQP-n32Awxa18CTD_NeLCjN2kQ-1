package com.nitroboost.app.core.adaptive

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostEngine
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.Journal
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.ThermalGuard
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus
import com.nitroboost.app.core.tasks.GameApiTask
import com.nitroboost.app.core.tasks.GovernorTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.min

/** A fresh, aligned observation copied from one monitor snapshot. */
data class AdaptiveSample(
    val fps: Int?,
    val thermal: Int,
    val timestampMs: Long = System.nanoTime() / 1_000_000L,
    val tempC: Double? = null,
    val thermalSlopeCPerMin: Double? = null,
    val ramPct: Int? = null,
    /** Actual FrameCompleted - IntendedVsync values only; empty means unavailable. */
    val frameTimesMs: List<Double> = emptyList(),
    val energyMah: Double? = null,
    val gamePackage: String? = null,
    /** Increments when gfxinfo's cumulative frame counter resets for this package. */
    val processEpoch: Long = 0L,
    val thermalValid: Boolean = true,
    val monitorAgeMs: Long = 0L,
    val targetFps: Int = 60
)

/** Legacy summary of one variant arm. Kept for callers; sweeps no longer select on this alone. */
data class SweepArm(val level: String, val deltas: List<Double>) {
    val mean: Double
        get() = if (deltas.isEmpty()) Double.NEGATIVE_INFINITY else deltas.average()
}

/** Legacy pure helper. The adaptive sweep does not use a preselected arm for inference. */
@Deprecated("Sweeps compare each arm independently with multiplicity correction")
fun pickBestArm(arms: List<SweepArm>): SweepArm? = arms.maxByOrNull { it.mean }

/** A measurable variant of a candidate task (e.g. downscale 0.8 or schedutil). */
data class Variant(val detail: String, val apply: (BoostContext) -> TaskResult)

/** Android-independent input to the trial loop. */
interface AdaptiveSampler {
    /** Next fresh monitor snapshot, null when no newer sample is available. */
    fun poll(): AdaptiveSample?
    /** Latest known FPS (may be stale). */
    fun fps(): Int?
    /** Latest display metrics for UI/diagnostics. */
    fun metrics(): FrameMetrics?
    fun privileged(): Boolean
}

/**
 * Session-oriented A/B engine. Each candidate gets a fresh nearby baseline;
 * every sweep arm is paired and assessed separately, with family-wise
 * multiplicity correction. Only quality-gated block observations reach the
 * ledger. Candidate changes are journaled immediately and restored in a
 * NonCancellable finally path unless a verified KEEP is finalized.
 */
class AdaptiveLoop(
    private val engine: BoostEngine,
    private val context: () -> BoostContext,
    val ledger: DecisionLedger,
    private val sampler: AdaptiveSampler,
    private val cfg: TrialConfig,
    private val effectiveThermal: () -> Int,
    private val log: (String) -> Unit = {},
    /** User-selected boost level — trials above it must not run. */
    private val maxLevel: () -> Int = { 3 },
    private val trialContextFactory: (BoostContext, Int) -> TrialContext = { ctx, tier ->
        TrialContext(
            deviceKey = "unknown-device",
            androidVersion = "unknown-android",
            gamePackage = ctx.profile.packageName,
            boostLevel = maxLevel(),
            thermalTier = tier,
            capabilityKey = ctx.executor.javaClass.name,
            profileKey = ctx.profile.enabledModules.map { it.key }.sorted().joinToString(",") +
                ":${ctx.profile.fpsCap}:${ctx.profile.refreshRate}:${ctx.profile.gameMode}"
        )
    },
    /** Monotonic clock and wait strategy are injectable for deterministic JVM tests. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() }
) {

    companion object {
        val TRIAL_MODULES = setOf(Module.CPU, Module.GPU, Module.TWEAKS, Module.NETWORK)
        val SWEEP_LEVELS = listOf("0.9", "0.8", "0.7")
        val GOVERNOR_VARIANTS = listOf("performance", "schedutil")
        const val THERMAL_REGRESSION_TIERS = 1
        const val BLOCK_SAMPLE_COUNT = 3
        const val MIN_BLOCK_PAIRS_PER_WINDOW = 2
        const val DEFAULT_VARIANT_ID = "default"
        const val LOOP_INTERVAL_MS = 1_500L
        const val IDLE_RETRY_MS = 15_000L
        const val MATERIAL_THERMAL_RISE_C = 2.0
        const val SEVERE_THERMAL_RISE_C = 3.0
    }

    @Volatile var phase: String = "idle"
        private set
    @Volatile var candidateId: String? = null
        private set
    @Volatile var pausedReason: String? = null
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var generation = 0L
    private var activeSessionId = "not-started"
    private val attemptedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running || job?.isActive == true) return
        running = true
        generation += 1L
        val token = generation
        activeSessionId = sessionIdFactory()
        attemptedThisSession.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        job = scope?.launch { loop(token) }
        log("adaptive engine started session=$activeSessionId")
    }

    /** Request cancellation; use [stopAndJoin] before a session-wide restore. */
    @Synchronized
    fun stop() {
        if (!running && job == null) return
        running = false
        generation += 1L
        job?.cancel()
        scope?.cancel()
        phase = "idle"
        candidateId = null
        pausedReason = null
        attemptedThisSession.clear()
        log("adaptive engine stop requested")
    }

    /** Wait until the in-flight trial's NonCancellable restore has completed. */
    suspend fun stopAndJoin() {
        val oldJob = synchronized(this) { job }
        stop()
        oldJob?.join()
        synchronized(this) {
            if (job === oldJob) {
                job = null
                scope = null
            }
        }
    }

    /** Used only by synchronous service/process teardown paths. */
    fun stopAndJoinBlocking() {
        runBlocking { stopAndJoin() }
    }

    private suspend fun loop(token: Long) {
        try {
            while (running && currentCoroutineContext().isActive && token == generation) {
                step()
                when {
                    phase == "done" -> return
                    phase == "more-data" -> wait(IDLE_RETRY_MS)
                    else -> wait(LOOP_INTERVAL_MS)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("adaptive loop failed: ${e.message}")
        } finally {
            synchronized(this) {
                if (token == generation) {
                    running = false
                    if (phase != "done") phase = "idle"
                    candidateId = null
                    pausedReason = null
                    job = null
                    scope = null
                }
            }
        }
    }

    private suspend fun step() {
        pausedReason = pauseReason()
        if (pausedReason != null) {
            phase = "paused"
            candidateId = null
            return
        }
        val ctx = context()
        val task = nextCandidate(ctx)
        if (task == null) {
            val trialContext = makeTrialContext(ctx, effectiveThermal())
            val now = wallClockMs()
            val eligibleUnresolved = engine.tasks().any { t ->
                t.module in TRIAL_MODULES && t.requiresPrivilege && ctx.profile.isEnabled(t) &&
                    withinLevel(t) && !ledger.isResolved(
                        t.id, trialContext, now, cfg.decisionTtlMs
                    )
            }
            phase = if (eligibleUnresolved) "more-data" else "done"
            candidateId = null
            return
        }
        candidateId = task.id
        attemptedThisSession += task.id
        phase = "trial:${task.id}"
        runTrial(task, ctx)
    }

    fun pauseReason(): String? {
        if (!sampler.privileged()) return "needs-shizuku"
        val thermal = effectiveThermal()
        if (thermal >= ThermalGuard.STATUS_MODERATE) return "thermal:$thermal"
        return null
    }

    private fun withinLevel(task: BoostTask): Boolean = task.boostLevel <= maxLevel()

    fun nextCandidate(ctx: BoostContext): BoostTask? {
        val now = wallClockMs()
        val contextTier = effectiveThermal()
        val trialContext = makeTrialContext(ctx, contextTier)
        return engine.tasks().firstOrNull { task ->
            task.id !in attemptedThisSession &&
                task.module in TRIAL_MODULES &&
                task.requiresPrivilege &&
                ctx.profile.isEnabled(task) &&
                withinLevel(task) &&
                !ledger.isResolved(task.id, trialContext, now, cfg.decisionTtlMs)
        }
    }

    fun estimateRemainingMinutes(ctx: BoostContext): Int {
        val trialContext = makeTrialContext(ctx, effectiveThermal())
        val now = wallClockMs()
        val pending = engine.tasks().filter { task ->
            task.id !in attemptedThisSession && task.module in TRIAL_MODULES &&
                task.requiresPrivilege && ctx.profile.isEnabled(task) && withinLevel(task) &&
                !ledger.isResolved(task.id, trialContext, now, cfg.decisionTtlMs)
        }
        val windowsPerArm = 2L * (cfg.windowMs + cfg.settleMs)
        val totalMs = pending.sumOf { task ->
            val variants = if (task.id == "game_api_downscale") SWEEP_LEVELS.size
                else if (task.id == "cpu_governor") GOVERNOR_VARIANTS.size else 1
            variants * windowsPerArm + cfg.settleMs
        }
        return ((totalMs + 59_999L) / 60_000L).toInt().coerceAtLeast(if (pending.isNotEmpty()) 1 else 0)
    }

    private data class CollectedWindow(
        val samples: List<AdaptiveSample>,
        val complete: Boolean,
        val abortedReason: String? = null
    )

    private suspend fun collectWindow(windowMs: Long): CollectedWindow {
        val samples = ArrayList<AdaptiveSample>(
            (windowMs / cfg.sampleIntervalMs.coerceAtLeast(1L)).toInt().coerceIn(1, 32)
        )
        val start = clockMs()
        val end = start + windowMs.coerceAtLeast(0L)
        var lastTs = Long.MIN_VALUE
        while (clockMs() < end) {
            currentCoroutineContext().ensureActive()
            if (job != null && !running) return CollectedWindow(samples, false, "session cancelled")
            val status = effectiveThermal()
            if (status >= ThermalGuard.STATUS_MODERATE) {
                return CollectedWindow(samples, false, "thermal safety floor reached ($status)")
            }
            val sample = sampler.poll()
            if (sample != null && sample.timestampMs != lastTs) {
                lastTs = sample.timestampMs
                samples += sample
                if (sample.thermalValid && sample.thermal >= ThermalGuard.STATUS_MODERATE) {
                    return CollectedWindow(samples, false, "thermal status changed during window")
                }
            }
            val remaining = end - clockMs()
            if (remaining > 0L) wait(min(cfg.sampleIntervalMs.coerceAtLeast(1L), remaining))
        }
        return CollectedWindow(samples, clockMs() >= end)
    }

    private fun summarize(
        samples: List<AdaptiveSample>,
        complete: Boolean,
        requestedWindowMs: Long
    ): WindowMetrics {
        val fps = samples.mapNotNull { it.fps?.takeIf { v -> v > 0 }?.toDouble() }
        val sortedFps = fps.sorted()
        val thermalSamples = samples.filter { it.thermalValid }
        val tiers = thermalSamples.map { it.thermal }
        val temperatures = samples.mapNotNull { it.tempC?.takeIf { v -> v.isFinite() } }
        val slopes = samples.mapNotNull { it.thermalSlopeCPerMin?.takeIf { v -> v.isFinite() } }
        val memory = samples.mapNotNull { it.ramPct?.takeIf { v -> v in 0..100 }?.toDouble() }
        val energy = samples.mapNotNull { it.energyMah?.takeIf { v -> v.isFinite() && v >= 0.0 } }
        val times = samples.map { it.timestampMs }.sorted()
        val maxGap = times.zipWithNext().maxOfOrNull { (a, b) -> (b - a).coerceAtLeast(0L) } ?: 0L
        val packages = samples.map { it.gamePackage }.distinct()
        val epochs = samples.map { it.processEpoch }.distinct()
        val targetFps = samples.map { it.targetFps }.firstOrNull { it > 0 } ?: 60
        val frameTimes = samples.flatMap { it.frameTimesMs }
        return WindowMetrics(
            fpsMean = if (sortedFps.isEmpty()) Double.NaN else sortedFps.average(),
            lowFps = if (sortedFps.isEmpty()) Double.NaN else percentile(sortedFps, 0.10),
            fpsSampleCount = sortedFps.size,
            sampleCount = samples.size,
            thermalSampleCount = thermalSamples.size,
            thermalTier = tiers.maxOrNull() ?: -1,
            minThermalTier = tiers.minOrNull() ?: -1,
            maxThermalTier = tiers.maxOrNull() ?: -1,
            tempMeanC = temperatures.takeIf { it.isNotEmpty() }?.average(),
            tempMaxC = temperatures.maxOrNull(),
            thermalSlopeCPerMin = slopes.takeIf { it.isNotEmpty() }?.average(),
            ramMeanPct = memory.takeIf { it.isNotEmpty() }?.average(),
            energyMeanMah = energy.takeIf { it.isNotEmpty() }?.average(),
            frameTime = FrameTimeAnalysis.summarize(frameTimes, targetFps),
            firstTimestampMs = times.firstOrNull() ?: 0L,
            lastTimestampMs = times.lastOrNull() ?: 0L,
            windowComplete = complete,
            requestedWindowMs = requestedWindowMs,
            maxSampleGapMs = maxGap,
            maxMonitorAgeMs = samples.maxOfOrNull { it.monitorAgeMs.coerceAtLeast(0L) } ?: Long.MAX_VALUE,
            gamePackage = packages.singleOrNull(),
            processEpoch = epochs.singleOrNull() ?: -1L,
            targetFps = targetFps
        )
    }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        sorted[((ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex))]

    private fun makeTrialContext(ctx: BoostContext, thermalTier: Int): TrialContext =
        trialContextFactory(ctx, thermalTier).copy(
            gamePackage = ctx.profile.packageName,
            boostLevel = maxLevel(),
            thermalTier = thermalTier
        )

    private fun sessionQuality(
        base: WindowMetrics,
        candidate: WindowMetrics,
        gamePackage: String
    ): QualityResult = SessionQualityGate.compare(base, candidate, gamePackage, cfg)

    /** Package-visible for deterministic JVM lifecycle tests; production calls this from the loop. */
    internal suspend fun runTrial(task: BoostTask, ctx: BoostContext) {
        when (task.id) {
            "game_api_downscale" -> runVariantSweep(
                task, ctx,
                SWEEP_LEVELS.map { level ->
                    Variant("level=$level") { GameApiTask(level = level).apply(it) }
                }
            )
            "cpu_governor" -> runVariantSweep(
                task, ctx,
                GOVERNOR_VARIANTS.map { governor ->
                    Variant("governor=$governor") { GovernorTask(governor).apply(it) }
                }
            )
            else -> runSingle(task, ctx)
        }
    }

    private suspend fun runSingle(task: BoostTask, ctx: BoostContext) {
        var trialContext = makeTrialContext(ctx, effectiveThermal())
        var keepApplied = false
        try {
            if (!revertTask(task.id, ctx)) {
                ledger.markMoreData(task.id, task.titleEn, trialContext,
                    "baseline restore failed; comparison rejected", wallClockMs(), DEFAULT_VARIANT_ID)
                return
            }
            wait(cfg.settleMs.coerceAtLeast(0L))
            val baselineWindow = collectWindow(cfg.windowMs)
            val baseline = summarize(baselineWindow.samples, baselineWindow.complete, cfg.windowMs)
            trialContext = makeTrialContext(ctx, baseline.thermalTier)
            val baseQuality = if (baselineWindow.abortedReason != null) {
                QualityResult.reject(baselineWindow.abortedReason)
            } else SessionQualityGate.validateWindow(baseline, ctx.profile.packageName, cfg)
            if (!baseQuality.accepted) {
                ledger.markMoreData(task.id, task.titleEn, trialContext, baseQuality.reason,
                    wallClockMs(), DEFAULT_VARIANT_ID)
                log("adaptive ${task.id}: MORE_DATA (${baseQuality.reason})")
                return
            }

            val applyResult = applyAndJournal(task.id, task.titleEn, ctx) { task.apply(ctx) }
            if (!applyResult.status.success || applyResult.entries.isEmpty()) {
                ledger.markMoreData(task.id, task.titleEn, trialContext,
                    "candidate not safely applicable (${applyResult.detail})", wallClockMs(), DEFAULT_VARIANT_ID)
                log("adaptive ${task.id}: candidate not measurable (${applyResult.detail})")
                return
            }
            wait(cfg.settleMs.coerceAtLeast(0L))
            val candidateWindow = collectWindow(cfg.windowMs)
            val candidate = summarize(candidateWindow.samples, candidateWindow.complete, cfg.windowMs)
            val quality = if (candidateWindow.abortedReason != null) {
                QualityResult.reject(candidateWindow.abortedReason)
            } else sessionQuality(baseline, candidate, ctx.profile.packageName)
            if (!quality.accepted) {
                ledger.markMoreData(task.id, task.titleEn, trialContext, quality.reason,
                    wallClockMs(), DEFAULT_VARIANT_ID)
                log("adaptive ${task.id}: MORE_DATA (${quality.reason})")
                return
            }
            val observations = pairedObservations(
                task.id, DEFAULT_VARIANT_ID, baselineWindow.samples, candidateWindow.samples,
                baseline, candidate
            )
            if (observations.size < MIN_BLOCK_PAIRS_PER_WINDOW) {
                ledger.markMoreData(task.id, task.titleEn, trialContext,
                    "not enough synchronized blocks", wallClockMs(), DEFAULT_VARIANT_ID)
                return
            }
            val safetyLimit = thermalSafetyLimit(observations)
            val variant = ledger.recordVariant(
                taskId = task.id,
                taskTitle = task.titleEn,
                variantId = DEFAULT_VARIANT_ID,
                detail = DEFAULT_VARIANT_ID,
                newObservations = observations,
                comparisonCount = 1,
                context = trialContext,
                nowMs = wallClockMs(),
                cfg = cfg,
                safetyLimit = safetyLimit
            )
            if (variant.decision != Decision.KEEP && !revertTask(task.id, ctx)) {
                ledger.markMoreData(task.id, task.titleEn, trialContext,
                    "restore failed after candidate measurement", wallClockMs(), DEFAULT_VARIANT_ID)
                return
            }
            val final = ledger.finalizeSingle(
                task.id, task.titleEn, DEFAULT_VARIANT_ID, trialContext, wallClockMs()
            )
            keepApplied = final.decision == Decision.KEEP
            log("adaptive ${task.id}: ${final.decision} score=${final.score} pairs=${final.pairs}")
        } catch (e: CancellationException) {
            ledger.markMoreData(task.id, task.titleEn, trialContext,
                "adaptive session cancelled", wallClockMs(), DEFAULT_VARIANT_ID)
            throw e
        } catch (e: Exception) {
            ledger.markMoreData(task.id, task.titleEn, trialContext,
                "candidate exception: ${e.message ?: e.javaClass.simpleName}", wallClockMs(), DEFAULT_VARIANT_ID)
            log("adaptive ${task.id}: candidate exception: ${e.message}")
        } finally {
            if (!keepApplied) withContext(NonCancellable) {
                if (!revertTask(task.id, ctx)) {
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "restore failed; journal retained for retry", wallClockMs(), DEFAULT_VARIANT_ID)
                    log("adaptive ${task.id}: restore failed; journal retained")
                }
            }
        }
    }

    private suspend fun runVariantSweep(task: BoostTask, ctx: BoostContext, variants: List<Variant>) {
        if (variants.isEmpty()) return
        var trialContext = makeTrialContext(ctx, effectiveThermal())
        var keepApplied = false
        var allPairsQualityChecked = true
        var testedCount = 0
        try {
            for (variant in variants) {
                currentCoroutineContext().ensureActive()
                val previouslyResolved = ledger.entries[task.id]
                    ?.takeIf { it.context == trialContext }
                    ?.variants?.get(variant.detail)?.decision?.resolved == true
                if (previouslyResolved) {
                    testedCount++
                    continue
                }
                if (!revertTask(task.id, ctx)) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "restore failed before variant ${variant.detail}", wallClockMs(), variant.detail)
                    break
                }
                wait(cfg.settleMs.coerceAtLeast(0L))
                val baselineWindow = collectWindow(cfg.windowMs)
                val baseline = summarize(baselineWindow.samples, baselineWindow.complete, cfg.windowMs)
                val baseQuality = if (baselineWindow.abortedReason != null) {
                    QualityResult.reject(baselineWindow.abortedReason)
                } else SessionQualityGate.validateWindow(baseline, ctx.profile.packageName, cfg)
                if (!baseQuality.accepted) {
                    allPairsQualityChecked = false
                    trialContext = makeTrialContext(ctx, baseline.thermalTier)
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        baseQuality.reason, wallClockMs(), variant.detail)
                    log("adaptive sweep ${task.id}/${variant.detail}: MORE_DATA (${baseQuality.reason})")
                    break
                }
                val thisContext = makeTrialContext(ctx, baseline.thermalTier)
                if (testedCount > 0 && thisContext != trialContext) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "thermal/device context changed between variants", wallClockMs(), variant.detail)
                    break
                }
                trialContext = thisContext

                val applyResult = applyAndJournal(task.id, task.titleEn, ctx) { variant.apply(ctx) }
                if (!applyResult.status.success || applyResult.entries.isEmpty()) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "variant not safely applicable (${applyResult.detail})", wallClockMs(), variant.detail)
                    break
                }
                wait(cfg.settleMs.coerceAtLeast(0L))
                val candidateWindow = collectWindow(cfg.windowMs)
                val candidate = summarize(candidateWindow.samples, candidateWindow.complete, cfg.windowMs)
                val quality = if (candidateWindow.abortedReason != null) {
                    QualityResult.reject(candidateWindow.abortedReason)
                } else sessionQuality(baseline, candidate, ctx.profile.packageName)
                if (!quality.accepted) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        quality.reason, wallClockMs(), variant.detail)
                    log("adaptive sweep ${task.id}/${variant.detail}: MORE_DATA (${quality.reason})")
                    break
                }
                val observations = pairedObservations(
                    task.id, variant.detail, baselineWindow.samples, candidateWindow.samples,
                    baseline, candidate
                )
                if (observations.size < MIN_BLOCK_PAIRS_PER_WINDOW) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "not enough synchronized blocks", wallClockMs(), variant.detail)
                    break
                }
                ledger.recordVariant(
                    taskId = task.id,
                    taskTitle = task.titleEn,
                    variantId = variant.detail,
                    detail = variant.detail,
                    newObservations = observations,
                    comparisonCount = variants.size,
                    context = trialContext,
                    nowMs = wallClockMs(),
                    cfg = cfg,
                    safetyLimit = thermalSafetyLimit(observations)
                )
                testedCount++
                if (!revertTask(task.id, ctx)) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "restore failed after variant ${variant.detail}", wallClockMs(), variant.detail)
                    break
                }
            }

            if (testedCount != variants.size) allPairsQualityChecked = false
            val current = ledger.entries[task.id]?.takeIf { it.context == trialContext }
            val armRecords = variants.mapNotNull { current?.variants?.get(it.detail) }
            val allResolved = armRecords.size == variants.size && armRecords.all { it.decision.resolved }
            val provisionalWinner = if (allResolved) {
                armRecords.filter { it.decision == Decision.KEEP }
                    .maxByOrNull { it.meanScore ?: Double.NEGATIVE_INFINITY }
            } else null

            if (allPairsQualityChecked && allResolved && provisionalWinner != null) {
                val selected = variants.firstOrNull { it.detail == provisionalWinner.variantId }
                val appliedWinner = selected?.let {
                    applyAndJournal(task.id, task.titleEn, ctx) { it.apply(ctx) }
                }
                if (appliedWinner == null || !appliedWinner.status.success || appliedWinner.entries.isEmpty()) {
                    allPairsQualityChecked = false
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "selected variant failed to re-apply safely", wallClockMs(), provisionalWinner.variantId)
                } else {
                    val final = ledger.finalizeSweep(
                        task.id, task.titleEn, variants.map { it.detail }, trialContext,
                        wallClockMs(), completeSweep = true
                    )
                    keepApplied = final.decision == Decision.KEEP
                }
            }
            if (!keepApplied) {
                ledger.finalizeSweep(
                    task.id, task.titleEn, variants.map { it.detail }, trialContext,
                    wallClockMs(), completeSweep = allPairsQualityChecked
                )
            }
            val finalEntry = ledger.entries[task.id]
            log("adaptive sweep ${task.id}: ${finalEntry?.decision} score=${finalEntry?.score} pairs=${finalEntry?.pairs}")
        } catch (e: CancellationException) {
            ledger.markMoreData(task.id, task.titleEn, trialContext,
                "adaptive sweep cancelled", wallClockMs())
            throw e
        } catch (e: Exception) {
            ledger.markMoreData(task.id, task.titleEn, trialContext,
                "sweep exception: ${e.message ?: e.javaClass.simpleName}", wallClockMs())
            log("adaptive sweep ${task.id} failed: ${e.message}")
        } finally {
            if (!keepApplied) withContext(NonCancellable) {
                if (!revertTask(task.id, ctx)) {
                    ledger.markMoreData(task.id, task.titleEn, trialContext,
                        "restore failed; journal retained for retry", wallClockMs())
                    log("adaptive sweep ${task.id}: restore failed; journal retained")
                }
            }
        }
    }

    private fun applyAndJournal(
        taskId: String,
        title: String,
        ctx: BoostContext,
        apply: () -> TaskResult
    ): TaskResult {
        return engine.withTaskLock(taskId) {
            val result = try {
                apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A well-behaved task returns its reversible entries. Any
                // partial entries already appended by a task remain restorable.
                TaskResult(taskId, TaskStatus.Failed("adaptive candidate: ${e.message}"))
            }
            if (result.entries.isNotEmpty() && result.status.success) {
                ctx.journal.add(result.entries)
                ctx.log("adaptive journaled $taskId: ${result.entries.size} change(s)")
            }
            result
        }
    }

    private fun pairedObservations(
        taskId: String,
        variantId: String,
        baselineSamples: List<AdaptiveSample>,
        candidateSamples: List<AdaptiveSample>,
        baseline: WindowMetrics,
        candidate: WindowMetrics
    ): List<PairObservation> {
        // Matching blocks avoids treating each autocorrelated 1Hz tick as an
        // independent experiment. Full windows are quality-gated above.
        val common = min(baselineSamples.size, candidateSamples.size)
        val blockCount = common / BLOCK_SAMPLE_COUNT
        if (blockCount < MIN_BLOCK_PAIRS_PER_WINDOW) return emptyList()
        val observations = ArrayList<PairObservation>(blockCount)
        for (block in 0 until blockCount) {
            val start = block * BLOCK_SAMPLE_COUNT
            val end = start + BLOCK_SAMPLE_COUNT
            val baseBlock = summarize(
                baselineSamples.subList(start, end), complete = true, requestedWindowMs = 0L
            )
            val candidateBlock = summarize(
                candidateSamples.subList(start, end), complete = true, requestedWindowMs = 0L
            )
            if (baseBlock.fpsSampleCount == 0 || candidateBlock.fpsSampleCount == 0 ||
                baseBlock.thermalSampleCount == 0 || candidateBlock.thermalSampleCount == 0
            ) continue
            val comparison = AdaptivePolicy.scoreComparison(baseBlock, candidateBlock)
            observations += PairObservation(
                sampleId = "$activeSessionId|$taskId|$variantId|$block",
                sessionId = activeSessionId,
                observedAtMs = wallClockMs(),
                baseline = baseBlock,
                candidate = candidateBlock,
                score = comparison
            )
        }
        return observations
    }

    /** Thermal risks may downgrade statistical KEEP, never upgrade MORE_DATA/DROP. */
    private fun thermalSafetyLimit(observations: List<PairObservation>): Decision? {
        if (observations.isEmpty()) return null
        val rises = observations.mapNotNull { observation ->
            val b = observation.baseline.tempMaxC ?: observation.baseline.tempMeanC
            val c = observation.candidate.tempMaxC ?: observation.candidate.tempMeanC
            if (b == null || c == null) null else c - b
        }
        val slopeIncreases = observations.mapNotNull { observation ->
            val b = observation.baseline.thermalSlopeCPerMin
            val c = observation.candidate.thermalSlopeCPerMin
            if (b == null || c == null) null else c - b
        }
        val maxRise = rises.maxOrNull() ?: 0.0
        val maxSlopeRise = slopeIncreases.maxOrNull() ?: 0.0
        return when {
            maxRise >= SEVERE_THERMAL_RISE_C || maxSlopeRise >= ThermalGuard.STRONG_HEAT_SLOPE_PER_MIN -> Decision.DROP
            maxRise >= MATERIAL_THERMAL_RISE_C || maxSlopeRise >= ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN -> Decision.NEUTRAL
            else -> null
        }
    }

    /** Restore all recorded entries for one task, retaining any failed restores. */
    fun revertTask(taskId: String, ctx: BoostContext): Boolean = engine.withTaskLock(taskId) {
        val entries = ctx.journal.snapshot().filter { it.taskId == taskId }.asReversed()
        val restored = mutableListOf<com.nitroboost.app.core.JournalEntry>()
        var allRestored = true
        for (entry in entries) {
            if (Journal.restore(entry, ctx.executor)) restored += entry else allRestored = false
        }
        if (restored.isNotEmpty()) ctx.journal.remove(restored)
        allRestored
    }

}
