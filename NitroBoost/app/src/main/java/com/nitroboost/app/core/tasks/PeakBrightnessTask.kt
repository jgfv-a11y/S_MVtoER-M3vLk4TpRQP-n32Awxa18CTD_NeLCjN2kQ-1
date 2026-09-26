package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Peak brightness during a session (v1.5).
 *
 * Auto-brightness dims the panel the moment a bright scene drops the
 * average luminance — in a dark game scene that makes the whole screen
 * unreadable for milliseconds. Forcing manual max brightness while
 * gaming removes those dips.
 *
 * Works WITHOUT Shizuku when the user granted WRITE_SETTINGS
 * (the public Settings.System API); otherwise it goes through the
 * privileged channel. Both old values are journaled and restored
 * exactly on exit.
 */
class PeakBrightnessTask : BoostTask {

    override val id = "peak_brightness"
    override val titleAr = "السطوع الذروة أثناء اللعب"
    override val titleEn = "Peak brightness in game"
    override val descAr = "يثبّت السطوع على الأقصى ويوقف التلقائي أثناء الجلسة — لا تخفوت في المشاهد المظلمة"
    override val descEn = "Lock brightness to max and disable auto-brightness for the session — no dips in dark scenes"
    override val module = Module.DISPLAY
    override val requiresPrivilege = false
    override val boostLevel = 1 // works without privileges

    companion object {
        const val MAX_BRIGHTNESS = 255
        const val MODE_MANUAL = "0"
        const val KEY_BRIGHTNESS = "screen_brightness"
        const val KEY_MODE = "screen_brightness_mode"
    }

    override fun isSupported(ctx: BoostContext): Boolean = true

    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.journal.entries.any { it.taskId == id }

    override fun apply(ctx: BoostContext): TaskResult {
        if (isApplied(ctx)) return TaskResult(id, TaskStatus.NoChange, "already locked")
        val curBrightness = ctx.executor.sysSettingGet(KEY_BRIGHTNESS) ?: "128"
        val curMode = ctx.executor.sysSettingGet(KEY_MODE) ?: MODE_MANUAL
        val bOk = ctx.executor.sysSettingPut(KEY_BRIGHTNESS, "$MAX_BRIGHTNESS")
        val mOk = ctx.executor.sysSettingPut(KEY_MODE, MODE_MANUAL)
        if (!bOk && !mOk) {
            return TaskResult(
                id, TaskStatus.Skipped,
                "brightness not writable (grant WRITE_SETTINGS or connect Shizuku)"
            )
        }
        val entries = mutableListOf<JournalEntry>()
        if (bOk) {
            entries += JournalEntry(
                taskId = id,
                kind = JournalEntry.Kind.SYS_SETTING,
                key = KEY_BRIGHTNESS,
                oldValue = curBrightness,
                newValue = "$MAX_BRIGHTNESS"
            )
        }
        if (mOk) {
            entries += JournalEntry(
                taskId = id,
                kind = JournalEntry.Kind.SYS_SETTING,
                key = KEY_MODE,
                oldValue = curMode,
                newValue = MODE_MANUAL
            )
        }
        return TaskResult(id, TaskStatus.Applied, "locked to max", entries)
    }
}
