package com.nitroboost.app.core

import java.util.concurrent.ConcurrentHashMap

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

    private val taskMutationLocks = ConcurrentHashMap<String, Any>()

    /** Serialize a task's apply/restore across normal boost and adaptive paths. */
    fun <T> withTaskLock(taskId: String, block: () -> T): T {
        val lock = taskMutationLocks.computeIfAbsent(taskId) { Any() }
        return synchronized(lock) { block() }
    }

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
     * Apply one task while write-ahead journaling every supported mutation.
     * Adaptive candidates and ledger-selected variants use the same path as a
     * normal boost so cancellation/exception cleanup sees partial writes.
     */
    fun applyWithJournal(
        taskId: String,
        ctx: BoostContext,
        apply: (BoostContext) -> TaskResult
    ): TaskResult = withTaskLock(taskId) {
        recordMissingEntries(ctx, apply(ctx.forTask(taskId)))
    }

    private fun recordMissingEntries(ctx: BoostContext, result: TaskResult): TaskResult {
        val missing = result.entries.filterNot { entry ->
            ctx.journal.containsTaskKey(entry.taskId, entry.key)
        }
        if (missing.isEmpty()) return result
        if (ctx.journal.add(missing)) return result

        var restored = true
        for (entry in missing.asReversed()) {
            if (!Journal.restore(entry, ctx.executor)) restored = false
        }
        val journalCleared = restored && ctx.journal.remove(missing)
        val detail = if (restored && journalCleared) {
            "journal persistence failed; change was rolled back"
        } else {
            "journal persistence failed; rollback incomplete and reversal retained"
        }
        return TaskResult(
            taskId = result.taskId,
            status = TaskStatus.Failed(detail),
            detail = detail,
            entries = if (restored && journalCleared) emptyList() else missing
        )
    }

    /**
     * Apply every task enabled by the profile. Idempotent and
     * failure-tolerant. [exclude] lists task ids that must NOT be touched
     * right now (e.g. the candidate the adaptive engine is currently
     * measuring — a re-apply mid-trial would corrupt its arm window).
     */
    @Synchronized
    fun boost(ctx: BoostContext, exclude: Set<String> = emptySet(), maxLevel: Int = 3): Report {
        val results = linkedMapOf<String, TaskResult>()
        for (task in tasks) {
            val r = withTaskLock(task.id) {
                try {
                    if (ctx.journal.recoveryBlocked) {
                        TaskResult(task.id, TaskStatus.Failed("journal recovery is blocked by corrupt or unreadable data"))
                    } else if (task.id in exclude) {
                        TaskResult(task.id, TaskStatus.Skipped, "reserved by adaptive trial")
                    } else if (task.boostLevel > maxLevel) {
                        TaskResult(task.id, TaskStatus.Skipped,
                            "boost level too low (needs ${task.boostLevel})")
                    } else if (!ctx.profile.isEnabled(task)) {
                        TaskResult(task.id, TaskStatus.Skipped, "disabled in profile")
                    } else if (task.requiresPrivilege && !ctx.executor.privileged) {
                        TaskResult(task.id, TaskStatus.Skipped, "needs Shizuku or root")
                    } else {
                        applyWithJournal(task.id, ctx) { taskContext -> task.apply(taskContext) }
                    }
                } catch (e: Exception) {
                    TaskResult(task.id, TaskStatus.Failed("unexpected: ${e.message}"))
                }
            }
            results[task.id] = r
            ctx.log("${task.id} -> ${describe(r.status)} ${r.detail}")
        }
        return summarize(results)
    }

    /**
     * Restore the whole journal in reverse order.
     * Entries that fail to restore are kept in the journal so a later retry
     * can finish the job — nothing is lost.
     */
    @Synchronized
    fun restoreAll(ctx: BoostContext): Report {
        if (ctx.journal.recoveryBlocked) {
            ctx.log("restore blocked: journal recovery state is corrupt or unreadable")
            return Report(emptyMap(), 0, 0, 0, 1)
        }
        val pending = ctx.journal.snapshot().asReversed()
        val restored = mutableListOf<JournalEntry>()
        val failed = mutableListOf<JournalEntry>()
        for (e in pending) {
            val ok = withTaskLock(e.taskId) { Journal.restore(e, ctx.executor) }
            if (ok) restored.add(e) else failed.add(e)
        }
        var journalCommitFailed = false
        if (restored.isNotEmpty() && !ctx.journal.remove(restored)) {
            journalCommitFailed = true
            ctx.log("restore completed but journal removal was not durable; entries retained for retry")
        }
        for (e in failed) ctx.log("restore failed: ${e.taskId}/${e.key}")
        return Report(
            results = emptyMap(),
            appliedCount = restored.size,
            noChangeCount = 0,
            skippedCount = 0,
            failedCount = failed.size + if (journalCommitFailed) restored.size.coerceAtLeast(1) else 0
        )
    }

    /**
     * Thermal guard: drop the given modules' optimizations (reverting their
     * journal entries) when the device runs too hot.
     */
    @Synchronized
    fun deescalate(ctx: BoostContext, modules: Set<Module>): Int {
        if (modules.isEmpty()) return 0
        val toRemove = ctx.journal.snapshot().filter { entry ->
            taskFor(entry.taskId)?.module in modules
        }
        if (toRemove.isEmpty()) return 0
        val restored = mutableListOf<JournalEntry>()
        val failed = mutableListOf<JournalEntry>()
        for (e in toRemove.asReversed()) {
            val ok = withTaskLock(e.taskId) { Journal.restore(e, ctx.executor) }
            if (ok) restored.add(e) else failed.add(e)
        }
        if (restored.isNotEmpty() && !ctx.journal.remove(restored)) {
            ctx.log("thermal de-escalation restored changes but could not durably remove journal entries")
        }
        for (e in failed) ctx.log("thermal de-escalate failed: ${e.taskId}/${e.key}")
        ctx.log("thermal de-escalation: dropped modules $modules")
        return restored.size
    }

    /** Live per-task state for the UI. */
    fun states(ctx: BoostContext): List<TaskState> {
        val journalEntries = ctx.journal.snapshot()
        return tasks.map { t ->
            val applied = try {
                when {
                    journalEntries.any { it.taskId == t.id } -> true
                    t.requiresPrivilege && !ctx.executor.privileged -> false
                    else -> t.isApplied(ctx)
                }
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
