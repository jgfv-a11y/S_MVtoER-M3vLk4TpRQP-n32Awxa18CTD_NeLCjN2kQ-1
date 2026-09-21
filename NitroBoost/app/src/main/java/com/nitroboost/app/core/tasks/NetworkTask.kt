package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Network: disable data saver (global no_background_data) so the game is
 * never deprioritized on the radio. Live retransmit metrics are surfaced in
 * the overlay by the monitor, not by this task.
 */
class NetworkTask : BoostTask {

    override val id = "data_saver"
    override val titleAr = "إيقاف موفر البيانات"
    override val titleEn = "Disable data saver"
    override val descAr = "منع النظام من تقييد الشبكة أثناء اللعب"
    override val descEn = "Stop the system from restricting the network while gaming"
    override val module = Module.NETWORK
    override val requiresPrivilege = true

    private fun current(ctx: BoostContext): String {
        val r = ctx.executor.shell("settings get global no_background_data")
        return r.stdout.trim()
    }

    override fun isSupported(ctx: BoostContext): Boolean = ctx.executor.privileged

    override fun isApplied(ctx: BoostContext): Boolean = current(ctx) == "0"

    override fun apply(ctx: BoostContext): TaskResult {
        val cur = current(ctx)
        if (cur == "0") return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("settings put global no_background_data 0")
        val now = current(ctx)
        return if (r.ok && now == "0") {
            TaskResult(
                id,
                TaskStatus.Applied,
                entries = listOf(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.GLOBAL_SETTING,
                        key = "no_background_data",
                        oldValue = if (cur.isEmpty() || cur == "null") "0" else cur,
                        newValue = "0",
                        revertCmd = "settings put global no_background_data ${if (cur.isEmpty() || cur == "null") 0 else cur}"
                    )
                )
            )
        } else {
            TaskResult(id, TaskStatus.Failed, "no_background_data not writable")
        }
    }
}
