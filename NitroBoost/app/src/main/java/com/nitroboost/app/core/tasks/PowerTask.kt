package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Disable battery saver so the SoC is never held back while gaming.
 * Requires a privileged shell (Settings.Global.low_power is shell-writable).
 */
class PowerTask : BoostTask {

    override val id = "battery_saver"
    override val titleAr = "إيقاف توفير البطارية"
    override val titleEn = "Disable battery saver"
    override val descAr = "منع النظام من تقليل أداء المعالج بسبب البطارية"
    override val descEn = "Stop the system from throttling the SoC for battery"
    override val module = Module.POWER
    override val requiresPrivilege = true

    private fun current(ctx: BoostContext): String {
        val r = ctx.executor.shell("settings get global low_power")
        return r.stdout.trim()
    }

    override fun isSupported(ctx: BoostContext): Boolean = ctx.executor.privileged

    override fun isApplied(ctx: BoostContext): Boolean = current(ctx) == "0"

    override fun apply(ctx: BoostContext): TaskResult {
        val cur = current(ctx)
        if (cur == "0") return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("settings put global low_power 0")
        val now = current(ctx)
        return if (r.ok && now == "0") {
            TaskResult(
                id,
                TaskStatus.Applied,
                entries = listOf(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.GLOBAL_SETTING,
                        key = "low_power",
                        oldValue = if (cur.isEmpty() || cur == "null") "0" else cur,
                        newValue = "0",
                        revertCmd = "settings put global low_power ${if (cur.isEmpty() || cur == "null") 0 else cur}"
                    )
                )
            )
        } else {
            TaskResult(id, TaskStatus.Failed, "low_power setting not writable")
        }
    }
}
