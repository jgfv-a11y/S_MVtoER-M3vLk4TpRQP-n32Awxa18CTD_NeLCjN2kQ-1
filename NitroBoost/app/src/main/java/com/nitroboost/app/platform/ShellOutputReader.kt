package com.nitroboost.app.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Bounded shell output capture that still drains the pipe to avoid deadlocks. */
internal object ShellOutputReader {
    const val MAX_STDOUT_BYTES = 512 * 1024
    const val MAX_STDERR_BYTES = 128 * 1024

    fun readLimited(input: InputStream, maxBytes: Int): String {
        val limit = maxBytes.coerceAtLeast(0)
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var stored = 0
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val keep = minOf(count, limit - stored)
                if (keep > 0) {
                    output.write(buffer, 0, keep)
                    stored += keep
                }
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }
}
