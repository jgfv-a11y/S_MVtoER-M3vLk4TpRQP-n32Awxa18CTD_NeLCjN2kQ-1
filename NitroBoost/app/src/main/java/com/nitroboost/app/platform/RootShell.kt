package com.nitroboost.app.platform

import com.nitroboost.app.core.ShellResult
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Minimal Magisk/Root fallback (v1.4.0).
 *
 * Shizuku is the preferred privileged channel; on rooted devices that
 * cannot (or will not) run Shizuku, a plain `su -c` gives the same
 * shell-uid powers. Detection is conservative:
 *  - `su -c id` must answer within [PROBE_TIMEOUT_MS] and report uid=0;
 *  - the probe is cached, but re-checked when the previous probe failed
 *    (the user may install/enable root later) and when a command fails.
 *
 * Every call is exception-proof: no root prompt loop, no crash.
 */
object RootShell {

    private const val PROBE_TIMEOUT_MS = 4_000L
    private const val COMMAND_TIMEOUT_MS = 10_000L

    // Drain stdout/stderr concurrently so a full pipe cannot block the command
    // thread before its process timeout is checked.
    private val outputReaders = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nitro-root-output").apply { isDaemon = true }
    }

    @Volatile
    private var available: Boolean? = null
    @Volatile
    private var lastProbeAt = 0L
    private const val PROBE_TTL_MS = 30_000L

    /** Pure: the probe output proves root iff it contains "uid=0". */
    fun parseProbeOutput(output: String): Boolean = output.contains("uid=0")

    /**
     * Probe `su -c id`. Cached for 30s on failure so the UI can poll
     * without hammering the device.
     */
    fun isAvailable(): Boolean {
        val cached = available
        val now = System.currentTimeMillis()
        if (cached == true) return true
        if (cached == false && now - lastProbeAt < PROBE_TTL_MS) return false
        return probe().also {
            lastProbeAt = System.currentTimeMillis()
        }
    }

    private fun probe(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroy()
                available = false
                return false
            }
            val out = try {
                p.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                ""
            }
            val rc = try {
                p.waitFor()
            } catch (e: Exception) {
                -1
            }
            available = rc == 0 && parseProbeOutput(out)
            available ?: false
        } catch (e: Exception) {
            available = false
            false
        }
    }

    /** Force the next call to re-probe (call after a failed command). */
    fun invalidate() {
        available = null
    }

    /**
     * Run one shell command via `su -c`. Escapes single quotes so the
     * command survives the extra shell layer.
     */
    fun run(cmd: String): ShellResult {
        if (!isAvailable()) return ShellResult.fail("root_unavailable")
        val escaped = cmd.replace("'", "'\\''")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", escaped))
            val outFuture = readAsync(p.inputStream)
            val errFuture = readAsync(p.errorStream)
            val done = try {
                p.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!done) {
                p.destroy()
                try {
                    if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    p.destroyForcibly()
                }
                closeQuietly(p.inputStream)
                closeQuietly(p.errorStream)
                outFuture.cancel(true)
                errFuture.cancel(true)
                invalidate()
                return ShellResult.fail("root_timeout")
            }
            val code = try {
                p.exitValue()
            } catch (e: Exception) {
                -1
            }
            val out = collectOutput(outFuture)
            val err = collectOutput(errFuture)
            val ok = code == 0
            if (!ok) invalidate()
            ShellResult(ok, code, out, err)
        } catch (e: Exception) {
            ShellResult.fail("root_error: ${e.message}")
        }
    }

    private fun readAsync(stream: InputStream): Future<String> = outputReaders.submit<String> {
        stream.bufferedReader().use { it.readText() }
    }

    private fun collectOutput(future: Future<String>): String = try {
        future.get(1_000, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        future.cancel(true)
        ""
    } catch (_: Exception) {
        future.cancel(true)
        ""
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }

    fun readSys(path: String): String? {
        val r = run("cat \"$path\" 2>/dev/null")
        if (!r.ok || r.stdout.isBlank()) return null
        return r.stdout.trim()
    }

    fun writeSys(path: String, value: String): Boolean {
        val r = run("echo \"$value\" > \"$path\" 2>/dev/null")
        if (!r.ok) return false
        return readSys(path) == value
    }
}
