package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Raise the per-policy CPU frequency floor (65% of the policy max) so the
 * cluster never has to ramp up from the lowest P-state — this removes the
 * first-frames stutter after a load spike.
 *
 * Deliberately conservative (65%, not 100%): the ThermalGuard reverts this
 * task's entries first when the device heats up, so the floor can never keep
 * the SoC at full speed in the hot state.
 */
class CpuFloorTask : BoostTask {

    override val id = "cpu_floor"
    override val titleAr = "رفع حد أدنى لتردد المعالج"
    override val titleEn = "CPU frequency floor"
    override val descAr = "منع هبوط التردد إلى أدنى مستوى — يلغي تقاطع الإطارات بعد قفزة الحمل"
    override val descEn = "Stop the SoC idling at the lowest P-state — kills post-spike frame stutter"
    override val module = Module.CPU
    override val requiresPrivilege = true

    private data class Policy(val dir: String, val maxFreq: Long, val minFreq: Long)

    private fun policies(ctx: BoostContext): List<Policy> {
        val r = ctx.executor.shell(
            "for d in /sys/devices/system/cpu/cpufreq/policy*; do " +
                "[ -d \"\$d\" ] && echo \"\$d \$(cat \$d/cpuinfo_max_freq 2>/dev/null) \$(cat \$d/scaling_min_freq 2>/dev/null)\"; done"
        )
        if (!r.ok) return emptyList()
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val p = line.split(Regex("\\s+"))
                if (p.size < 3) return@mapNotNull null
                val maxF = p[1].toLongOrNull() ?: return@mapNotNull null
                val minF = p[2].toLongOrNull() ?: return@mapNotNull null
                if (maxF <= 0) return@mapNotNull null
                Policy(p[0], maxF, minF)
            }
            .toList()
    }

    private fun targetFloor(p: Policy): Long = (p.maxFreq * 65L / 100L).coerceAtMost(p.maxFreq)

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && policies(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val ps = policies(ctx)
        return ps.isNotEmpty() && ps.all { it.minFreq >= targetFloor(it) }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val ps = policies(ctx)
        if (ps.isEmpty()) {
            return TaskResult(id, TaskStatus.Skipped, "no cpufreq policies visible to shell")
        }
        val entries = mutableListOf<JournalEntry>()
        for (p in ps) {
            val target = targetFloor(p)
            if (p.minFreq >= target) continue
            val w = ctx.executor.shell("echo $target > ${p.dir}/scaling_min_freq 2>/dev/null")
            val now = ctx.executor.readSys("${p.dir}/scaling_min_freq")?.toLongOrNull()
            if (now != null && now >= target) {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = "${p.dir}/scaling_min_freq",
                        oldValue = p.minFreq.toString(),
                        newValue = target.toString()
                    )
                )
            } else if (!w.ok) {
                return TaskResult(
                    id,
                    TaskStatus.Failed,
                    "ROM blocks scaling_min_freq writes on ${p.dir}"
                )
            }
        }
        return if (entries.isEmpty()) {
            TaskResult(id, TaskStatus.NoChange, "floor already set")
        } else {
            TaskResult(id, TaskStatus.Applied, "${entries.size} policy(ies)", entries = entries)
        }
    }
}
