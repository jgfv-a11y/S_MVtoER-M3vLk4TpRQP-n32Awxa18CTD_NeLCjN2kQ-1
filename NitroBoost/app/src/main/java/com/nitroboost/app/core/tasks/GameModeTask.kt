package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * AOSP Game Mode (secure setting "game_mode").
 * Levels: 0 off .. 5 max depending on the ROM; the profile carries the level.
 * Requires a privileged shell.
 */
class GameModeTask : BoostTask {

    override val id = "game_mode"
    override val titleAr = "وضع اللعبة"
    override val titleEn = "Game Mode"
    override val descAr = "تفعيل وضع اللعبة في النظام (أولوية كاملة للعبة)"
    override val descEn = "System Game Mode — full priority for the game"
    override val module = Module.CPU
    override val requiresPrivilege = true

    private fun current(ctx: BoostContext): Int {
        val r = ctx.executor.shell("settings get secure game_mode")
        return r.stdout.trim().toIntOrNull() ?: -1
    }

    override fun isSupported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        return ctx.executor.shell("settings get secure game_mode").ok
    }

    override fun isApplied(ctx: BoostContext): Boolean {
        val target = ctx.profile.gameMode
        return target > 0 && current(ctx) == target
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val target = ctx.profile.gameMode
        if (target <= 0) return TaskResult(id, TaskStatus.Skipped, "profile game mode off")
        val cur = current(ctx)
        if (cur == target) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("settings put secure game_mode $target")
        val now = current(ctx)
        return if (r.ok && now == target) {
            TaskResult(
                id,
                TaskStatus.Applied,
                "$cur -> $target",
                entries = listOf(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SECURE_SETTING,
                        key = "game_mode",
                        oldValue = if (cur < 0) "0" else cur.toString(),
                        newValue = target.toString(),
                        revertCmd = "settings put secure game_mode ${if (cur < 0) 0 else cur}"
                    )
                )
            )
        } else {
            TaskResult(id, TaskStatus.Failed("game_mode setting not writable on this ROM"))
        }
    }
}
