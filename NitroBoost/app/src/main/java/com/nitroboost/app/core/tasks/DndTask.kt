package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.DndFilters
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Do-Not-Disturb while gaming: only alarms get through.
 * Needs the user-granted "Notification access" (no Shizuku required).
 */
class DndTask : BoostTask {

    override val id = "dnd"
    override val titleAr = "عدم الإزعاج"
    override val titleEn = "Do Not Disturb"
    override val descAr = "إخفاء كل الإشعارات أثناء اللعب عدا المنبهات"
    override val descEn = "Hide all notifications while gaming, alarms still ring"
    override val module = Module.DND
    override val requiresPrivilege = false

    override fun isSupported(ctx: BoostContext): Boolean = true

    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.executor.dndFilterGet() == DndFilters.PRIORITY

    override fun apply(ctx: BoostContext): TaskResult {
        val current = ctx.executor.dndFilterGet()
        if (current == DndFilters.PRIORITY) {
            return TaskResult(id, TaskStatus.NoChange)
        }
        return if (ctx.executor.dndFilterSet(DndFilters.PRIORITY)) {
            TaskResult(
                id,
                TaskStatus.Applied,
                entries = listOf(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.DND,
                        key = "interruption_filter",
                        oldValue = current.toString(),
                        newValue = DndFilters.PRIORITY.toString()
                    )
                )
            )
        } else {
            TaskResult(id, TaskStatus.Failed, "DND permission not granted")
        }
    }
}
