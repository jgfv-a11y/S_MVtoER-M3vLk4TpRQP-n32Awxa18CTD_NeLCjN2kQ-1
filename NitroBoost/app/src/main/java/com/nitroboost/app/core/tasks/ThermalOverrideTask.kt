package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Compatibility shim for old profile/journal data.
 * Android's command changes controller status; it cannot configure a safe
 * temperature threshold. Calling it with 0 would disable OS throttling, so
 * this task is deliberately fail-closed. ThermalGuard is always active.
 */
class ThermalOverrideTask : BoostTask {

    override val id = "thermal_override"
    override val titleAr = "الحماية الحرارية التكيفية"
    override val titleEn = "Adaptive thermal protection"
    override val descAr = "مفعلة دائماً؛ لا يتم تعطيل خنق النظام الحراري"
    override val descEn = "Always enabled; system thermal throttling is never disabled"
    override val module = Module.THERMAL
    override val requiresPrivilege = true
    override val boostLevel = 3

    override fun isSupported(ctx: BoostContext): Boolean =
        false

    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.journal.containsTask(id)

    override fun apply(ctx: BoostContext): TaskResult {
        return TaskResult(id, TaskStatus.Skipped,
            "thermal override refused: Android exposes no safe threshold API")
    }
}
