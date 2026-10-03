package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistent, replayable record of every change the booster made.
 * Stored as a JSON array so it can be inspected, exported and audited.
 *
 * Durability contract (v1.6):
 *  - writes go to a temp file, are fsync'd, then renamed over the live file;
 *  - a `.bak` of the last known-good file is kept;
 *  - a corrupt live file is copied to `.corrupt` and the last known-good
 *    `.bak` is recovered when available;
 *  - unknown/partial entries are skipped instead of discarding the whole file;
 *  - if the live journal grows past [ROTATE_AFTER] entries (a leak of failed
 *    restores), a snapshot is archived as `{filename}_{timestamp}` and the
 *    live list is cleared so storage cannot grow without bound.
 */
class Journal(val file: File) {

    private val mutableEntries: MutableList<JournalEntry> = mutableListOf()

    /** Mutable copy retained for source compatibility; mutating it cannot bypass durable Journal APIs. */
    val entries: MutableList<JournalEntry>
        get() = snapshot().toMutableList()

    @Synchronized
    fun snapshot(): List<JournalEntry> = mutableEntries.toList()

    init {
        load()
    }

    @Synchronized
    fun load() {
        mutableEntries.clear()
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return
        }
        try {
            mutableEntries.addAll(parseEntries(text))
            return
        } catch (_: Exception) {
            // Preserve the corrupt live journal, then recover the last known-good snapshot.
            val corrupt = File(file.parentFile, file.name + ".corrupt")
            try {
                Files.copy(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
            }
        }
        val backup = File(file.parentFile, file.name + ".bak")
        try {
            if (backup.exists()) mutableEntries.addAll(parseEntries(backup.readText()))
        } catch (_: Exception) {
            mutableEntries.clear()
        }
    }

