package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * WALT scheduler tuning for lower frame-pacing jitter:
 *  - sched_latency_ns        -> 1ms   (faster reaction to load spikes)
 *  - sched_min_granularity_ns-> 250us (finer per-task cost accounting)
 *  - sched_child_runs_first  -> 1     (children inherit the parent's CPU class)
 * Only nodes that exist on the ROM are touched; every write is journaled.
 */
class WaltSchedulerTask : BoostTask {

    override val id = "walt_tuning"
    override val titleAr = "ضبط مجدول WALT"
    override val titleEn = "WALT scheduler tuning"
    override val descAr = "استجابة أسرع للتغير في الأحمال وتوزيع أدق للمهام بين الأنوية"
    override val descEn = "Tighter latency/granularity so the scheduler reacts faster to load spikes"
    override val module = Module.TWEAKS
    override val requiresPrivilege = true

    private data class Node(val path: String, val target: String)

    private val nodes = listOf(
        Node("/proc/sys/kernel/sched_latency_ns", "1000000"),
        Node("/proc/sys/kernel/sched_min_granularity_ns", "250000"),
        Node("/proc/sys/kernel/sched_child_runs_first", "1")
    )

    private fun active(ctx: BoostContext): List<Node> =
        nodes.filter { ctx.executor.readSys(it.path) != null }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && active(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val a = active(ctx)
        return a.isNotEmpty() && a.all { ctx.executor.readSys(it.path) == it.target }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val a = active(ctx)
        if (a.isEmpty()) return TaskResult(id, TaskStatus.Skipped, "no WALT sysctls on this ROM")
        val entries = mutableListOf<JournalEntry>()
        for (n in a) {
            val cur = ctx.executor.readSys(n.path) ?: continue
            if (cur == n.target) continue
            if (ctx.executor.writeSys(n.path, n.target)) {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = n.path,
                        oldValue = cur,
                        newValue = n.target
                    )
                )
            }
        }
        return if (entries.isEmpty()) {
            TaskResult(id, TaskStatus.NoChange, "already tuned")
        } else {
            TaskResult(id, TaskStatus.Applied, "${entries.size} sysctl(s)", entries = entries)
        }
    }
}
