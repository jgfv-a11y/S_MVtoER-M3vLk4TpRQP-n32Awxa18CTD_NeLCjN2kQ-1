package com.nitroboost.app.core.tasks

import android.os.Build
import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Official AOSP Game Manager PERFORMANCE mode (v1.5, Android 12+).
 *
 * `cmd game set --mode 2 <pkg>` asks the platform to run the game in
 * GAME_MODE_PERFORMANCE (constant 2; note: 1 = standard/device default,
 * 3 = battery saver). It is an attribute of the game's mode config —
 * an unset `--fps` / `--downscale` argument leaves those attributes
 * untouched, so this coexists with the `--downscale` attribute the
 * adaptive engine sweeps.
 *
 * Revert is `--mode 1` (standard = device configuration). Deliberately
 * NOT `cmd game reset`, which would also wipe the downscale override.
 */
class GamePerfModeTask(
    private val sdk: Int = Build.VERSION.SDK_INT
) : BoostTask {

    override val id = "game_perf_mode"
    override val titleAr = "وضع الأداء العالي (Game API)"
    override val titleEn = "Performance game mode (Game API)"
    override val descAr = "يطلب من النظام أولوية أداء كاملة للعبة عبر Game Manager الرسمي (أندرويد 12+)"
    override val descEn = "Full-performance game priority via the official Game Manager (Android 12+)"
    override val module = Module.GPU
    override val requiresPrivilege = true
    override val boostLevel = 2

    companion object {
        const val MODE_STANDARD = "1"   // device default (AOSP shell: 1|standard)
        const val MODE_PERFORMANCE = "2" // AOSP GameManager.GAME_MODE_PERFORMANCE
        const val MIN_SDK = 31
    }

    private fun pkg(ctx: BoostContext): String = ctx.profile.packageName

    private fun supported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        if (sdk < MIN_SDK) return false
        if (pkg(ctx).isBlank()) return false
        val help = ctx.executor.shell("cmd game help 2>&1")
        return help.ok && "--mode" in help.stdout
    }

    override fun isSupported(ctx: BoostContext): Boolean = supported(ctx)

    override fun isApplied(ctx: BoostContext): Boolean {
        val p = pkg(ctx)
        return ctx.journal.entries.any { it.taskId == id && it.key == "game_mode_api:$p" }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val p = pkg(ctx)
        if (p.isBlank()) return TaskResult(id, TaskStatus.Skipped, "no game package set")
        if (!supported(ctx)) {
            return TaskResult(
                id,
                TaskStatus.Skipped,
                "Game Manager performance mode unavailable (needs Android 12+, privileged shell, ROM support)"
            )
        }
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange)
        val r = ctx.executor.shell("cmd game set --mode $MODE_PERFORMANCE $p 2>&1")
        if (!r.ok) {
            return TaskResult(id, TaskStatus.Skipped, "platform rejected the performance mode")
        }
        return TaskResult(
            id,
            TaskStatus.Applied,
            p,
            listOf(
                JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.CMD,
                    key = "game_mode_api:$p",
                    oldValue = MODE_STANDARD,
                    newValue = MODE_PERFORMANCE,
                    revertCmd = "cmd game set --mode $MODE_STANDARD $p 2>/dev/null"
                )
            )
        )
    }
}
