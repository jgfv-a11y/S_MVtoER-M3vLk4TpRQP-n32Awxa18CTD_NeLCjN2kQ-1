package com.nitroboost.app.platform

import android.content.Context
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import com.nitroboost.app.core.DndFilters
import com.nitroboost.app.core.ShellResult
import com.nitroboost.app.core.SystemExecutor
import java.io.File

/**
 * Android implementation of [SystemExecutor].
 * Reads go through public Settings APIs (no permission needed); writes use
 * the public APIs when the user granted them, and fall back to the Shizuku
 * user service for privileged keys.
 */
class AndroidExecutor(private val context: Context) : SystemExecutor {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override val privileged: Boolean
        get() = try {
            ShizukuShell.isUsable(context)
        } catch (e: Exception) {
            false
        }

    /** Shell command through the Shizuku user service (bound on demand). */
    private fun runPriv(cmd: String): ShellResult {
        if (!ShizukuShell.isReady()) return ShellResult.fail("shizuku_not_ready")
        if (!ShizukuShell.ensureBound(context)) return ShellResult.fail("shizuku_service_not_bound")
        return ShizukuShell.run(cmd)
    }

    override fun shell(cmd: String): ShellResult = runPriv(cmd)

    override fun readSys(path: String): String? {
        try {
            val f = File(path)
            if (f.canRead()) return f.readText().trim()
        } catch (e: Exception) {
            // fall through to the privileged service
        }
        if (!ShizukuShell.isReady()) return null
        if (!ShizukuShell.ensureBound(context)) return null
        return ShizukuShell.readSys(path)
    }

    override fun writeSys(path: String, value: String): Boolean {
        if (!ShizukuShell.isReady()) return false
        if (!ShizukuShell.ensureBound(context)) return false
        val r = ShizukuShell.run("echo \"$value\" > \"$path\" 2>/dev/null")
        if (!r.ok) return false
        return readSys(path) == value
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
            when (notificationManager.interruptionFilter) {
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
