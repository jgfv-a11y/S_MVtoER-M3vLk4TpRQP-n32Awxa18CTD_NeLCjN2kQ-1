package com.nitroboost.app.core.adaptive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The engine's memory: every trial's delta pairs and verdict, persisted
 * to `filesDir/adaptive_ledger.json` so decisions SURVIVE app restarts and
 * accumulate across sessions (a tweak that is neutral for one session may
 * become significant over the next three).
 *
 * The same "keep failed entries, never lose state" discipline as the
 * main modification journal.
 */
class DecisionLedger(private val file: File, private val maxPairs: Int = 40) {

    val entries: MutableMap<String, LedgerEntry> = linkedMapOf()

    fun load() {
        entries.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val deltas = mutableListOf<Double>()
                val d = o.optJSONArray("deltas")
                if (d != null) for (j in 0 until d.length()) deltas.add(d.getDouble(j))
                val entry = LedgerEntry(
                    taskId = o.getString("taskId"),
                    taskTitle = o.optString("taskTitle", o.getString("taskId")),
                    decision = Decision.valueOf(o.getString("decision")),
                    deltas = deltas,
                    meanDelta = if (o.isNull("meanDelta")) null else o.optDouble("meanDelta"),
                    ciLow = if (o.isNull("ciLow")) null else o.optDouble("ciLow"),
                    ciHigh = if (o.isNull("ciHigh")) null else o.optDouble("ciHigh"),
                    pairs = o.optInt("pairs"),
                    sessions = o.optInt("sessions", 1),
                    evaluatedAt = o.optLong("evaluatedAt")
                )
                entries[entry.taskId] = entry
            }
        } catch (e: Exception) {
            // corrupt ledger: start fresh rather than crash — trials are safe
            entries.clear()
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (e in entries.values) {
                val o = JSONObject()
                    .put("taskId", e.taskId)
                    .put("taskTitle", e.taskTitle)
                    .put("decision", e.decision.name)
                    .put("deltas", JSONArray(e.deltas))
                    .put("meanDelta", e.meanDelta ?: JSONObject.NULL)
                    .put("ciLow", e.ciLow ?: JSONObject.NULL)
                    .put("ciHigh", e.ciHigh ?: JSONObject.NULL)
                    .put("pairs", e.pairs)
                    .put("sessions", e.sessions)
                    .put("evaluatedAt", e.evaluatedAt)
                arr.put(o)
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // best effort — the in-memory map stays authoritative this session
        }
    }

    /** Merge new trial deltas and re-assess. Returns the new record. */
    fun record(taskId: String, taskTitle: String, newDeltas: List<Double>,
               outcome: TrialOutcome, nowMs: Long, cfg: TrialConfig): LedgerEntry {
        val prev = entries[taskId]
        val base = prev?.deltas ?: emptyList()
        val deltas = (base + newDeltas).takeLast(maxPairs)
        val final = if (newDeltas.isEmpty()) outcome else AdaptivePolicy.assess(deltas, cfg)
        val merged = LedgerEntry(
            taskId = taskId,
            taskTitle = taskTitle.ifEmpty { taskId },
            decision = final.decision,
            deltas = deltas,
            meanDelta = final.meanDelta,
            ciLow = final.ciLow,
            ciHigh = final.ciHigh,
            pairs = deltas.size,
            sessions = (prev?.sessions ?: 0) + if (newDeltas.isNotEmpty()) 1 else 0,
            evaluatedAt = nowMs
        )
        entries[taskId] = merged
        return merged
    }

    fun decisionFor(taskId: String): Decision? = entries[taskId]?.decision

    fun isResolved(taskId: String): Boolean = entries[taskId]?.resolved == true

    /** Unresolved candidates the loop should keep measuring. */
    fun pendingIds(): List<String> =
        entries.values.filter { !it.resolved }.map { it.taskId }
}
