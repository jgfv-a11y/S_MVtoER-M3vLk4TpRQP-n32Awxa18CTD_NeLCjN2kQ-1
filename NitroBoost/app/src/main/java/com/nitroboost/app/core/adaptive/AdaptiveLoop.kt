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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** One aligned observation (FPS + thermal tier) of the running game. */
data class AdaptiveSample(val fps: Int?, val thermal: Int)

/** One measured arm of a variant sweep. */
data class SweepArm(val level: String, val deltas: List<Double>) {
    val mean: Double
        get() = if (deltas.isEmpty()) Double.NEGATIVE_INFINITY else deltas.average()
}

/** Pure: best arm = highest mean delta; null when nothing measured. */
fun pickBestArm(arms: List<SweepArm>): SweepArm? = arms.maxByOrNull { it.mean }

/** A measurable variant of a candidate task (e.g. downscale 0.8, governor schedutil). */
data class Variant(val detail: String, val apply: (BoostContext) -> TaskResult)

/**
 * What the loop can observe. Implemented by AppStore over the monitor
 * snapshots; the loop itself never touches Android classes, which is what
 * keeps the decision pipeline unit-testable.
 */
interface AdaptiveSampler {
    /**
     * Next FRESH aligned sample (null while the monitor has not produced a
     * new snapshot). One call per loop tick — FPS and thermal stay paired.
     */
    fun poll(): AdaptiveSample?
    /** Latest known FPS (may be stale). */
    fun fps(): Int?
    /** Latest frame metrics, or null before the first sample. */
    fun metrics(): FrameMetrics?
    fun privileged(): Boolean
}

/**
 * The adaptive engine (v2 — faster, more precise, stronger).
 *
 * During a boost session it A/B tests every profile-enabled performance
 * tweak on the real device:
 *
 *   1. revert the candidate -> settle -> baseline window
 *   2. apply the candidate  -> settle -> arm window
 *   3. paired, outlier-trimmed deltas -> DecisionLedger
 *   4. verdict: KEEP / DROP / NEUTRAL / NEEDS_MORE
 *
 * Speed:
 *  - the FIRST candidate of a session measures the baseline; every later
 *    candidate REUSES it (device unchanged) — saving a full window per task;
 *  - the baseline is re-measured only after 5 minutes or a thermal change.
 *
 * Precision:
 *  - deltas are outlier-trimmed (top/bottom 10%) before the paired 95% CI;
 *  - thermal-aware: an FPS "win" that heats the SoC a full tier is
 *    downgraded to NEUTRAL — a win you pay for with throttling is not a win.
 *
 * Strength:
 *  - variant sweeps: downscale 0.9/0.8/0.7 and governor performance/schedutil
 *    are tested against each other; the winner (with its exact variant) is
 *    persisted and restored by AppStore on every later session.
 *
 * Safety (non-negotiable):
 *  - never runs without a privileged shell and a real FPS source;
 *  - pauses while the effective thermal status is >= MODERATE (the
 *    predictive trend can escalate one tier early);
 *  - every applied candidate lives in the main journal, so "restore all"
 *    and the game-exit watcher revert it like any other modification;
 *  - any exception inside a step is logged and the loop keeps going.
 */
