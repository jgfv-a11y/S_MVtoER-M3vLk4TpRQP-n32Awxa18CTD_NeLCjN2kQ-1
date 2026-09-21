package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * AGGRESSIVE, opt-in only: override the thermal controller so it never
 * throttles. The device WILL get hot and drain battery faster.
 *
 * Safety net: ThermalGuard reverts this automatically when the device hits
 * severe heat, so even a careless user cannot brick the device.
 */
class ThermalOverrideTask : BoostTask {

    override val id = "thermal_override"
    override val titleAr = "تجاوز التبريد (مجازف)"
    override val titleEn = "Thermal override (aggressive)"
    override val descAr = "إيقاف تقليص الأداء الحراري — حرارة أعلى وبطارية أسرع (مراقبة أوتوماتيكية)"
    override val descEn = "Disable thermal throttling — hotter device, faster drain (auto-guarded)"
    override val module = Module.THERMAL
    override val requiresPrivilege = true

    private fun probe(ctx: BoostContext): Boolean =
        ctx.executor.shell("cmd thermalservice override-status").ok

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && probe(ctx)

    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.journal.entries.any { it.taskId == id }

    override fun apply(ctx: BoostContext): TaskResult {
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("cmd thermalservice override-status 0")
        if (!r.ok) {
            return TaskResult(id, TaskStatus.Failed, "thermalservice not controllable on this ROM")
        }
        return TaskResult(
            id,
            TaskStatus.Applied,
            entries = listOf(
                JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.THERMAL_OVERRIDE,
                    key = "thermal_override",
                    oldValue = "-1",
                    newValue = "0",
                    revertCmd = "cmd thermalservice override-status -1"
                )
            )
        )
    }
}