    private fun parseEntries(text: String): List<JournalEntry> {
        if (text.isBlank()) return emptyList()
        val loaded = mutableListOf<JournalEntry>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val kind = try {
                    JournalEntry.Kind.valueOf(o.optString("kind"))
                } catch (_: Exception) {
                    // Skip one unknown kind — do not discard the rest.
                    continue
                }
                loaded += JournalEntry(
                    taskId = o.optString("taskId"),
                    kind = kind,
                    key = o.optString("key"),
                    oldValue = if (o.isNull("oldValue")) null else o.optString("oldValue"),
                    newValue = if (o.isNull("newValue")) null else o.optString("newValue"),
                    revertCmd = if (o.isNull("revertCmd")) null else o.optString("revertCmd"),
                    ts = o.optLong("ts", 0L)
                )
            } catch (_: Exception) {
                // Skip one malformed object; keep the rest.
            }
        }
        return loaded
    }

    @Synchronized
    fun add(newEntries: List<JournalEntry>) {
        if (newEntries.isEmpty()) return
        for (entry in newEntries) {
            val alreadyRecorded = mutableEntries.any {
                it.taskId == entry.taskId && it.kind == entry.kind && it.key == entry.key
            }
            // Preserve the first old value/revert command: it is the true
            // pre-session state and duplicate entries must not restore twice.
            if (!alreadyRecorded) mutableEntries.add(entry)
        }
        save()
    }

    @Synchronized
    fun remove(newEntries: List<JournalEntry>) {
        val set = newEntries.toHashSet()
        mutableEntries.removeAll { it in set }
        save()
    }

    @Synchronized
    fun clear() {
        mutableEntries.clear()
        save()
    }

    @Synchronized
    fun isEmpty(): Boolean = mutableEntries.isEmpty()

    @Synchronized
    fun containsTask(taskId: String): Boolean = mutableEntries.any { it.taskId == taskId }

    @Synchronized
    fun containsTaskKey(taskId: String, key: String): Boolean =
        mutableEntries.any { it.taskId == taskId && it.key == key }

    @Synchronized
    fun save() {
        try {
            persistLocked()
            // Overflow protection for leaked failed-restores. The archive
            // name MUST be `{originalFilename}_{timestamp}` — JournalTest
            // (and operators grepping the files dir) key off that prefix.
            if (mutableEntries.size > ROTATE_AFTER) {
                val archived = File(file.parentFile, file.name + "_" + System.currentTimeMillis())
                try {
                    if (file.exists()) file.copyTo(archived, overwrite = true)
                } catch (_: Exception) {
                }
                mutableEntries.clear()
                persistLocked()
            }
        } catch (e: Exception) {
            // Never let persistence break the boost flow.
        }
    }

    private fun persistLocked() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        for (e in mutableEntries) {
            val o = JSONObject()
            o.put("taskId", e.taskId)
            o.put("kind", e.kind.name)
            o.put("key", e.key)
            o.put("oldValue", e.oldValue ?: JSONObject.NULL)
            o.put("newValue", e.newValue ?: JSONObject.NULL)
            o.put("revertCmd", e.revertCmd ?: JSONObject.NULL)
            o.put("ts", e.ts)
            arr.put(o)
        }

        val data = arr.toString(2).toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, file.name + ".tmp")
        val backup = File(file.parentFile, file.name + ".bak")

        if (file.exists() && runCatching { parseEntries(file.readText()) }.isSuccess) {
            val backupTmp = File(file.parentFile, file.name + ".bak.tmp")
            try {
                writeAndSync(backupTmp, file.readBytes())
                atomicReplace(backupTmp, backup)
            } catch (_: Exception) {
                backupTmp.delete()
                // The existing live journal remains intact until the new temp is synced.
            }
        }
        writeAndSync(tmp, data)
        atomicReplace(tmp, file)
    }

    private fun writeAndSync(target: File, data: ByteArray) {
        FileOutputStream(target).use { out ->
            out.write(data)
            out.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Never fall back to truncating-copy over the live journal.
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Live-journal size that triggers an archive + clear. */
        const val ROTATE_AFTER = 250

        private fun safeRevertCommand(entry: JournalEntry): String? {
            val command = entry.revertCmd ?: return null
            return when (entry.kind) {
                JournalEntry.Kind.THERMAL_OVERRIDE ->
                    if (entry.taskId == "thermal_override" &&
                        entry.key == "thermal_override" &&
                        entry.oldValue == "-1" && entry.newValue == "0" &&
                        command == "cmd thermalservice override-status -1"
                    ) command else null

                JournalEntry.Kind.CMD -> when (entry.taskId) {
                    "display" -> {
                        if (entry.key != "wm_density") return null
                        val targetDpi = entry.newValue?.toIntOrNull()
                        if (targetDpi == null || targetDpi !in 72..1000) return null
                        val oldDpi = entry.oldValue?.toIntOrNull()
                        when {
                            oldDpi == null && entry.oldValue == null && command == "wm density reset" -> command
                            oldDpi != null && oldDpi in 72..1000 && command == "wm density $oldDpi" -> command
                            else -> null
                        }
                    }
                    "game_api_downscale" -> {
                        val pkg = entry.key.removePrefix("game_api:")
                        val downscale = entry.newValue?.removePrefix("--downscale ")
                        if (entry.key.startsWith("game_api:") && ShellInput.isPackageName(pkg) &&
                            downscale != null && downscale in setOf("0.9", "0.8", "0.7") &&
                            entry.oldValue == "none" &&
                            command == "cmd game reset $pkg 2>/dev/null"
                        ) command else null
                    }
                    "game_perf_mode" -> {
                        val pkg = entry.key.removePrefix("game_mode_api:")
                        if (entry.key.startsWith("game_mode_api:") && ShellInput.isPackageName(pkg) &&
                            entry.oldValue == "1" && entry.newValue == "2" &&
                            command == "cmd game set --mode 1 $pkg 2>/dev/null"
                        ) command else null
                    }
                    "device_idle" -> {
                        val pkg = entry.key.removePrefix("device_idle:")
                        if (entry.key.startsWith("device_idle:") && ShellInput.isPackageName(pkg) &&
                            entry.newValue == "whitelisted" &&
                            command == "cmd deviceidle whitelist-remove $pkg 2>/dev/null"
                        ) command else null
                    }
                    "doze_whitelist" -> {
                        val pkg = entry.key.removePrefix("doze_whitelist:")
                        if (entry.key.startsWith("doze_whitelist:") && ShellInput.isPackageName(pkg) &&
                            entry.oldValue == "absent" && entry.newValue == "present" &&
                            command == "cmd deviceidle whitelist -$pkg 2>/dev/null"
                        ) command else null
                    }
                    else -> null
                }

                else -> null
            }
        }

        /**
         * Revert a single entry using the executor.
         * Returns true when the original state was successfully restored.
         */
        fun restore(entry: JournalEntry, ex: SystemExecutor): Boolean {
            return try {
                when (entry.kind) {
                    JournalEntry.Kind.SYS_SETTING ->
                        if (entry.taskId == "display" && entry.key == "min_refresh_rate" && entry.oldValue == null)
                            ex.sysSettingDelete(entry.key)
                        else ex.sysSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.SECURE_SETTING ->
                        ex.secureSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.GLOBAL_SETTING ->
                        ex.globalSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.SYSFS ->
                        ex.writeSys(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.DND ->
                        ex.dndFilterSet(entry.oldValue?.toIntOrNull() ?: DndFilters.ALL)
                    JournalEntry.Kind.THERMAL_OVERRIDE, JournalEntry.Kind.CMD -> {
                        val command = safeRevertCommand(entry) ?: return false
                        ex.shell(command).ok
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