class AdaptiveLoop(
    private val engine: BoostEngine,
    private val context: () -> BoostContext,
    val ledger: DecisionLedger,
    private val sampler: AdaptiveSampler,
    private val cfg: TrialConfig,
    private val effectiveThermal: () -> Int,
    private val log: (String) -> Unit = {},
    /** v1.5: user-selected boost level — trials above it must not run. */
    private val maxLevel: () -> Int = { 3 }
) {

    companion object {
        /** Only performance-relevant modules are worth an A/B trial. */
        val TRIAL_MODULES = setOf(
            Module.CPU, Module.GPU, Module.TWEAKS, Module.NETWORK
        )

        /** Legal AOSP downscale ratios, mildest first. */
        val SWEEP_LEVELS = listOf("0.9", "0.8", "0.7")

        /** Governor variants tested against each other. */
        val GOVERNOR_VARIANTS = listOf("performance", "schedutil")

        /** A session baseline is valid for at most this long. */
        const val BASELINE_MAX_AGE_MS = 5 * 60_000L

        /** Arm heats one full thermal tier vs baseline -> not a real win. */
        const val THERMAL_REGRESSION_TIERS = 1
    }

    @Volatile var phase: String = "idle"
        private set
    @Volatile var candidateId: String? = null
        private set
    @Volatile var pausedReason: String? = null
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile
    private var running = false

    // Session-wide baseline (shared by all candidates, see class docs).
    @Volatile
    private var sessBaseline: List<Int>? = null
    @Volatile
    private var sessBaselineThermal = -1
    @Volatile
    private var sessBaselineAt = 0L

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        sessBaseline = null
        sessBaselineAt = 0L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        job = scope?.launch { loop() }
        log("adaptive engine started")
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        phase = "idle"
        candidateId = null
        pausedReason = null
        sessBaseline = null
        sessBaselineAt = 0L
        log("adaptive engine stopped")
    }

    private suspend fun loop() {
        while (running) {
            try {
                step()
            } catch (e: Exception) {
                log("adaptive step failed: ${e.message}")
            }
            delay(1_500)
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
            phase = "done"
            candidateId = null
            return
        }
        candidateId = task.id
        phase = "trial:${task.id}"
        runTrial(task, ctx)
    }

    /** Returns a human reason when the engine must stand down, else null. */
    fun pauseReason(): String? {
        if (!sampler.privileged()) return "needs-shizuku"
        val eff = effectiveThermal()
        if (eff >= ThermalGuard.STATUS_MODERATE) return "thermal:$eff"
        return null
    }

    /** v1.5: candidate must be at or below the user-selected boost level. */
    private fun withinLevel(t: BoostTask): Boolean = t.boostLevel <= maxLevel()

    /** Next profile-enabled, unresolved trial candidate, in task order. */
    fun nextCandidate(ctx: BoostContext): BoostTask? =
        engine.tasks().firstOrNull { t ->
            t.module in TRIAL_MODULES &&
                t.requiresPrivilege &&
                ctx.profile.isEnabled(t) &&
                withinLevel(t) &&
                !ledger.isResolved(t.id)
        }

    /** Human-friendly estimate of the remaining work, in minutes. */
    fun estimateRemainingMinutes(ctx: BoostContext): Int {
        val pending = engine.tasks().count { t ->
            t.module in TRIAL_MODULES &&
                t.requiresPrivilege &&
                ctx.profile.isEnabled(t) &&
                withinLevel(t) &&
                !ledger.isResolved(t.id)
        }
        var ms = pending.toLong() * (cfg.windowMs + 2 * cfg.settleMs)
        if (sessBaseline == null) ms += cfg.windowMs
        return ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    }

    private data class Window(val fps: List<Int>, val thermalMean: Int)

    /**
     * Acquire the session baseline: reuse the cached one when still valid
     * (same thermal tier, < 5 minutes old), otherwise measure it fresh with
     * the candidate reverted.
     */
    private suspend fun acquireBaseline(ctx: BoostContext, candidateId: String): Window? {
        val cached = sessBaseline
        if (
            cached != null &&
            cached.size >= 2 &&
            System.currentTimeMillis() - sessBaselineAt < BASELINE_MAX_AGE_MS &&
            effectiveThermal() == sessBaselineThermal
        ) {
            return Window(cached, sessBaselineThermal)
        }
        revertTask(candidateId, ctx)
        delay(cfg.settleMs)
        val w = collectWindow(cfg.windowMs)
        if (w.fps.size < 2) return null
        sessBaseline = w.fps
        sessBaselineThermal = w.thermalMean
        sessBaselineAt = System.currentTimeMillis()
        return w
    }

    private suspend fun collectWindow(windowMs: Long): Window {
        val fps = mutableListOf<Int>()
        val temps = mutableListOf<Int>()
        val end = System.currentTimeMillis() + windowMs
        while (running && System.currentTimeMillis() < end) {
            sampler.poll()?.let {
                if (it.fps != null) fps.add(it.fps)
                temps.add(it.thermal)
            }
            delay(1_000)
        }
        return Window(fps, if (temps.isEmpty()) 0 else temps.average().toInt())
    }

    private suspend fun runTrial(task: BoostTask, ctx: BoostContext) {
        when (task.id) {
            "game_api_downscale" -> runVariantSweep(
                task, ctx,
                SWEEP_LEVELS.map { lvl ->
                    Variant("level=$lvl") { GameApiTask(level = lvl).apply(it) }
                }
            )
            "cpu_governor" -> runVariantSweep(
                task, ctx,
                GOVERNOR_VARIANTS.map { g ->
                    Variant("governor=$g") { GovernorTask(g).apply(it) }
                }
            )
            else -> runSingle(task, ctx)
        }
    }

    /** Plain single-variant trial. */
    private suspend fun runSingle(task: BoostTask, ctx: BoostContext) {
        val baseline = acquireBaseline(ctx, task.id)
        if (baseline == null) {
            recordNoData(task, ctx)
            return
        }
        val r = try {
            task.apply(ctx)
        } catch (e: Exception) {
            TaskResult(task.id, TaskStatus.Failed("adaptive trial: ${e.message}"))
        }
        when {
            r.entries.isNotEmpty() && r.status.success -> ctx.journal.add(r.entries)
            // The candidate cannot run on this device at all. Do NOT
            // measure: an "off vs off" window would accumulate fake
            // zero-pairs and eventually mark a fine task NEUTRAL.
            r.status == TaskStatus.Skipped || r.status is TaskStatus.Failed -> {
                ledger.record(
                    task.id, task.titleEn, emptyList(),
                    TrialOutcome(Decision.NEEDS_MORE, null, null, null,
                        ledger.entries[task.id]?.pairs ?: 0,
                        "cannot apply on this device (${r.detail})"),
                    System.currentTimeMillis(), cfg
                )
                ledger.save()
                log("adaptive ${task.id}: not applicable on this device — not measured")
                return
            }
            // NoChange: already applied from a previous session — the arm
            // window below still measures the real on-state. Fine.
            else -> Unit
        }
        delay(cfg.settleMs)
        val arm = collectWindow(cfg.windowMs)
        if (arm.fps.size < 2) {
            revertTask(task.id, ctx)
            return
        }
        val deltas = AdaptivePolicy.deltasOf(baseline.fps, arm.fps)
        val baseDeltas = ledger.entries[task.id]?.deltas ?: emptyList()
        val outcome = withThermalGuard(
            AdaptivePolicy.assess(baseDeltas + deltas, cfg),
            baseline.thermalMean, arm.thermalMean
        )
        val merged = ledger.record(task.id, task.titleEn, deltas,
            outcome, System.currentTimeMillis(), cfg)
        ledger.save()
        log(
            "adaptive ${task.id}: ${merged.decision} " +
                "mean=${merged.meanDelta?.let { String.format("%.2f", it) }} " +
                "pairs=${merged.pairs} sessions=${merged.sessions}"
        )
        when (merged.decision) {
            Decision.KEEP -> Unit
            else -> revertTask(task.id, ctx)
        }
    }

    /**
     * Variant sweep: one shared session baseline, then each variant measured
     * against it (device reset to default between arms). The best variant —
     * statistically — wins; its detail string (e.g. "level=0.8") persists in
     * the ledger and AppStore restores the winner on later sessions.
     */
    private suspend fun runVariantSweep(task: BoostTask, ctx: BoostContext, variants: List<Variant>) {
        val baseline = acquireBaseline(ctx, task.id)
        if (baseline == null) {
            recordNoData(task, ctx)
            return
        }
        val arms = mutableListOf<SweepArm>()
        val armThermals = mutableMapOf<String, Int>()
        for (v in variants) {
            if (!running) return
            revertTask(task.id, ctx)
            delay(cfg.settleMs)
            val r = try {
                v.apply(ctx)
            } catch (e: Exception) {
                TaskResult(task.id, TaskStatus.Failed("sweep ${v.detail}: ${e.message}"))
            }
            if (!(r.entries.isNotEmpty() && r.status.success)) continue
            delay(cfg.settleMs)
            val arm = collectWindow(cfg.windowMs)
            if (arm.fps.size < 2) {
                // never leave a variant applied while unmeasured
                revertTask(task.id, ctx)
                if (arms.isEmpty()) return
                break
            }
            arms += SweepArm(v.detail, AdaptivePolicy.deltasOf(baseline.fps, arm.fps))
            armThermals[v.detail] = arm.thermalMean
        }
        val best = pickBestArm(arms) ?: return
        val raw = AdaptivePolicy.assess(best.deltas, cfg)
        val outcome = withThermalGuard(
            raw, baseline.thermalMean, armThermals[best.level] ?: baseline.thermalMean
        )
        val merged = ledger.record(
            task.id, task.titleEn, best.deltas, outcome,
            System.currentTimeMillis(), cfg, detail = best.level
        )
        ledger.save()
        log(
            "adaptive sweep ${task.id}: winner=${best.level} " +
                "mean=${merged.meanDelta?.let { String.format("%.2f", it) }} " +
                "decision=${merged.decision} pairs=${merged.pairs}"
        )
        when (merged.decision) {
            Decision.KEEP -> {
                val winner = variants.firstOrNull { it.detail == best.level }
                if (winner != null) {
                    val r = try {
                        winner.apply(ctx)
                    } catch (e: Exception) {
                        null
                    }
                    if (r != null && r.entries.isNotEmpty() && r.status.success) {
                        ctx.journal.add(r.entries)
                    }
                }
            }
            else -> revertTask(task.id, ctx)
        }
    }

    /** A "win" that costs a full thermal tier is not a win. */
    private fun withThermalGuard(
        outcome: TrialOutcome,
        baselineThermal: Int,
        armThermal: Int
    ): TrialOutcome {
        if (outcome.decision == Decision.KEEP &&
            armThermal >= baselineThermal + THERMAL_REGRESSION_TIERS
        ) {
            return outcome.copy(decision = Decision.NEUTRAL, reason = "fps up but thermal regression")
        }
        return outcome
    }

    private fun recordNoData(task: BoostTask, ctx: BoostContext) {
        ledger.record(
            task.id, task.titleEn, emptyList(),
            AdaptivePolicy.assess(emptyList(), cfg),
            System.currentTimeMillis(), cfg
        )
        ledger.save()
        log("adaptive ${task.id}: no FPS data to measure")
    }

    /** Revert all journal entries belonging to one task. */
    fun revertTask(taskId: String, ctx: BoostContext) {
        val entries = ctx.journal.entries.filter { it.taskId == taskId }
        val ok = entries.filter { Journal.restore(it, ctx.executor) }
        if (ok.isNotEmpty()) ctx.journal.remove(ok)
    }
}
