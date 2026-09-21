package com.nitroboost.app.core

/** Result of a privileged shell command. */
data class ShellResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    companion object {
        fun fail(message: String): ShellResult = ShellResult(false, -1, "", message)
    }
}

/**
 * Abstraction over the privileged / system surface.
 *
 * The Android implementation uses Settings APIs + Shizuku shell.
 * Tests use an in-memory fake — which is why the whole engine is unit-testable.
 */
interface SystemExecutor {
    /** True when a privileged shell (Shizuku) is available. */
    val privileged: Boolean

    /** Run a shell command (as Shizuku shell user when privileged). */
    fun shell(cmd: String): ShellResult

    /** Read a /sys (or /proc) file; null when not readable. */
    fun readSys(path: String): String?

    /** Write a /sys file; true when the read-back matches the value. */
    fun writeSys(path: String, value: String): Boolean

    fun sysSettingGet(key: String): String?
    fun sysSettingPut(key: String, value: String): Boolean

    fun secureSettingGet(key: String): String?
    fun secureSettingPut(key: String, value: String): Boolean

    fun globalSettingGet(key: String): String?
    fun globalSettingPut(key: String, value: String): Boolean

    /** Current notification interruption filter (see [DndFilters]). */
    fun dndFilterGet(): Int

    /** Set the notification interruption filter; true on success. */
    fun dndFilterSet(filter: Int): Boolean
}

/** Interruption filter constants (mirror of NotificationManager). */
object DndFilters {
    const val ALL = 0
    const val PRIORITY = 1
    const val ALARMS = 2
    const val NONE = 3
}
