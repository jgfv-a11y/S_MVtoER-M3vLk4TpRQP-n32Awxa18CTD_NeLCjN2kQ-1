package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Push every CPU core to the requested governor (default "performance").
 * Requires a privileged shell (Shizuku). On ROMs where the cpufreq sysfs is
 * not writable by shell the task reports Skipped ("not writable on this
 * ROM") — it never pretends to work, and a blocked ROM is not an error.
 *
 * [governor] is injectable: the adaptive engine A/B tests "performance"
 * against "schedutil" on the real device and keeps the winner.
 */
class GovernorTask(private val governor: String = "performance") : BoostTask {

    override val id = "cpu_governor"
    override val titleAr = "وضع الأداء للمعالج"
    override val titleEn = "CPU performance governor"
    override val descAr: String
        get() = "تحويل جميع أنوية المعالج إلى وضع $governor"
    override val descEn: String
        get() = "Switch all CPU cores to the $governor governor"
    override val module = Module.CPU
    override val requiresPrivilege = true

    private data class GovFile(val path: String, val current: String)

    private fun listGovernors(ctx: BoostContext): List<GovFile> {
        val r = ctx.executor.shell(
            "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do " +
                "[ -f \"\$f\" ] && echo \"\$f $(cat \"\$f\" 2>/dev/null)\"; done"
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
        return files.isNotEmpty() && files.all { it.current == governor }
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
        var attempted = 0
        for (f in files) {
            if (f.current == governor) continue
            attempted++
            val w = ctx.executor.shell("echo $governor > \"${f.path}\" 2>/dev/null")
            val now = ctx.executor.readSys(f.path)
            if (now == governor) {
                entries.add(
                    JournalEntry(
                        taskId = id,
                        kind = JournalEntry.Kind.SYSFS,
                        key = f.path,
                        oldValue = f.current,
                        newValue = governor,
                        revertCmd = "echo ${f.current} > \"${f.path}\" 2>/dev/null"
                    )
                )
                touched++
            }
        }
        return when {
            touched > 0 -> TaskResult(id, TaskStatus.Applied, "$touched cores", entries = entries)
            attempted > 0 -> TaskResult(
                id,
                TaskStatus.Skipped,
                "ROM blocks governor writes on this device"
            )
            else -> TaskResult(id, TaskStatus.NoChange, "all cores already at $governor")
        }
    }
}
