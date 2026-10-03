package com.nitroboost.app.core

/** Strict validation for values interpolated into privileged shell commands. */
object ShellInput {

    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val sysPathPattern = Regex("/[A-Za-z0-9_./-]+")
    private val sysValuePattern = Regex("[A-Za-z0-9_.:+-]{1,128}")
    private val settingKeyPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val settingValuePattern = Regex("[A-Za-z0-9_.:+-]{0,256}")
    private val tokenPattern = Regex("[A-Za-z0-9_.+-]{1,64}")

    fun isPackageName(value: String): Boolean =
        value.length <= 255 && packageNamePattern.matches(value)

    /** Only the sysfs/proc-sys trees used by tuning tasks may be accessed. */
    fun isSysPath(value: String): Boolean =
        value.length <= 255 &&
            sysPathPattern.matches(value) &&
            (value.startsWith("/sys/") || value.startsWith("/proc/sys/")) &&
            value.removePrefix("/").split('/').none { it == ".." || it.isEmpty() }

    fun isSysValue(value: String): Boolean = sysValuePattern.matches(value)

    fun isSettingKey(value: String): Boolean = settingKeyPattern.matches(value)

    fun isSettingValue(value: String): Boolean = settingValuePattern.matches(value)

    /** Single shell token used for values such as CPU governors. */
    fun isToken(value: String): Boolean = tokenPattern.matches(value)
}
