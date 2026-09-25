package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostTask
import com.nitroboost.app.core.JournalEntry
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskResult
import com.nitroboost.app.core.TaskStatus

/**
 * Keep every CPU core online during a session (v1.5).
 *
 * Hot-plug (parking small cores, and sometimes a big one, when the load
 * looks low) adds wake-up latency exactly when a game spikes — a classic
 * source of 1–3 frame hitches. Forcing all cores online removes that
 * latency; the cores are re-parked on restore, so idle battery is not
 * affected outside sessions.
 *
 * Blocked-rom semantics: a kernel that exposes the `online` node but
 * refuses writes reports Skipped, never Failed.
 */
class CpuOnlineTask : BoostTask {

    override val id = "cpu_online"
    override val titleAr = "إبقاء كل أنوية المعالج نشطة"
    override val titleEn = "Keep all CPU cores online"
    override val descAr = "يمنع توقيت الأنوية الخاملة أثناء الجلسة — يزيل تقطيعات الاستيقاف القصيرة"
    override val descEn = "Stops core hot-plug during the session — removes wake-up hitches"
    override val module = Module.CPU
    override val requiresPrivilege = true
    override val boostLevel = 2

    private data class Core(val n: Int, val online: Boolean)

    private fun listCores(ctx: BoostContext): List<Core> {
        val r = ctx.executor.shell(
            "for c in /sys/devices/system/cpu/cpu[0-9]*/online; do " +
                "echo \"\$(basename \$(dirname \$c))=\$(cat \$c 2>/dev/null)\"; done 2>/dev/null"
        )
        if (!r.ok) return emptyList()
        return r.stdout.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val n = parts[0].removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                Core(n, parts[1].trim() == "1")
            }
            .toList()
    }

    override fun isSupported(ctx: BoostContext): Boolean =
        ctx.executor.privileged && listCores(ctx).isNotEmpty()

    override fun isApplied(ctx: BoostContext): Boolean {
        val cores = listCores(ctx)
        return cores.isNotEmpty() && cores.all { it.online }
    }

    override fun apply(ctx: BoostContext): TaskResult {
        if (!ctx.executor.privileged) {
            return TaskResult(id, TaskStatus.Skipped, "needs Shizuku or root")
        }
        val cores = listCores(ctx)
        if (cores.isEmpty()) {
            return TaskResult(id, TaskStatus.Skipped, "no cpu hotplug interface on this kernel")
        }
        val offline = cores.filter { !it.online }
        if (offline.isEmpty()) return TaskResult(id, TaskStatus.NoChange, "all cores online")
        val entries = mutableListOf<JournalEntry>()
        var attempted = 0
        var touched = 0
        for (c in offline) {
            val path = "/sys/devices/system/cpu/cpu${c.n}/online"
            attempted++
            if (ctx.executor.writeSys(path, "1") &&
                ctx.executor.readSys(path)?.trim() == "1"
            ) {
                entries += JournalEntry(
                    taskId = id,
                    kind = JournalEntry.Kind.SYSFS,
                    key = path,
                    oldValue = "0",
                    newValue = "1"
                )
                touched++
            }
        }
        return when {
            touched > 0 -> TaskResult(id, TaskStatus.Applied, "$touched core(s) online", entries)
            attempted > 0 -> TaskResult(
                id, TaskStatus.Skipped,
                "kernel refuses core-online writes on this ROM"
            )
            else -> TaskResult(id, TaskStatus.NoChange, "all cores online")
        }
    }
}
