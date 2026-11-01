package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Compatibility placeholder for profiles and journals created before 1.24.
 *
 * NitroBoost must never disable or override Android thermal protection. The
 * old task id remains in the roster so a legacy Journal can still be mapped
 * and safely restored by Journal.restore, but no new thermal override command
 * can be probed or executed.
 */
class ThermalOverrideTask : BoostTask {

    override val id = "thermal_override"
    override val titleAr = "الحماية الحرارية (غير قابلة للتجاوز)"
    override val titleEn = "Thermal protection (immutable)"
    override val descAr = "يحمي NitroBoost سياسة الحرارة ولا يسمح بتجاوز حماية النظام"
    override val descEn = "NitroBoost never disables or overrides Android thermal protection"
    override val module = Module.THERMAL
    override val requiresPrivilege = false
    override val boostLevel = 3

    override fun isSupported(ctx: BoostContext): Boolean = false

    /** A legacy entry is visible to the Journal/restore path, not a new apply. */
    override fun isApplied(ctx: BoostContext): Boolean =
        ctx.journal.containsTask(id)

    override fun apply(ctx: BoostContext): TaskResult =
        TaskResult(
            id,
            TaskStatus.Skipped,
            "rejected by immutable thermal-safety policy; use Restore to clean a legacy entry"
        )
}
