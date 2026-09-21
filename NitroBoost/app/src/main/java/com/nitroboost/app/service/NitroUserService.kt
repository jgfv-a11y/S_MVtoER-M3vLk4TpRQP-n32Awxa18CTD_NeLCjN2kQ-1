package com.nitroboost.app.service

import android.content.Context
import android.os.RemoteException
import android.util.Base64
import androidx.annotation.Keep
import com.nitroboost.app.shizuku.INitroService
import java.io.File

/**
 * Shizuku **user service**: runs in its own process with the Shizuku
 * identity (shell uid 2000, or root when Shizuku runs on root).
 *
 * The app process talks to it through the generated AIDL stub. Every call
 * is wrapped so a bad command can never crash the service process.
 */
class NitroUserService constructor() : INitroService.Stub() {

    @Keep
    constructor(context: Context) : super()

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
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            buildString {
                append(code)
                append('\u0000')
                append(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                append('\u0000')
                append(Base64.encodeToString(err.toByteArray(), Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            val errText = e.message ?: "error"
            "-1\u0000\u0000" + Base64.encodeToString(errText.toByteArray(), Base64.NO_WRAP)
        }
    }

    override fun readSys(path: String): String {
        return try {
            val f = File(path)
            if (f.canRead()) f.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }
}
