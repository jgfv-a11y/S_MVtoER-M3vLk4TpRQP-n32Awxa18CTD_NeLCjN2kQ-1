package com.nitroboost.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of boost session events (start, stop, task results,
 * de-escalations). Stored as one JSON object per line.
 */
object SessionLog {

    private const val MAX_LINES = 300
    private var file: File? = null

    private fun f(ctx: Context): File {
        file?.let { return it }
        val f = File(ctx.filesDir, "session.log.jsonl")
        file = f
        return f
    }

    fun log(ctx: Context, event: String, detail: String = "") {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val o = JSONObject()
            o.put("ts", stamp)
            o.put("event", event)
            o.put("detail", detail)
            f(ctx).appendText(o.toString() + "\n")
            trim(f(ctx))
        } catch (e: Exception) {
            // logging must never break the app
        }
    }

    fun lines(ctx: Context): List<String> {
        val f = f(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }.takeLast(200)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        try {
            f(ctx).delete()
        } catch (e: Exception) {
        }
    }

    private fun trim(f: File) {
        if (f.length() < 1_000_000) return
        try {
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", "", "\n"))
            }
        } catch (e: Exception) {
        }
    }
}
