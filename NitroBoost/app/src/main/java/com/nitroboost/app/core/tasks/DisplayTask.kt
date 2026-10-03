package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.ProfileValidation
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Optional display tuning. A profile rate of zero preserves Android's current
 * refresh policy; an explicit rate is applied only when it does not exceed the
 * panel's reported peak. Density changes are likewise opt-in per profile.
 */
class DisplayTask : BoostTask {

    override val id = "display"
    override val titleAr = "معدل التحديث والكثافة"
    override val titleEn = "Optional refresh rate & density"
    override val descAr = "يُبقي معدل الجهاز عند 0؛ يمكن تحديد هرتز وكثافة اختياريًا في الملف"
    override val descEn = "0 preserves the device refresh policy; set an optional rate or density per profile"
    override val module = Module.DISPLAY
    override val requiresPrivilege = true

    override fun isSupported(ctx: BoostContext): Boolean {
        if (!ctx.executor.privileged) return false
        return ctx.executor.shell("wm density").ok
    }

    override fun isApplied(ctx: BoostContext): Boolean {
        val profile = ctx.profile
        if (profile.refreshRate <= 0 && profile.dpi <= 0) return false
        val rateApplied = profile.refreshRate <= 0 ||
            rateValue(settingValue(ctx, KEY_MIN_REFRESH_RATE))?.let {
                kotlin.math.abs(it - profile.refreshRate.toDouble()) < RATE_TOLERANCE
            } == true
        val densityApplied = profile.dpi <= 0 || currentDensity(ctx) == profile.dpi
        return rateApplied && densityApplied
    }

    private fun settingValue(ctx: BoostContext, key: String): String? {
        val fromApi = ctx.executor.sysSettingGet(key)?.trim()
        if (!fromApi.isNullOrEmpty() && fromApi != "null") return fromApi
        val result = ctx.executor.shell("settings get system $key")
        if (!result.ok) return null
        return result.stdout.trim().takeUnless { it.isEmpty() || it == "null" }
    }

    private fun rateValue(raw: String?): Double? =
        raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private data class DensityState(val physical: Int?, val override: Int?) {
        val effective: Int get() = override ?: physical ?: 0
    }

    private fun densityState(ctx: BoostContext): DensityState {
        val text = ctx.executor.shell("wm density").stdout
        val physical = Regex("Physical density:\\s*(\\d+)").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        val override = Regex("Override density:\\s*(\\d+)").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        return DensityState(physical, override)
    }

    private fun currentDensity(ctx: BoostContext): Int = densityState(ctx).effective

    override fun apply(ctx: BoostContext): TaskResult {
        if (!ctx.executor.privileged) {
            return TaskResult(id, TaskStatus.Skipped, "needs Shizuku or root")
        }
        val profile = ctx.profile
        if (!ProfileValidation.isValidDpi(profile.dpi) ||
            !ProfileValidation.isValidRefreshRate(profile.refreshRate)
        ) {
            return TaskResult(id, TaskStatus.Skipped, "invalid DPI or refresh-rate profile value")
        }

        val ex = ctx.executor
        val entries = mutableListOf<JournalEntry>()
        val details = mutableListOf<String>()
        var skippedReason: String? = null

        // Zero means preserve Android's current adaptive/user-selected policy.
        val targetRate = profile.refreshRate
        if (targetRate > 0) {
            val peakRaw = settingValue(ctx, KEY_PEAK_REFRESH_RATE)
            val peak = rateValue(peakRaw)
            if (peak != null && targetRate > peak + RATE_TOLERANCE) {
                skippedReason = "requested ${targetRate}Hz exceeds panel peak ${peakRaw}Hz"
            } else {
                val oldRaw = settingValue(ctx, KEY_MIN_REFRESH_RATE)
                val current = rateValue(oldRaw) ?: 0.0
                if (kotlin.math.abs(current - targetRate.toDouble()) >= RATE_TOLERANCE) {
                    val written = ex.sysSettingPut(KEY_MIN_REFRESH_RATE, targetRate.toString())
                    val now = rateValue(settingValue(ctx, KEY_MIN_REFRESH_RATE))
                    if (written && now != null &&
                        kotlin.math.abs(now - targetRate.toDouble()) < RATE_TOLERANCE
                    ) {
                        entries += JournalEntry(
                            taskId = id,
                            kind = JournalEntry.Kind.SYS_SETTING,
                            key = KEY_MIN_REFRESH_RATE,
                            oldValue = oldRaw,
                            newValue = targetRate.toString()
                        )
                        details += "refresh ${oldRaw ?: "auto"}->$targetRate Hz"
                    }
                }
            }
        }

        // --- optional density ---
        val targetDpi = profile.dpi
        if (targetDpi > 0) {
            val oldState = densityState(ctx)
            val oldDpi = oldState.effective
            if (oldDpi != targetDpi) {
                val result = ex.shell("wm density $targetDpi")
                val current = currentDensity(ctx)
                if (result.ok && current == targetDpi) {
                    entries += JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.CMD,
                        key = KEY_WM_DENSITY,
                        oldValue = oldState.override?.toString(),
                        newValue = targetDpi.toString(),
                        revertCmd = if (oldState.override == null) "wm density reset"
                        else "wm density ${oldState.override}"
                    )
                    details += "dpi ${if (oldState.override == null) "auto" else oldDpi}->$targetDpi"
                }
            }
        }

        return when {
            entries.isNotEmpty() -> TaskResult(id, TaskStatus.Applied, details.joinToString("; "), entries)
            skippedReason != null -> TaskResult(id, TaskStatus.Skipped, skippedReason)
            else -> TaskResult(id, TaskStatus.NoChange, "system display policy preserved")
        }
    }

    companion object {
        const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"
        const val KEY_PEAK_REFRESH_RATE = "peak_refresh_rate"
        const val KEY_WM_DENSITY = "wm_density"
        private const val RATE_TOLERANCE = 0.01
    }
}
