package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Push every CPU core governor to "performance".
 * Requires a privileged shell (Shizuku). On ROMs where the cpufreq sysfs is
 * not exposed to shell the task reports Failed with a clear reason — it never
 * pretends to work.
 */
class GovernorTask : BoostTask {

    override val id = "cpu_governor"
    override val titleAr = "وضع الأداء للمعالج"
    override val titleEn = "CPU performance governor"
    override val descAr = "تحويل جميع أنوية المعالج إلى وضع الأداء الأقصى"
    override val descEn = "Switch all CPU cores to the performance governor"
    override val module = Module.CPU
    override val requiresPrivilege = true

    private data class GovFile(val path: String, val current: String)

    private fun listGovernors(ctx: BoostContext): List<GovFile> {
        val r = ctx.executor.shell(
            "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do " +
                "[ -f \"$f\" ] && echo \"\$f \$(cat \"$f\" 2>/dev/null)\"; done"
        )
        if (!r.ok) return emptyList()
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) null else GovFile(parts[0], parts[1].trim())
            }
            .filter { it.path.endsWith("scaling_governor") }
            .toList()
    }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && listGovernors(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val files = listGovernors(ctx)
        return files.isNotEmpty() && files.all { it.current == "performance" }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        val files = listGovernors(ctx)
        if (files.isEmpty()) {
            return TaskResult(
                id,
                TaskStatus.Skipped,
                "no cpufreq sysfs exposed to shell on this ROM"
            )
        }
        val entries = mutableListOf<JournalEntry>()
        var touched = 0
        for (f in files) {
            if (f.current == "performance") continue
            val w = ctx.executor.shell("echo performance > \"${f.path}\" 2>/dev/null")
            val now = ctx.executor.readSys(f.path)
            if (now == "performance") {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = f.path,
                        oldValue = f.current,
                        newValue = "performance",
                        revertCmd = "echo ${f.current} > \"${f.path}\" 2>/dev/null"
                    )
                )
                touched++
            } else if (!w.ok) {
                return TaskResult(
                    id,
                    TaskStatus.Failed,
                    "ROM blocks governor writes (shell has no cpufreq access)"
                )
            }
        }
        return if (touched == 0) {
            TaskResult(id, TaskStatus.NoChange, "all cores already at performance")
        } else {
            TaskResult(id, TaskStatus.Applied, "${touched} cores", entries = entries)
        }
    }
}
