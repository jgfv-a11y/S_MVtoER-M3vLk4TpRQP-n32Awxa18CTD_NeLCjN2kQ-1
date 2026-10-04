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
import com.nitroboost.app.core.ShellInput
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
    const val SHIZUKU_PKG = "moe.shizuku.manager"
    private const val MAX_RECONNECT_ATTEMPTS = 3
    private const val MAX_COMMAND_LENGTH = 4096

    /** Where the Shizuku setup stands — drives the in-app guidance card. */
    enum class ShizukuState {
        NOT_INSTALLED,
        NOT_STARTED,
        PENDING_PERMISSION,
        NOT_BOUND,
        READY
    }

    @Volatile
    private var service: INitroService? = null

    @Volatile
    private var bound = false

    private var conn: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var appCtx: Context? = null

    /** True when the Shizuku app is installed. */
    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Precise setup state, cheapest check first. Call on a background
     * thread (ensureBound may bind the user service).
     */
    fun state(ctx: Context): ShizukuState {
        if (!isInstalled(ctx)) return ShizukuState.NOT_INSTALLED
        if (!isReady()) return ShizukuState.NOT_STARTED
        if (!isPermissionGranted()) return ShizukuState.PENDING_PERMISSION
        return if (ensureBound(ctx)) ShizukuState.READY else ShizukuState.NOT_BOUND
    }

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
                    } catch (_: Exception) {
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
            val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            if (intent != null) {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) {
            // Shizuku not installed — nothing to open
        }
    }

    /**
     * Try to re-establish the user service binding after a disconnect.
     * Falls back gracefully to root/safe-mode if the service is unavailable.
     */
    fun reconnect(ctx: Context, maxAttempts: Int = MAX_RECONNECT_ATTEMPTS): Boolean {
        if (!isInstalled(ctx)) return false
        if (!isReady()) return false
        if (!isPermissionGranted()) {
            requestPermission()
            return false
        }
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                unbind()
                if (ensureBound(ctx)) return true
            } catch (_: Exception) {
                // ignore and retry
            }
            attempt += 1
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep((1000L shl attempt).coerceAtMost(8_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return false
    }

    /**
     * Make sure the user service is bound. Call on a background thread
     * (binding can take a moment). Returns true when the service is usable.
     */
    fun ensureBound(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        val appContext = appCtx ?: ctx.applicationContext

        if (service != null && bound && isReady()) return true
        if (!isReady()) {
            unbind()
            return false
        }
        if (!isPermissionGranted()) {
            requestPermission()
            return false
        }

        synchronized(this) {
            if (service != null && bound && isReady()) return true
            if (!isReady() || !isPermissionGranted()) {
                unbind()
                return false
            }

            if (conn != null) return waitForService(1500)

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try {
                        service = INitroService.Stub.asInterface(binder)
                        bound = service != null
                    } catch (_: Exception) {
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
            val argsToBind = try {
                Shizuku.UserServiceArgs(
                    ComponentName(appContext.packageName, NitroUserService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("nitro")
                    .version(1)
            } catch (_: Exception) {
                null
            }

            args = argsToBind
            if (argsToBind == null) {
                conn = null
                return false
            }

            return try {
                Shizuku.bindUserService(argsToBind, connection)
                waitForService(2500)
            } catch (_: Exception) {
                conn = null
                args = null
                service = null
                bound = false
                false
            }
        }
    }

    private fun waitForService(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service != null && bound && isReady()) return true
            if (!isReady()) {
                unbind()
                return false
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return service != null && bound
    }

    /** Release the binding (e.g. when Shizuku dies). */
    fun unbind() {
        synchronized(this) {
            val a = args
            val c = conn
            if (a != null && c != null) {
                try {
                    Shizuku.unbindUserService(a, c, true)
                } catch (_: Exception) {
                    // already gone
                }
            }
            args = null
            conn = null
            service = null
            bound = false
        }
    }

    private fun parseRaw(raw: String): ShellResult {
        if (raw.isBlank()) return ShellResult.fail("shizuku_empty_response")
        val parts = raw.split('\u0000')
        val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        val out = decodeB64(parts.getOrNull(1))
        val err = decodeB64(parts.getOrNull(2))
        return ShellResult(code == 0, code, out, err)
    }

    fun run(cmd: String): ShellResult {
        if (cmd.isBlank() || cmd.length > MAX_COMMAND_LENGTH) {
            return ShellResult.fail("invalid_command")
        }
        val s = service ?: return ShellResult.fail("shizuku_service_not_bound")
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
        if (cmd.isBlank() || cmd.length > MAX_COMMAND_LENGTH) return null
        val s = service ?: return null
        if (!isReady() || !bound) return null
        return try {
            parseRaw(s.runShell(cmd))
        } catch (e: RemoteException) {
            unbind()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun readSys(path: String): String? {
        if (!ShellInput.isSysPath(path)) return null
        val s = service ?: return null
        if (!isReady() || !bound) return null
        return try {
            val v = s.readSys(path)
            if (v.isNullOrBlank()) null else v.trim()
        } catch (e: RemoteException) {
            unbind()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeB64(v: String?): String {
        if (v.isNullOrBlank()) return ""
        return try {
            String(Base64.decode(v, Base64.NO_WRAP))
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * True when a privileged command actually succeeds end-to-end.
     * Cached for a short TTL: the check pings the binder AND the user
     * service on every call, and the UI asks it on every refresh —
     * without the cache the app would hammer the binder while idle.
     */
    fun isUsable(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val c = usableCache
        if (c.second + USABLE_CACHE_MS > now) return c.first
        val ok = try {
            ensureBound(ctx) && runIfReady("true")?.ok == true
        } catch (_: Exception) {
            false
        }
        usableCache = ok to now
        return ok
    }

    private var usableCache: Pair<Boolean, Long> = (false to 0L)
    private const val USABLE_CACHE_MS = 2_000L
}
