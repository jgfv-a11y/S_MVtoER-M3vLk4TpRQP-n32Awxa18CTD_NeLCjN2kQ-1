package com.nitroboost.app.core

/**
 * Single source of truth for every string that may reach a privileged shell
 * (Shizuku user service or `su -c`).
 *
 * Two independent layers consume this policy:
 *  - [JournaledSystemExecutor] admits only [Disposition.READ_ONLY] commands
 *    and task-owned [Disposition.ONE_WAY] commands as fallthrough; every
 *    other mutation must go through its write-ahead journaled paths.
 *  - The privileged processes (`NitroUserService`, `RootShell`) refuse to
 *    execute anything classified [Disposition.UNKNOWN], so a caller bug can
 *    never turn into arbitrary shell/root command execution.
 *
 * Patterns are anchored (`matchEntire`-style semantics via the callers) and
 * every interpolated value is re-validated with [ShellInput]. Anything with
 * shell metacharacters (`;`, `|`, backticks, `$(...)`, redirects other than
 * the fixed ones below) classifies as UNKNOWN.
 */
object PrivilegedShellPolicy {

    enum class Disposition { READ_ONLY, MUTATING, ONE_WAY, UNKNOWN }

    private const val PKG = "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"
    private const val KEY = "[A-Za-z0-9_.-]{1,100}"
    private const val VALUE = "[A-Za-z0-9_.:+-]{1,256}"

    private val READ_ONLY_EXACT = setOf(
        "wm density",
        "cmd deviceidle help 2>&1",
        "cmd game help 2>&1",
        "cmd deviceidle whitelist",
        "dumpsys deviceidle whitelist",
        "ps -A -o PID,RSS,NAME"
    )

    /** Fixed read-only enumeration loops used by the CPU tasks (exact strings). */
    private val READ_ONLY_EXACT_LOOPS = setOf(
        "for d in /sys/devices/system/cpu/cpufreq/policy*; do " +
            "[ -d \"\$d\" ] && echo \"\$d \$(cat \$d/cpuinfo_max_freq 2>/dev/null) " +
            "\$(cat \$d/scaling_min_freq 2>/dev/null)\"; done",
        "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do " +
            "[ -f \"\$f\" ] && echo \"\$f \$(cat \"\$f\" 2>/dev/null)\"; done",
        "for c in /sys/devices/system/cpu/cpu[0-9]*/online; do " +
            "echo \"\$(basename \$(dirname \$c))=\$(cat \$c 2>/dev/null)\"; done 2>/dev/null"
    )

    private val SETTINGS_GET = Regex("settings get (?:system|secure|global) $KEY")
    private val SETTINGS_PUT = Regex("settings put (?:system|secure|global) $KEY $VALUE")
    private val SETTINGS_DELETE = Regex("settings delete (?:system|secure|global) $KEY")
    private val GFXINFO = Regex("dumpsys gfxinfo \"($PKG)\" framestats 2>/dev/null")
    private val CAT_SYS = Regex("cat \"([^\"]+)\" 2>/dev/null")
    private val ECHO_SYS = Regex("echo \"([^\"]*)\" > \"([^\"]+)\" 2>/dev/null")
    private val FORCE_STOP = Regex("am force-stop \"($PKG)\"")
    private val TRIM_CACHES = "pm trim-caches 9223372036854775807"

    private val MUTATING_PATTERNS = listOf(
        Regex("wm density \\d{1,4}"),
        Regex("wm density reset"),
        Regex("cmd game set --downscale (?:0\\.9|0\\.8|0\\.7) $PKG (?:2>&1|2>/dev/null)"),
        Regex("cmd game set --mode [12] $PKG (?:2>&1|2>/dev/null)"),
        Regex("cmd game reset $PKG 2>/dev/null"),
        Regex("cmd deviceidle whitelist \\+?$PKG (?:2>&1|2>/dev/null)"),
        Regex("cmd deviceidle whitelist -$PKG 2>/dev/null"),
        Regex("cmd deviceidle whitelist-remove $PKG 2>/dev/null"),
        Regex("cmd thermalservice override-status (?:-1|0)")
    )

    fun classify(cmd: String): Disposition {
        if (cmd.isEmpty() || cmd.length > 4096) return Disposition.UNKNOWN
        if (cmd in READ_ONLY_EXACT) return Disposition.READ_ONLY
        if (cmd in READ_ONLY_EXACT_LOOPS) return Disposition.READ_ONLY
        if (SETTINGS_GET.matches(cmd)) return Disposition.READ_ONLY
        GFXINFO.matchEntire(cmd)?.let {
            return if (ShellInput.isPackageName(it.groupValues[1])) {
                Disposition.READ_ONLY
            } else {
                Disposition.UNKNOWN
            }
        }
        CAT_SYS.matchEntire(cmd)?.let {
            return if (ShellInput.isSysPath(it.groupValues[1])) {
                Disposition.READ_ONLY
            } else {
                Disposition.UNKNOWN
            }
        }
        if (cmd == TRIM_CACHES) return Disposition.ONE_WAY
        FORCE_STOP.matchEntire(cmd)?.let {
            return if (ShellInput.isPackageName(it.groupValues[1])) {
                Disposition.ONE_WAY
            } else {
                Disposition.UNKNOWN
            }
        }
        if (SETTINGS_PUT.matches(cmd) || SETTINGS_DELETE.matches(cmd)) return Disposition.MUTATING
        ECHO_SYS.matchEntire(cmd)?.let {
            val value = it.groupValues[1]
            val path = it.groupValues[2]
            return if (ShellInput.isSysPath(path) && ShellInput.isSysValue(value)) {
                Disposition.MUTATING
            } else {
                Disposition.UNKNOWN
            }
        }
        if (MUTATING_PATTERNS.any { it.matches(cmd) }) return Disposition.MUTATING
        return Disposition.UNKNOWN
    }

    /** True when a privileged process may execute [cmd] at all. */
    fun isAllowed(cmd: String): Boolean = classify(cmd) != Disposition.UNKNOWN

    /** Package of an `am force-stop "pkg"` command, or null when not one. */
    fun forceStopPackage(cmd: String): String? =
        FORCE_STOP.matchEntire(cmd)?.groupValues?.get(1)
            ?.takeIf(ShellInput::isPackageName)

    fun isTrimCaches(cmd: String): Boolean = cmd == TRIM_CACHES
}
