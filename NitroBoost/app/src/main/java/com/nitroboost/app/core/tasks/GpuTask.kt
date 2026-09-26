package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * GPU clock lock for Adreno GPUs (force_clk_on / force_bus_on) plus the
 * OEM touch-boost sysfs nodes where they exist (vivo / oplus).
 * Every node is probed first; non-existent nodes are silently skipped.
 * The ThermalGuard reverts this task first when the device runs hot.
 */
class GpuTask : BoostTask {

    override val id = "gpu_boost"
    override val titleAr = "رفع أداء الرسوميات"
    override val titleEn = "GPU boost"
    override val descAr = "قفل ترددات GPU (Adreno) وتسريع اللمس إن توفر"
    override val descEn = "Lock GPU clocks (Adreno) and enable touch boost where available"
    override val module = Module.GPU
    override val requiresPrivilege = true

    private data class Node(val path: String, val on: String, val off: String)

    private val nodes = listOf(
        Node("/sys/class/kgsl/kgsl-3d0/force_clk_on", "1", "0"),
        Node("/sys/class/kgsl/kgsl-3d0/force_bus_on", "1", "0"),
        Node("/sys/kernel/vivo_touch/touch_boost", "1", "0"),
        Node("/sys/kernel/oplus_touch/touch_boost", "1", "0")
    )

    private fun readable(ctx: BoostContext): List<Node> =
        nodes.filter { n -> ctx.executor.readSys(n.path) != null }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && readable(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val active = readable(ctx)
        return active.isNotEmpty() && active.all { n -> ctx.executor.readSys(n.path) == n.on }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val active = readable(ctx)
        if (active.isEmpty()) {
            return TaskResult(id, TaskStatus.Skipped, "no GPU sysfs nodes on this device")
        }
        val entries = mutableListOf<JournalEntry>()
        var touched = 0
        var attempted = 0
        for (n in active) {
            val current = ctx.executor.readSys(n.path) ?: continue
            if (current == n.on) continue
            attempted++
            if (ctx.executor.writeSys(n.path, n.on)) {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = n.path,
                        oldValue = current,
                        newValue = n.on,
                        revertCmd = "echo ${n.off} > \"${n.path}\" 2>/dev/null"
                    )
                )
                touched++
            }
        }
        return when {
            touched > 0 -> TaskResult(id, TaskStatus.Applied, "${touched} node(s)", entries = entries)
            attempted > 0 -> TaskResult(
                id,
                TaskStatus.Skipped,
                "ROM blocks these GPU nodes on this device"
            )
            else -> TaskResult(id, TaskStatus.NoChange, "GPU already boosted")
        }
    }
}
