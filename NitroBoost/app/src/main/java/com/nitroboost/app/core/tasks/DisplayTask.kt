package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Display tuning:
 *  - min/peak refresh rate to the profile's rate (or panel max when profile is 0
 *    and the device exposes a peak value)
 *  - density downscale when the profile sets a dpi
 * Everything goes through the privileged shell; unsupported keys are skipped,
 * never guessed.
 */
class DisplayTask : BoostTask {

    override val id = "display"
    override val titleAr = "معدل التحديث والكثافة"
    override val titleEn = "Refresh rate & density"
    override val descAr = "رفع معدل التحديث إلى أقصى قيمة وتقليل الكثافة حسب الملف"
    override val descEn = "Max refresh rate and profile-driven density downscale"
    override val module = Module.DISPLAY
    override val requiresPrivilege = true

    override fun isSupported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        val ex = ctx.executor
        val minOk = ex.shell("settings get system min_refresh_rate").ok
        val peakOk = ex.shell("settings get system peak_refresh_rate").ok
        return minOk || peakOk || ex.shell("wm density").ok
    }

    override fun isApplied(ctx: BoostContext): Boolean {
        val p = ctx.profile
        if (p.refreshRate > 0 && minRate(ctx) != p.refreshRate) return false
        if (p.dpi > 0 && currentDensity(ctx) != p.dpi) return false
        return true
    }

    private fun minRate(ctx: BoostContext): Int {
        val r = ctx.executor.shell("settings get system min_refresh_rate")
        return r.stdout.trim().toIntOrNull() ?: 0
    }

    private fun currentDensity(ctx: BoostContext): Int {
        val r = ctx.executor.shell("wm density")
        return Regex("Physical density:\\s*(\\d+)").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val ex = ctx.executor
        val p = ctx.profile
        val entries = mutableListOf<JournalEntry>()
        var detail = StringBuilder()

        // --- refresh rate ---
        val target = if (p.refreshRate > 0) {
            p.refreshRate
        } else {
            val peak = ex.shell("settings get system peak_refresh_rate").stdout.trim()
            val peakInt = peak.toIntOrNull()
            if (peakInt != null && peakInt > 0) peakInt else 0
        }
        if (target > 0) {
            val curMin = minRate(ctx)
            if (curMin != target) {
                val r = ex.shell("settings put system min_refresh_rate $target")
                val now = minRate(ctx)
                if (now == target) {
                    entries.add(
                        JournalEntry(
                            taskId = id,
                            kind = JournalEntry.Kind.SYS_SETTING,
                            key = "min_refresh_rate",
                            oldValue = if (curMin == 0) "0" else curMin.toString(),
                            newValue = target.toString(),
                            revertCmd = "settings put system min_refresh_rate ${if (curMin == 0) 0 else curMin}"
                        )
                    )
                    detail.append("refresh ${curMin}->${target}Hz ")
                }
            }
        }

        // --- density ---
        if (p.dpi > 0) {
            val cur = currentDensity(ctx)
            if (cur != p.dpi) {
                val r = ex.shell("wm density ${p.dpi}")
                val now = currentDensity(ctx)
                if (now == p.dpi) {
                    entries.add(
                        JournalEntry(
                            taskId = id,
                            kind = JournalEntry.Kind.CMD,
                            key = "wm_density",
                            oldValue = if (cur == 0) null else cur.toString(),
                            newValue = p.dpi.toString(),
                            revertCmd = if (cur == 0) "wm density reset" else "wm density $cur"
                        )
                    )
                    detail.append("dpi ${if (cur == 0) "auto" else cur}->${p.dpi} ")
                }
            }
        }

        return if (entries.isEmpty()) {
            TaskResult(id, TaskStatus.NoChange)
        } else {
            TaskResult(id, TaskStatus.Applied, detail.toString().trim(), entries = entries)
        }
    }
}
