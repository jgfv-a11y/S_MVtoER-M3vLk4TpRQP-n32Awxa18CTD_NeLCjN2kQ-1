package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Touch boost: raise the input boost duration so the CPU jumps up the
 * moment a finger lands — measurable input-latency reduction on
 * Qualcomm/Exynos kernels that expose the cpu_boost interface.
 */
class TouchBoostTask : BoostTask {

    override val id = "touch_boost"
    override val titleAr = "تسريع الاستجابة للمس"
    override val titleEn = "Touch boost (input latency)"
    override val descAr = "رفع تردد المعالج فور لمس الشاشة — استجابة أسرع في اللحظات الحاسمة"
    override val descEn = "Boost the CPU the instant touch input lands — lower input latency"
    override val module = Module.TWEAKS
    override val requiresPrivilege = true

    private data class Node(val path: String, val target: String)

    private val nodes = listOf(
        Node("/sys/devices/system/cpu/cpu_boost/boost_ms", "200"),
        Node("/sys/module/cpu_boost/parameters/input_boost_ms", "200")
    )

    private fun active(ctx: BoostContext): List<Node> =
        nodes.filter { ctx.executor.readSys(it.path) != null }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && active(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val a = active(ctx)
        return a.isNotEmpty() && a.all {
            (ctx.executor.readSys(it.path)?.toLongOrNull() ?: 0L) >= 200L
        }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val a = active(ctx)
        if (a.isEmpty()) return TaskResult(id, TaskStatus.Skipped, "no cpu_boost interface on this kernel")
        val entries = mutableListOf<JournalEntry>()
        var attempted = 0
        for (n in a) {
            val cur = ctx.executor.readSys(n.path) ?: continue
            val curV = cur.toLongOrNull() ?: continue
            if (curV >= 200L) continue
            attempted++
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
        return when {
            entries.isNotEmpty() -> TaskResult(id, TaskStatus.Applied, "${entries.size} node(s)", entries = entries)
            attempted > 0 -> TaskResult(
                id,
                TaskStatus.Skipped,
                "cpu_boost nodes are read-only on this kernel"
            )
            else -> TaskResult(id, TaskStatus.NoChange, "already boosted")
        }
    }
}
