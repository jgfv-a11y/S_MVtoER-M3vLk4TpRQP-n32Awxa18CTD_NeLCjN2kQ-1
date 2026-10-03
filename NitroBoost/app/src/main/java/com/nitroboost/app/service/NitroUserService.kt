package com.nitroboost.app.service

import android.content.Context
import android.os.RemoteException
import android.util.Base64
import androidx.annotation.Keep
import com.nitroboost.app.shizuku.INitroService
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Shizuku **user service**: runs in its own process with the Shizuku
 * identity (shell uid 2000, or root when Shizuku runs on root).
 *
 * The app process talks to it through the generated AIDL stub. Every call
 * is wrapped so a bad command can never crash the service process.
 */
class NitroUserService constructor() : INitroService.Stub() {

    @Keep
    constructor(context: Context) : this()

    /** Reserved: called by the Shizuku server when the service is destroyed. */
    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun runShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val outFuture = readAsync(process.inputStream)
            val errFuture = readAsync(process.errorStream)
            val finished = try {
                process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) {
                terminate(process)
                outFuture.cancel(true)
                errFuture.cancel(true)
                return encodeResult(-1, "", "command_timeout")
            }
            encodeResult(
                process.exitValue(),
                collectOutput(outFuture),
                collectOutput(errFuture)
            )
        } catch (e: Exception) {
            val errText = e.message ?: "error"
            encodeResult(-1, "", errText)
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

    private fun terminate(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
        try {
            process.inputStream.close()
        } catch (_: Exception) {
        }
        try {
            process.errorStream.close()
        } catch (_: Exception) {
        }
    }

    private fun encodeResult(code: Int, stdout: String, stderr: String): String = buildString {
        append(code)
        append('\u0000')
        append(Base64.encodeToString(stdout.toByteArray(), Base64.NO_WRAP))
        append('\u0000')
        append(Base64.encodeToString(stderr.toByteArray(), Base64.NO_WRAP))
    }

    override fun readSys(path: String): String {
        return try {
            val f = File(path)
            if (f.canRead()) f.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private companion object {
        private const val SHELL_TIMEOUT_MS = 10_000L
        private val outputReaders = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "nitro-shizuku-output").apply { isDaemon = true }
        }
    }
}
