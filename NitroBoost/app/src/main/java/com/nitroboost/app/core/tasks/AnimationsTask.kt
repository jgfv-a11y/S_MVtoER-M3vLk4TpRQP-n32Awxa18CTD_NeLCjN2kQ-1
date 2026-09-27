package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Kill the three system animation scales for a snappier UI while gaming.
 * Needs the user-granted "Modify system settings" (no Shizuku required).
 */
class AnimationsTask : BoostTask {

    override val id = "animations"
    override val titleAr = "تعطيل الأنيميشن"
    override val titleEn = "Kill animations"
    override val descAr = "تصفير مقاييس الحركة في النظام لاستجابة أسرع"
    override val descEn = "Zero the system animation scales for instant responses"
    override val module = Module.TWEAKS
    override val requiresPrivilege = false
    override val boostLevel = 1

    private val keys = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )

    override fun isSupported(ctx: BoostContext): Boolean = true

    override fun isApplied(ctx: BoostContext): Boolean =
        keys.all { ctx.executor.sysSettingGet(it) == "0.0" }

    override fun apply(ctx: BoostContext): TaskResult {
        val entries = mutableListOf<JournalEntry>()
        var changed = false
        var failed = false
        for (key in keys) {
            val current = ctx.executor.sysSettingGet(key)
            if (current == "0.0") continue
            if (ctx.executor.sysSettingPut(key, "0.0")) {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYS_SETTING,
                        key = key,
                        oldValue = current ?: "1.0",
                        newValue = "0.0"
                    )
                )
                changed = true
            } else {
                failed = true
            }
        }
        return when {
            failed && entries.isEmpty() ->
                TaskResult(id, TaskStatus.Failed("needs WRITE_SETTINGS"))
            entries.isNotEmpty() -> TaskResult(id, TaskStatus.Applied, entries = entries)
            changed -> TaskResult(id, TaskStatus.Applied)
            else -> TaskResult(id, TaskStatus.NoChange)
        }
    }
}
