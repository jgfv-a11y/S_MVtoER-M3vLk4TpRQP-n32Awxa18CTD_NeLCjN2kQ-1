package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Ask the system to trim app caches (pm trim-caches with max bytes).
 * The system decides which caches to evict — safe and idempotent
 * (stateless: re-applying is always a no-op).
 */
class RamTrimTask : BoostTask {

    override val boostLevel = 3 // aggressive

    override val id = "ram_trim"
    override val titleAr = "تنظيف ذاكرة التخزين المؤقت"
    override val titleEn = "Trim app caches"
    override val descAr = "تخليص النظام من كاش التطبيقات لتحرير ذاكرة"
    override val descEn = "Let the system evict app caches to free memory"
    override val module = Module.RAM
    override val requiresPrivilege = true

    override fun isSupported(ctx: BoostContext): Boolean = ctx.executor.privileged

    override fun isApplied(ctx: BoostContext): Boolean = false

    override fun apply(ctx: BoostContext): TaskResult {
        val r = ctx.executor.shell("pm trim-caches 9223372036854775807")
        return if (r.ok) {
            TaskResult(id, TaskStatus.Applied, "cache trim requested")
        } else {
            TaskResult(id, TaskStatus.Failed("pm trim-caches rejected"))
        }
    }
}

/**
 * Pure selection logic for background-app killing — unit-testable.
 *
 * Rules (conflict-free by construction):
 *  - never kill the game itself, the booster itself, system packages
 *    (uid < 10000) or anything in the protection list;
 *  - only apps idle for longer than [idleMs] are candidates;
 *  - the caller passes already-filtered background packages.
 */
object BackgroundSelector {

    fun select(
        backgroundPackages: Map<String, Long>, // pkg -> lastUsedTimeMs
        foregroundPackage: String?,
        selfPackage: String?,
        protectedPackages: Set<String>,
        nowMs: Long,
        idleMs: Long = 90_000L
    ): List<String> {
        val forbidden = protectedPackages.toMutableSet()
        if (foregroundPackage != null) forbidden.add(foregroundPackage)
        if (selfPackage != null) forbidden.add(selfPackage)
        return backgroundPackages.entries
            .map { it.key to it.value }
            .filter { (pkg, last) ->
                pkg !in forbidden && (nowMs - last) >= idleMs
            }
            .map { it.first }
            .sorted()
    }
}

/**
 * Force-stop background apps via the privileged shell (am force-stop).
 * Enabled only when the profile has aggressiveRamClean.
 *
 * The killable list is supplied by the platform layer (UsageStats based)
 * through [killableProvider]; the default yields nothing, so the task is a
 * no-op until the Android side wires it up.
 */
class RamKillTask : BoostTask {

    override val boostLevel = 3 // aggressive

    override val id = "ram_kill"
    override val titleAr = "إنهاء تطبيقات الخلفية"
    override val titleEn = "Kill background apps"
    override val descAr = "إنهاء التطبيقات الخاملة في الخلفية (مع قائمة حماية)"
    override val descEn = "Force-stop idle background apps (with protection list)"
    override val module = Module.RAM
    override val requiresPrivilege = true

    var killableProvider: () -> List<String> = { emptyList() }

    override fun isSupported(ctx: BoostContext): Boolean = ctx.executor.privileged

    override fun isApplied(ctx: BoostContext): Boolean = false

    override fun apply(ctx: BoostContext): TaskResult {
        val killable = killableProvider()
        if (killable.isEmpty()) return TaskResult(id, TaskStatus.NoChange, "nothing to kill")
        var ok = 0
        for (pkg in killable) {
            if (pkg.isBlank()) continue
            val r = ctx.executor.shell("am force-stop \"$pkg\"")
            if (r.ok) ok++
        }
        return if (ok > 0) {
            TaskResult(id, TaskStatus.Applied, "$ok app(s) stopped")
        } else {
            TaskResult(id, TaskStatus.Failed("force-stop rejected by system"))
        }
    }
}
