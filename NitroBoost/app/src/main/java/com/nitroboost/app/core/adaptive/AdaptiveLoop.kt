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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * What the loop can observe. Implemented by AppStore over the monitor
 * snapshots; the loop itself never touches Android classes, which is what
 * keeps the decision pipeline unit-testable.
 */
interface AdaptiveSampler {
    /** Latest measured FPS of the game, or null (no Shizuku / priming). */
    fun fps(): Int?
    /** Latest frame metrics, or null before the first sample. */
    fun metrics(): FrameMetrics?
    fun privileged(): Boolean
}

/**
 * The adaptive engine.
 *
 * During a boost session it A/B tests every profile-enabled performance
 * tweak on the real device:
 *
 *   1. revert the candidate (journal) -> settle -> measure baseline window
 *   2. apply the candidate (journal)  -> settle -> measure arm window
 *   3. paired deltas  ->  DecisionLedger (accumulates across sessions)
 *   4. verdict:
 *        KEEP       -> stays applied
 *        DROP       -> reverted, and AppStore.boost() will not re-apply it
 *        NEUTRAL    -> reverted (clean system, same FPS)
 *        NEEDS_MORE -> reverted now, measured again next session
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
    private val log: (String) -> Unit = {}
) {

    companion object {
        /** Only performance-relevant modules are worth an A/B trial. */
        val TRIAL_MODULES = setOf(
            Module.CPU, Module.GPU, Module.TWEAKS, Module.NETWORK
        )

        /**
         * The downscale sweep: legal AOSP ratios, mildest first. Each level
         * is measured against the SAME baseline; the engine keeps the level
         * whose measured delta is best — the real "dynamic resolution"
         * control point for a userland booster.
         */
        val SWEEP_LEVELS = listOf("0.9", "0.8", "0.7")

        /** One measured arm of the downscale sweep. */
        data class SweepArm(val level: String, val deltas: List<Double>) {
            val mean: Double
                get() = if (deltas.isEmpty()) Double.NEGATIVE_INFINITY else deltas.average()
        }

        /** Pure: best arm = highest mean delta; null when nothing measured. */
        fun pickBestArm(arms: List<SweepArm>): SweepArm? = arms.maxByOrNull { it.mean }
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

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
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
        log("adaptive engine stopped")
    }

    private suspend fun loop() {
        while (running) {
            try {
                step()
            } catch (e: Exception) {
                log("adaptive step failed: ${e.message}")
            }
            delay(2_000)
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

    /** Next profile-enabled, unresolved trial candidate, in task order. */
    fun nextCandidate(ctx: BoostContext): BoostTask? =
        engine.tasks().firstOrNull { t ->
            t.module in TRIAL_MODULES &&
                t.requiresPrivilege &&
                ctx.profile.isEnabled(t) &&
                !ledger.isResolved(t.id)
        }

    private suspend fun runTrial(task: BoostTask, ctx: BoostContext) {
        if (task.id == "game_api_downscale") {
            runSweep(task, ctx)
            return
        }
        // 1) baseline: candidate must be OFF
        revertTask(task.id, ctx)
        delay(cfg.settleMs)
        val baseline = collectFps(cfg.windowMs)
        if (baseline.size < 2) {
            // FPS source not producing data — record and retry next session
            ledger.record(
                task.id, task.titleEn, emptyList(),
                AdaptivePolicy.assess(emptyList(), cfg),
                System.currentTimeMillis(), cfg
            )
            ledger.save()
            log("adaptive ${task.id}: no FPS data to measure")
            return
        }
        // 2) arm: candidate ON
        val r = try {
            task.apply(ctx)
        } catch (e: Exception) {
            TaskResult(task.id, TaskStatus.Failed("adaptive trial: ${e.message}"))
        }
        if (r.entries.isNotEmpty() && r.status.success) ctx.journal.add(r.entries)
        delay(cfg.settleMs)
        val arm = collectFps(cfg.windowMs)
        if (arm.size < 2) {
            revertTask(task.id, ctx)
            return
        }
        // 3) decide over the accumulated pairs (this session + previous)
        val deltas = AdaptivePolicy.deltasOf(baseline, arm)
        val baseDeltas = ledger.entries[task.id]?.deltas ?: emptyList()
        val outcome = AdaptivePolicy.assess(baseDeltas + deltas, cfg)
        val merged = ledger.record(task.id, task.titleEn, deltas,
            outcome, System.currentTimeMillis(), cfg)
        ledger.save()
        log(
            "adaptive ${task.id}: ${merged.decision} " +
                "mean=${merged.meanDelta?.let { String.format("%.2f", it) }} " +
                "pairs=${merged.pairs} sessions=${merged.sessions}"
        )
        // 4) leave the system clean except for KEEP verdicts
        when (merged.decision) {
            Decision.KEEP -> Unit
            else -> revertTask(task.id, ctx)
        }
    }

    /**
     * Dynamic-resolution sweep: one shared baseline (no override), then each
     * legal downscale level measured against it. The winning level (best
     * measured mean delta) is kept and journaled; its verdict and the level
     * itself persist in the ledger so the normal boost path can restore the
     * winner on later sessions (see AppStore.honorLedger).
     */
    private suspend fun runSweep(task: BoostTask, ctx: BoostContext) {
        // shared baseline
        revertTask(task.id, ctx)
        delay(cfg.settleMs)
        val baseline = collectFps(cfg.windowMs)
        if (baseline.size < 2) {
            ledger.record(
                task.id, task.titleEn, emptyList(),
                AdaptivePolicy.assess(emptyList(), cfg),
                System.currentTimeMillis(), cfg
            )
            ledger.save()
            log("adaptive sweep ${task.id}: no FPS data to measure")
            return
        }
        // arms: one per legal level, each starting from device default
        val arms = mutableListOf<SweepArm>()
        for (level in SWEEP_LEVELS) {
            if (!running) return
            val t = GameApiTask(level = level)
            val r = try {
                t.apply(ctx)
            } catch (e: Exception) {
                TaskResult(task.id, TaskStatus.Failed("sweep $level: ${e.message}"))
            }
            if (!(r.entries.isNotEmpty() && r.status.success)) {
                // level rejected by the platform (or task unsupported)
                revertTask(task.id, ctx)
                continue
            }
            delay(cfg.settleMs)
            val arm = collectFps(cfg.windowMs)
            // reset BEFORE the next arm so every level starts equal
            revertTask(task.id, ctx)
            delay(cfg.settleMs)
            if (arm.size < 2) {
                if (arms.isEmpty()) return
                break
            }
            arms += SweepArm(level, AdaptivePolicy.deltasOf(baseline, arm))
        }
        val best = pickBestArm(arms) ?: return
        val outcome = AdaptivePolicy.assess(best.deltas, cfg)
        val merged = ledger.record(
            task.id, task.titleEn, best.deltas, outcome,
            System.currentTimeMillis(), cfg, detail = "level=$best.level"
        )
        ledger.save()
        log(
            "adaptive sweep ${task.id}: winner=${best.level} " +
                "mean=${merged.meanDelta?.let { String.format("%.2f", it) }} " +
                "decision=${merged.decision} pairs=${merged.pairs}"
        )
        when (merged.decision) {
            Decision.KEEP -> {
                val t = GameApiTask(level = best.level)
                val r = t.apply(ctx)
                if (r.entries.isNotEmpty() && r.status.success) ctx.journal.add(r.entries)
            }
            else -> revertTask(task.id, ctx)
        }
    }

    /** Revert all journal entries belonging to one task. */
    fun revertTask(taskId: String, ctx: BoostContext) {
        val entries = ctx.journal.entries.filter { it.taskId == taskId }
        val ok = entries.filter { Journal.restore(it, ctx.executor) }
        if (ok.isNotEmpty()) ctx.journal.remove(ok)
    }

    private suspend fun collectFps(windowMs: Long): List<Int> {
        val out = mutableListOf<Int>()
        val end = System.currentTimeMillis() + windowMs
        while (running && System.currentTimeMillis() < end) {
            sampler.fps()?.let { out.add(it) }
            delay(1_000)
        }
        return out
    }
}
