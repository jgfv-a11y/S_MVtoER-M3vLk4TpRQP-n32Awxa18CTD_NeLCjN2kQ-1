package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Whitelist the game in Android's Doze (device idle) subsystem (v1.5).
 *
 * When the phone is idle (even while the game is in the foreground on
 * some OEM ROMs), Doze can throttle network stacks and job scheduling.
 * `cmd deviceidle whitelist <pkg>` exempts the game; the journal stores
 * a `whitelist-remove` revert so a restore puts the system back exactly.
 *
 * Needs the privileged shell (shell-uid `cmd deviceidle`). If the ROM
 * rejects it the task is Skipped, never Failed.
 */
class DeviceIdleTask : BoostTask {

    override val id = "device_idle"
    override val titleAr = "إعفاء اللعبة من خمول النظام"
    override val titleEn = "Exempt game from Doze"
    override val descAr = "يضيف اللعبة لقائمة استثناء Device Idle — لا تقييد للشبكة أو المهام أثناء اللعب"
    override val descEn = "Add the game to the device-idle exemption list — no network/job throttling in game"
    override val module = Module.POWER
    override val requiresPrivilege = true
    override val boostLevel = 2

    private fun pkg(ctx: BoostContext): String = ctx.profile.packageName

    override fun isSupported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        if (pkg(ctx).isBlank()) return false
        val r = ctx.executor.shell("cmd deviceidle help 2>&1")
        return r.ok && "whitelist" in r.stdout
    }

    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.journal.entries.any { it.taskId == id }

    override fun apply(ctx: BoostContext): TaskResult {
        val p = pkg(ctx)
        if (p.isBlank()) return TaskResult(id, TaskStatus.Skipped, "no game package set")
        if (!isSupported(ctx)) {
            return TaskResult(id, TaskStatus.Skipped, "deviceidle API not available on this ROM")
        }
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("cmd deviceidle whitelist $p 2>&1")
        if (!r.ok) {
            return TaskResult(id, TaskStatus.Skipped, "ROM rejected the deviceidle whitelist")
        }
        return TaskResult(
            id,
            TaskStatus.Applied,
            p,
            listOf(
                JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.CMD,
                    key = "device_idle:$p",
                    oldValue = null,
                    newValue = "whitelisted",
                    revertCmd = "cmd deviceidle whitelist-remove $p 2>/dev/null"
                )
            )
        )
    }
}
