package com.nitroboost.app.core

/**
 * Orchestrates boost tasks: applies only profile-enabled tasks, journals
 * every successful change, and restores everything from the journal.
 *
 * Safety rules:
 *  - a task failure never aborts the rest (report is returned instead);
 *  - a task that is already applied never re-applies (idempotent);
 *  - restore replays the journal in reverse order;
 *  - the journal is only cleared when every entry was restored.
 */
class BoostEngine(private val tasks: List<BoostTask>) {

    data class Report(
        val results: Map<String, TaskResult>,
        val appliedCount: Int,
        val noChangeCount: Int,
        val skippedCount: Int,
        val failedCount: Int
    ) {
        fun result(taskId: String): TaskResult? = results[taskId]
        val success: Boolean get() = failedCount == 0
    }

    fun taskFor(id: String): BoostTask? = tasks.firstOrNull { it.id == id }

    fun tasks(): List<BoostTask> = tasks

    /**
     * Apply every task enabled by the profile. Idempotent and
     * failure-tolerant. [exclude] lists task ids that must NOT be touched
     * right now (e.g. the candidate the adaptive engine is currently
     * measuring — a re-apply mid-trial would corrupt its arm window).
     */
    fun boost(ctx: BoostContext, exclude: Set<String> = emptySet()): Report {
        val results = linkedMapOf<String, TaskResult>()
        for (task in tasks) {
            val r = try {
                if (task.id in exclude) {
                    TaskResult(task.id, TaskStatus.Skipped, "reserved by adaptive trial")
                } else if (!ctx.profile.isEnabled(task)) {
                    TaskResult(task.id, TaskStatus.Skipped, "disabled in profile")
                } else {
                    task.apply(ctx)
                }
            } catch (e: Exception) {
                TaskResult(task.id, TaskStatus.Failed("unexpected: ${e.message}"))
            }
            results[task.id] = r
            if (r.entries.isNotEmpty() && r.status.success) {
                ctx.journal.add(r.entries)
            }
            ctx.log("${task.id} -> ${describe(r.status)} ${r.detail}")
        }
        return summarize(results)
    }

    /**
     * Restore the whole journal in reverse order.
     * Entries that fail to restore are kept in the journal so a later retry
     * can finish the job — nothing is lost.
     */
    fun restoreAll(ctx: BoostContext): Report {
        val pending = ctx.journal.entries.toList().asReversed()
        val restored = mutableListOf<JournalEntry>()
        val failed = mutableListOf<JournalEntry>()
        for (e in pending) {
            if (Journal.restore(e, ctx.executor)) restored.add(e) else failed.add(e)
        }
        if (restored.isNotEmpty()) ctx.journal.remove(restored)
        for (e in failed) ctx.log("restore failed: ${e.taskId}/${e.key}")
        return Report(
            results = emptyMap(),
            appliedCount = restored.size,
            noChangeCount = 0,
            skippedCount = 0,
            failedCount = failed.size
        )
    }

    /**
     * Thermal guard: drop the given modules' optimizations (reverting their
     * journal entries) when the device runs too hot.
     */
    fun deescalate(ctx: BoostContext, modules: Set<Module>): Int {
        if (modules.isEmpty()) return 0
        val toRemove = ctx.journal.entries.filter { entry ->
            taskFor(entry.taskId)?.module in modules
        }
        if (toRemove.isEmpty()) return 0
        val restored = mutableListOf<JournalEntry>()
        val failed = mutableListOf<JournalEntry>()
        for (e in toRemove.asReversed()) {
            if (Journal.restore(e, ctx.executor)) restored.add(e) else failed.add(e)
        }
        if (restored.isNotEmpty()) ctx.journal.remove(restored)
        for (e in failed) ctx.log("thermal de-escalate failed: ${e.taskId}/${e.key}")
        ctx.log("thermal de-escalation: dropped modules $modules")
        return restored.size
    }

    /** Live per-task state for the UI. */
    fun states(ctx: BoostContext): List<TaskState> = tasks.map { t ->
        val applied = try {
            ctx.journal.entries.any { it.taskId == t.id } || t.isApplied(ctx)
        } catch (e: Exception) {
            false
        }
        val supported = try {
            t.isSupported(ctx)
        } catch (e: Exception) {
            false
        }
        TaskState(
            id = t.id,
            titleAr = t.titleAr,
            titleEn = t.titleEn,
            descAr = t.descAr,
            descEn = t.descEn,
            module = t.module,
            applied = applied,
            supported = supported,
            requiresPrivilege = t.requiresPrivilege,
            pending = t.requiresPrivilege && !supported && !ctx.executor.privileged
        )
    }

    private fun summarize(results: Map<String, TaskResult>): Report {
        var applied = 0
        var noChange = 0
        var skipped = 0
        var failed = 0
        for (r in results.values) {
            when (r.status) {
                TaskStatus.Applied -> applied++
                TaskStatus.NoChange -> noChange++
                TaskStatus.Skipped -> skipped++
                is TaskStatus.Failed -> failed++
            }
        }
        return Report(results, applied, noChange, skipped, failed)
    }

    private fun describe(s: TaskStatus): String = when (s) {
        TaskStatus.Applied -> "applied"
        TaskStatus.NoChange -> "ok"
        TaskStatus.Skipped -> "skipped"
        is TaskStatus.Failed -> "failed"
    }
}
