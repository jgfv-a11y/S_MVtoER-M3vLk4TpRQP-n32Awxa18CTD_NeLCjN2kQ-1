package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent, replayable record of every change the booster made.
 * Stored as a JSON array so it can be inspected, exported and audited.
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
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    JournalEntry(
                        taskId = o.optString("taskId"),
                        kind = JournalEntry.Kind.valueOf(o.optString("kind")),
                        key = o.optString("key"),
                        oldValue = if (o.isNull("oldValue")) null else o.optString("oldValue"),
                        newValue = if (o.isNull("newValue")) null else o.optString("newValue"),
                        revertCmd = if (o.isNull("revertCmd")) null else o.optString("revertCmd"),
                        ts = o.optLong("ts", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt journal: keep it safe, do not crash.
            entries.clear()
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
            // Atomic write: a process kill mid-write must never leave a
            // truncated journal (which would silently drop pending reverts).
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString(2))
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            tmp.delete()
        } catch (e: Exception) {
            // Never let persistence break the boost flow.
        }
    }

    companion object {
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
