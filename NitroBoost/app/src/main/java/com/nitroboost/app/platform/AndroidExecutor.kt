package com.nitroboost.app.platform

import android.content.Context
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import com.nitroboost.app.core.DndFilters
import com.nitroboost.app.core.ShellResult
import com.nitroboost.app.core.SystemExecutor
import java.io.File
import kotlin.concurrent.Volatile

/**
 * Android implementation of [SystemExecutor].
 * Reads go through public Settings APIs (no permission needed); writes use
 * the public APIs when the user granted them, and fall back to the Shizuku
 * user service for privileged keys.
 */
class AndroidExecutor(private val context: Context) : SystemExecutor {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Privilege check with a short TTL cache. It probes Shizuku (binder +
     * user service) and possibly root; the UI asks it on every list
     * refresh, so an uncached probe would thrash both channels.
     */
    @Volatile
    private var privCache: Pair<Boolean, Long> = (false to 0L)
    private val PRIV_TTL_MS = 2_000L

    override val privileged: Boolean
        get() {
            val (cached, at) = privCache
            if (System.currentTimeMillis() - at < PRIV_TTL_MS) return cached
            val value = try {
                ShizukuShell.isUsable(context) || RootShell.isAvailable()
            } catch (e: Exception) {
                false
            }
            privCache = value to System.currentTimeMillis()
            return value
        }

    /**
     * Shell command through the best available privileged channel:
     * Shizuku first, then root. Never through the unprivileged app shell
     * (that would silently do nothing on most ROMs).
     */
    private fun runPriv(cmd: String): ShellResult {
        if (ShizukuShell.isReady()) {
            if (ShizukuShell.ensureBound(context)) {
                return ShizukuShell.run(cmd)
            }
        }
        if (RootShell.isAvailable()) {
            return RootShell.run(cmd)
        }
        return ShellResult.fail("no_privileged_channel")
    }

    override fun shell(cmd: String): ShellResult = runPriv(cmd)

    /** Shell command without waiting for a service bind; null when unavailable. */
    fun shellNonBlocking(cmd: String): ShellResult? {
        if (ShizukuShell.isReady()) {
            ShizukuShell.runIfReady(cmd)?.let { return it }
        }
        return if (RootShell.isAvailable()) RootShell.run(cmd) else null
    }

    override fun readSys(path: String): String? {
        try {
            val f = File(path)
            if (f.canRead()) return f.readText().trim()
        } catch (e: Exception) {
            // fall through to the privileged service
        }
        if (ShizukuShell.isReady()) {
            if (ShizukuShell.ensureBound(context)) {
                ShizukuShell.readSys(path)?.let { return it }
            }
        }
        return if (RootShell.isAvailable()) RootShell.readSys(path) else null
    }

    override fun writeSys(path: String, value: String): Boolean {
        if (ShizukuShell.isReady() && ShizukuShell.ensureBound(context)) {
            val r = ShizukuShell.run("echo \"$value\" > \"$path\" 2>/dev/null")
            if (r.ok && readSys(path) == value) return true
        }
        if (RootShell.isAvailable()) {
            return RootShell.writeSys(path, value)
        }
        return false
    }

    // ---------------- Settings.System ----------------

    override fun sysSettingGet(key: String): String? =
        try {
            Settings.System.getString(context.contentResolver, key)
        } catch (e: Exception) {
            null
        }

    override fun sysSettingPut(key: String, value: String): Boolean {
        try {
            Settings.System.putString(context.contentResolver, key, value)
            return Settings.System.getString(context.contentResolver, key) == value
        } catch (e: Exception) {
            // no WRITE_SETTINGS grant — try the Shizuku service
        }
        val r = runPriv("settings put system $key $value")
        return r.ok && sysSettingGet(key) == value
    }

    // ---------------- Settings.Secure ----------------

    override fun secureSettingGet(key: String): String? {
        val viaApi = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (e: Exception) {
            null
        }
        if (viaApi != null) return viaApi
        val r = runPriv("settings get secure $key")
        if (r.ok) {
            val v = r.stdout.trim()
            return if (v.isNotEmpty() && v != "null") v else null
        }
        return null
    }

    override fun secureSettingPut(key: String, value: String): Boolean {
        try {
            Settings.Secure.putString(context.contentResolver, key, value)
        } catch (e: Exception) {
            // needs the Shizuku service
        }
        val r = runPriv("settings put secure $key $value")
        return r.ok && secureSettingGet(key) == value
    }

    // ---------------- Settings.Global ----------------

    override fun globalSettingGet(key: String): String? {
        val viaApi = try {
            Settings.Global.getString(context.contentResolver, key)
        } catch (e: Exception) {
            null
        }
        if (viaApi != null) return viaApi
        val r = runPriv("settings get global $key")
        if (r.ok) {
            val v = r.stdout.trim()
            return if (v.isNotEmpty() && v != "null") v else null
        }
        return null
    }

    override fun globalSettingPut(key: String, value: String): Boolean {
        try {
            Settings.Global.putString(context.contentResolver, key, value)
        } catch (e: Exception) {
            // needs the Shizuku service
        }
        val r = runPriv("settings put global $key $value")
        return r.ok && globalSettingGet(key) == value
    }

    // ---------------- DND ----------------

    override fun dndFilterGet(): Int {
        return try {
            when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndFilters.PRIORITY
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndFilters.ALARMS
                NotificationManager.INTERRUPTION_FILTER_NONE -> DndFilters.NONE
                else -> DndFilters.ALL
            }
        } catch (e: Exception) {
            DndFilters.ALL
        }
    }

    override fun dndFilterSet(filter: Int): Boolean {
        return try {
            val target = when (filter) {
                DndFilters.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DndFilters.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                DndFilters.NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            }
            notificationManager.setInterruptionFilter(target)
            dndFilterGet() == filter
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- Thermal ----------------

    @Suppress("DEPRECATION")
    fun thermalStatus(): Int {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (e: Exception) {
            0
        }
    }
}
