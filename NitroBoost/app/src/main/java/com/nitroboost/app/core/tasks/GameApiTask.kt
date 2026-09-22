package com.nitroboost.app.core.tasks

import android.os.Build
import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Official Android 12+ Game Manager: ask the platform to downscale the
 * game's rendering at 80% (GPU does 36% less pixel work → cooler device,
 * steadier frames on mid/low SoCs).
 *
 * Guarded end-to-end:
 *  - Android 12+ (SDK 31) and a privileged shell only;
 *  - `cmd game help` must exist and list `set` (some OEM ROMs strip it);
 *  - the platform ignores the setting for apps that do not declare game
 *    modes, so a no-op can never harm a non-game app;
 *  - revert is `cmd game reset <pkg>` (back to device configuration).
 *
 * The platform exposes no getter for the active override, so "applied" is
 * tracked through the journal entry — which is also what makes the revert
 * precise.
 */
class GameApiTask(private val sdk: Int = Build.VERSION.SDK_INT) : BoostTask {

    override val id = "game_api_downscale"
    override val titleAr = "تخفيف دقة الرسوميات (Game API)"
    override val titleEn = "Render downscale (Game API)"
    override val descAr = "يطلب من النظام رسم اللعبة بنسبة 80% — أبرد وجوٌد إطارات أكثر ثباتًا"
    override val descEn = "Ask the platform to render the game at 80% — cooler, steadier frames"
    override val module = Module.GPU
    override val requiresPrivilege = true

    companion object {
        const val DOWNSCALE = "0.8"
        const val MIN_SDK = 31
    }

    private fun pkg(ctx: BoostContext): String = ctx.profile.packageName

    private fun supported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        if (sdk < MIN_SDK) return false
        if (pkg(ctx).isBlank()) return false
        val help = ctx.executor.shell("cmd game help 2>&1")
        return help.ok && "set" in help.stdout
    }

    override fun isSupported(ctx: BoostContext): Boolean = supported(ctx)

    override fun isApplied(ctx: BoostContext): Boolean {
        val p = pkg(ctx)
        return ctx.journal.entries.any { it.taskId == id && it.key == "game_api:$p" }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val p = pkg(ctx)
        if (p.isBlank()) return TaskResult(id, TaskStatus.Skipped, "no game package set")
        if (!supported(ctx)) {
            return TaskResult(
                id,
                TaskStatus.Skipped,
                "Game Manager API unavailable (needs Android 12+, privileged shell, ROM support)"
            )
        }
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell(
            "cmd game set --downscale $DOWNSCALE $p 2>&1"
        )
        if (!r.ok) {
            return TaskResult(
                id,
                TaskStatus.Failed("Game Manager rejected the override — the game does not declare game modes")
            )
        }
        return TaskResult(
            id,
            TaskStatus.Applied,
            "downscale $DOWNSCALE (Game Manager)",
            entries = listOf(
                JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.CMD,
                    key = "game_api:$p",
                    oldValue = "none",
                    newValue = "--downscale $DOWNSCALE",
                    revertCmd = "cmd game reset $p 2>/dev/null"
                )
            )
        )
    }
}
