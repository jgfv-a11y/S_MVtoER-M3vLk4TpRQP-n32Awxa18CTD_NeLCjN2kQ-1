package com.nitroboost.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Base64
import com.nitroboost.app.core.ShellResult
import com.nitroboost.app.service.NitroUserService
import com.nitroboost.app.shizuku.INitroService
import rikka.shizuku.Shizuku
import kotlin.concurrent.Volatile

/**
 * Thin, exception-proof bridge to Shizuku (API 13.1.5).
 *
 * Privileged commands run inside a Shizuku **user service** process
 * (shell uid 2000). Every public method is try/catch wrapped: a Shizuku
 * outage must degrade the app, never crash it.
 */
object ShizukuShell {

    private const val REQUEST_CODE = 1

    @Volatile
    private var service: INitroService? = null

    @Volatile
    private var bound = false

    private var conn: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var appCtx: Context? = null

    /** True when the Shizuku binder is alive (the Shizuku app is running). */
    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ask the user to grant Shizuku permission.
     * Returns true when the request was dispatched (the user still decides).
     * The Shizuku UI request must be dispatched from the main thread.
     */
    fun requestPermission(): Boolean {
        return try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Shizuku.requestPermission(REQUEST_CODE)
                    } catch (e: Exception) {
                        // ignored — surfaced via status later
                    }
                }
                return true
            }
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open the Shizuku app so the user can start it / grant permission.
     */
    fun openShizukuApp(ctx: Context) {
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
            if (intent != null) {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            // Shizuku not installed — nothing to open
        }
    }

    /**
     * Make sure the user service is bound. Call on a background thread
     * (binding can take a moment). Returns true when the service is usable.
     */
    fun ensureBound(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        if (service != null) return true
        if (!isReady()) return false
        if (!isPermissionGranted()) {
            requestPermission()
            return false
        }
        val c = ctx.applicationContext
        val existing = conn
        if (existing != null) {
            // Already binding / bound — give it a moment
            return waitForService(1500)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    service = INitroService.Stub.asInterface(binder)
                    bound = true
                } catch (e: Exception) {
                    service = null
                    bound = false
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
            }
        }
        conn = connection
        val a = try {
            Shizuku.UserServiceArgs(
                ComponentName(c.packageName, NitroUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("nitro")
                .version(1)
        } catch (e: Exception) {
            null
        }
        args = a
        if (a == null) return false
        try {
            Shizuku.bindUserService(a, connection)
        } catch (e: Exception) {
            conn = null
            args = null
            return false
        }
        return waitForService(2500)
    }

    private fun waitForService(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service != null) return true
            if (!isReady()) return false
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return service != null
    }

    /** Release the binding (e.g. when Shizuku dies). */
    fun unbind() {
        val a = args
        val c = conn
        if (a != null && c != null) {
            try {
                Shizuku.unbindUserService(a, c, true)
            } catch (e: Exception) {
                // already gone
            }
        }
        args = null
        conn = null
        service = null
        bound = false
    }

    private fun parseRaw(raw: String): ShellResult {
        val parts = raw.split('\u0000')
        val code = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val out = decodeB64(parts.getOrNull(1))
        val err = decodeB64(parts.getOrNull(2))
        return ShellResult(code == 0, code, out, err)
    }

    fun run(cmd: String): ShellResult {
        val s = service
        if (s == null) return ShellResult.fail("shizuku_service_not_bound")
        if (!isReady()) {
            unbind()
            return ShellResult.fail("shizuku_not_ready")
        }
        return try {
            parseRaw(s.runShell(cmd))
        } catch (e: RemoteException) {
            unbind()
            ShellResult.fail(e.message ?: "remote_error")
        } catch (e: Exception) {
            ShellResult.fail(e.message ?: "error")
        }
    }

    /**
     * Non-blocking variant: runs only when the user service is already
     * bound. Safe to call from the main thread (no binding wait).
     */
    fun runIfReady(cmd: String): ShellResult? {
        val s = service ?: return null
        if (!isReady()) return null
        return try {
            parseRaw(s.runShell(cmd))
        } catch (e: RemoteException) {
            unbind()
            null
        } catch (e: Exception) {
            null
        }
    }

    fun readSys(path: String): String? {
        val s = service
        if (s == null) return null
        if (!isReady()) return null
        return try {
            val v = s.readSys(path)
            if (v.isNotEmpty()) v else null
        } catch (e: RemoteException) {
            unbind()
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeB64(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        return try {
            String(Base64.decode(v, Base64.NO_WRAP))
        } catch (e: Exception) {
            ""
        }
    }

    /** True when a privileged command actually succeeds end-to-end. */
    fun isUsable(ctx: Context): Boolean {
        if (!isReady()) return false
        if (!ensureBound(ctx)) return false
        return run("true").ok
    }
}
