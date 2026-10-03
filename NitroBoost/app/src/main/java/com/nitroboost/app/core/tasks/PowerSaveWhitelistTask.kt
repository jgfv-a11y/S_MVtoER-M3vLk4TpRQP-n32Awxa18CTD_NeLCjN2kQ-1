package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.ShellInput
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Whitelist the game in the device-idle (Doze) policy so background
 * maintenance jobs never pause or throttle it mid-session.
 * Shell command based (no privileged binder needed beyond Shizuku).
 */
class PowerSaveWhitelistTask : BoostTask {

    override val id = "doze_whitelist"
    override val titleAr = "حماية اللعبة من توفير البطارية"
    override val titleEn = "Doze / battery whitelist"
    override val descAr = "إدراج اللعبة في قائمة أفضلية الجهاز لمنع Doze من إبطائها"
    override val descEn = "Add the game to the device-idle whitelist so Doze cannot throttle it"
    override val module = Module.POWER
    override val requiresPrivilege = true

    private fun pkg(ctx: BoostContext): String = ctx.profile.packageName

    override fun isSupported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        if (!ShellInput.isPackageName(pkg(ctx))) return false
        return ctx.executor.shell("cmd deviceidle whitelist").ok
    }

    override fun isApplied(ctx: BoostContext): Boolean {
        val packageName = pkg(ctx)
        if (!ShellInput.isPackageName(packageName)) return false
        val r = ctx.executor.shell("dumpsys deviceidle whitelist")
        if (!r.ok) return false
        val wanted = Regex("(^|\\s)${Regex.escape(packageName)}(\\s|$)")
        return r.stdout.lineSequence().any { wanted.containsMatchIn(it) }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val p = pkg(ctx)
        if (!ShellInput.isPackageName(p)) return TaskResult(id, TaskStatus.Skipped, "invalid game package")
        if (!ctx.executor.privileged) return TaskResult(id, TaskStatus.Skipped, "needs Shizuku or root")
        if (!isSupported(ctx)) return TaskResult(id, TaskStatus.Skipped, "deviceidle whitelist unavailable")
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("cmd deviceidle whitelist +$p 2>/dev/null")
        if (!r.ok) {
            return TaskResult(id, TaskStatus.Failed("deviceidle whitelist not writable on this ROM"))
        }
        return TaskResult(
            id,
            TaskStatus.Applied,
            entries = listOf(
                JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.CMD,
                    key = "doze_whitelist:$p",
                    oldValue = "absent",
                    newValue = "present",
                    revertCmd = "cmd deviceidle whitelist -$p 2>/dev/null"
                )
            )
        )
    }
}
