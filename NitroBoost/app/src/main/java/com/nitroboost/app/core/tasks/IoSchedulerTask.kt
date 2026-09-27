package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Low-latency block-I/O scheduler during a session (v1.5).
 *
 * Games do a lot of small random reads (level streaming, textures,
 * replays). The default scheduler optimizes throughput/batching at the
 * cost of tail latency. `none` (mq direct dispatch, no reordering) is
 * the lowest-latency option on blk-mq kernels; it is restored on exit,
 * so sequential workloads keep their batched default.
 *
 * The main user-data block device is probed (mmcblk / sda / nvme). Some
 * kernels report the full option list ("[mq-deadline] kyber none"), some
 * only the active token after a switch ("none") — both forms are handled.
 * Blocked-rom semantics: no writable node or a locked node = Skipped,
 * never Failed.
 */
class IoSchedulerTask : BoostTask {

    override val id = "io_scheduler"
    override val titleAr = "جدول I/O منخفض التأخير"
    override val titleEn = "Low-latency I/O scheduler"
    override val descAr = "يحوّل تخزين البيانات إلى وضع none أثناء الجلسة — قراءة ملفات اللعبة أسرع استجابة"
    override val descEn = "Switch the data storage to 'none' for the session — snappier game file reads"
    override val module = Module.TWEAKS
    override val requiresPrivilege = true
    override val boostLevel = 2

    companion object {
        const val TARGET = "none"
        /** Most common user-data block devices, probed in order. */
        val CANDIDATES = listOf("mmcblk0", "sda", "nvme0n1")
    }

    private data class SchedNode(val dev: String, val active: String, val options: List<String>)

    private fun path(dev: String): String = "/sys/block/$dev/queue/scheduler"

    /** Active scheduler = token inside brackets, else the whole value. */
    private fun active(value: String): String {
        val m = Regex("\\[([^\\]]+)\\]").find(value)
        return (m?.groupValues?.get(1) ?: value.trim()).trim()
    }

    private fun optionsOf(value: String): List<String> =
        value.trim().removePrefix("[").removeSuffix("]").trim().split(' ').filter { it.isNotEmpty() }

    /** First schedulable block device, or null when none is visible. */
    private fun detect(ctx: BoostContext): SchedNode? {
        for (dev in CANDIDATES) {
            val v = ctx.executor.readSys(path(dev)) ?: continue
            if (v.isBlank()) continue
            return SchedNode(dev, active(v), optionsOf(v))
        }
        return null
    }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && detect(ctx) != null

    override fun isApplied(ctx: BoostContext): Boolean {
        val node = detect(ctx) ?: return false
        return node.active == TARGET
    }

    override fun apply(ctx: BoostContext): TaskResult {
        if (!ctx.executor.privileged) {
            return TaskResult(id, TaskStatus.Skipped, "needs Shizuku or root")
        }
        val node = detect(ctx)
            ?: return TaskResult(id, TaskStatus.Skipped, "no schedulable block device visible to shell")
        if (node.active == TARGET) return TaskResult(id, TaskStatus.NoChange, "already $TARGET")
        if (node.options.size < 2) {
            return TaskResult(id, TaskStatus.Skipped, "device offers a single scheduler (${node.active})")
        }
        val p = path(node.dev)
        val ok = ctx.executor.writeSys(p, TARGET) &&
            ctx.executor.readSys(p)?.let { active(it) == TARGET } == true
        return if (ok) {
            TaskResult(
                id,
                TaskStatus.Applied,
                "${node.dev}: ${node.active} -> $TARGET",
                listOf(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = p,
                        oldValue = node.active,
                        newValue = TARGET
                    )
                )
            )
        } else {
            TaskResult(id, TaskStatus.Skipped, "ROM locks the scheduler on ${node.dev}")
        }
    }
}
