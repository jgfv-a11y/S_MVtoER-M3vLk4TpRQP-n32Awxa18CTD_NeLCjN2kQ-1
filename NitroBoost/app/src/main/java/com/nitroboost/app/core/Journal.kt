package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent, replayable record of every change the booster made.
 * Stored as a JSON array so it can be inspected, exported and audited.
 *
 * Durability contract (v1.6):
 *  - writes go to a temp file, are fsync'd, then renamed over the live file;
 *  - a `.bak` of the last known-good file is kept;
 *  - a corrupt live file is copied to `.corrupt` and the journal starts empty
 *    rather than crashing the boost flow;
 *  - unknown/partial entries are skipped instead of discarding the whole file;
 *  - if the live journal grows past [ROTATE_AFTER] entries (a leak of failed
 *    restores), a snapshot is archived as `{filename}_{timestamp}` and the
 *    live list is cleared so storage cannot grow without bound.
 */
class Journal(val file: File) {

    val entries: MutableList<JournalEntry> = mutableListOf()

    init {
        load()
    }

    @Synchronized
    fun load() {
        entries.clear()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return
        }
        if (text.isBlank()) return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val kindName = o.optString("kind")
                    val kind = try {
                        JournalEntry.Kind.valueOf(kindName)
                    } catch (_: Exception) {
                        // Skip a single unknown kind — do not nuke the rest.
                        continue
                    }
                    entries.add(
                        JournalEntry(
                            taskId = o.optString("taskId"),
                            kind = kind,
                            key = o.optString("key"),
                            oldValue = if (o.isNull("oldValue")) null else o.optString("oldValue"),
                            newValue = if (o.isNull("newValue")) null else o.optString("newValue"),
                            revertCmd = if (o.isNull("revertCmd")) null else o.optString("revertCmd"),
                            ts = o.optLong("ts", 0L)
                        )
                    )
                } catch (_: Exception) {
                    // Skip one malformed object; keep the rest.
                }
            }
        } catch (e: Exception) {
            // Corrupt journal: keep it safe, do not crash.
            entries.clear()
            val backup = File(file.parentFile, file.name + ".corrupt")
            try {
                if (file.exists()) file.copyTo(backup, overwrite = true)
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun add(newEntries: List<JournalEntry>) {
        if (newEntries.isEmpty()) return
        entries.addAll(newEntries)
        save()
    }

    @Synchronized
    fun remove(newEntries: List<JournalEntry>) {
        val set = newEntries.toHashSet()
        entries.removeAll { it in set }
        save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        save()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun save() {
        try {
            persistLocked()
            // Overflow protection for leaked failed-restores. The archive
            // name MUST be `{originalFilename}_{timestamp}` — JournalTest
            // (and operators grepping the files dir) key off that prefix.
            if (entries.size > ROTATE_AFTER) {
                val archived = File(file.parentFile, file.name + "_" + System.currentTimeMillis())
                try {
                    if (file.exists()) file.copyTo(archived, overwrite = true)
                } catch (_: Exception) {
                }
                entries.clear()
                persistLocked()
            }
        } catch (e: Exception) {
            // Never let persistence break the boost flow.
        }
    }

    private fun persistLocked() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("taskId", e.taskId)
            o.put("kind", e.kind.name)
            o.put("key", e.key)
            // JSONObject.NULL (not raw null) — portable across android / org.json
            o.put("oldValue", e.oldValue ?: JSONObject.NULL)
            o.put("newValue", e.newValue ?: JSONObject.NULL)
            o.put("revertCmd", e.revertCmd ?: JSONObject.NULL)
            o.put("ts", e.ts)
            arr.put(o)
        }

        val data = arr.toString(2).toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, file.name + ".tmp")
        val backup = File(file.parentFile, file.name + ".bak")

        if (file.exists()) {
            try {
                file.copyTo(backup, overwrite = true)
            } catch (_: Exception) {
            }
        }

        FileOutputStream(tmp).use { fos ->
            fos.write(data)
            try {
                fos.fd.sync()
            } catch (_: Exception) {
            }
        }

        // Atomic replace on the same filesystem. Fall back to a copy if
        // rename is refused (some FUSE / overlay mounts).
        if (!tmp.renameTo(file)) {
            try {
                tmp.copyTo(file, overwrite = true)
            } catch (_: Exception) {
            }
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** Live-journal size that triggers an archive + clear. */
        const val ROTATE_AFTER = 250

        /**
         * Revert a single entry using the executor.
         * Returns true when the original state was successfully restored.
         */
        fun restore(entry: JournalEntry, ex: SystemExecutor): Boolean {
            return try {
                when (entry.kind) {
                    JournalEntry.Kind.SYS_SETTING ->
                        ex.sysSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.SECURE_SETTING ->
                        ex.secureSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.GLOBAL_SETTING ->
                        ex.globalSettingPut(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.SYSFS ->
                        ex.writeSys(entry.key, entry.oldValue ?: "0")
                    JournalEntry.Kind.DND ->
                        ex.dndFilterSet(entry.oldValue?.toIntOrNull() ?: DndFilters.ALL)
                    JournalEntry.Kind.THERMAL_OVERRIDE, JournalEntry.Kind.CMD ->
                        if (entry.revertCmd.isNullOrBlank()) true else ex.shell(entry.revertCmd).ok
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
