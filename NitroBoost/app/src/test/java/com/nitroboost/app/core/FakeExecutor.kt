package com.nitroboost.app.core

/**
 * In-memory SystemExecutor for JVM unit tests.
 * Records every write so tests can assert exactly what happened.
 *
 * Fail-closed: a shell command with no explicit handler below returns
 * failure instead of success. A typo'd or renamed command in production
 * code must fail its test, not silently "work" against the fake.
 */
class FakeExecutor : SystemExecutor {

    val sys = HashMap<String, String>()
    val secure = HashMap<String, String>()
    val global = HashMap<String, String>()
    val sysfs = HashMap<String, String>()
    val written: MutableList<String> = mutableListOf()
    val shellLog: MutableList<String> = mutableListOf()
    /** Packages currently on the simulated deviceidle whitelist. */
    val dozeWhitelist: MutableSet<String> = mutableSetOf()

    var dndFilter = DndFilters.ALL
    var failDnd = false
    /** Simulates a platform delete that reports success but leaves the value behind. */
    var preserveSysDelete = false
    /** Simulates a device that refuses Settings.System writes (no WRITE_SETTINGS). */
    var failSys = false
    var physicalDensity = 420
    var densityOverride: Int? = null
    override var privileged: Boolean = true
    override var thermalSafetyAvailable: Boolean = false

    override fun shell(cmd: String): ShellResult {
        shellLog.add(cmd)

        if (cmd == "wm density") {
            val output = buildString {
                append("Physical density: $physicalDensity\n")
                densityOverride?.let { append("Override density: $it\n") }
            }
            return ShellResult(true, 0, output, "")
        }
        if (cmd == "wm density reset") {
            densityOverride = null
            written.add("wm-density-reset")
            return ShellResult(true, 0, "", "")
        }
        Regex("wm density (\\d+)").matchEntire(cmd)?.let { m ->
            densityOverride = m.groupValues[1].toInt()
            written.add("wm-density:${m.groupValues[1]}")
            return ShellResult(true, 0, "", "")
        }

        // Simulate the cpufreq policy enumeration loop (cpu_floor task)
        if (cmd.startsWith("for d in /sys/devices/system/cpu/cpufreq/policy*")) {
            val dirs = sysfs.keys
                .map { it.substringBefore("/cpuinfo_").substringBefore("/scaling_") }
                .filter { it.contains("/cpufreq/policy") }
                .distinct()
                .sorted()
            val out = dirs.mapNotNull { dir ->
                val max = sysfs["$dir/cpuinfo_max_freq"] ?: return@mapNotNull null
                val min = sysfs["$dir/scaling_min_freq"] ?: return@mapNotNull null
                "$dir $max $min"
            }.joinToString("\n")
            return ShellResult(true, 0, out, "")
        }

        // Simulate the governor listing loop
        if (cmd.startsWith("for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor")) {
            val out = sysfs.filterKeys { it.endsWith("scaling_governor") }
                .map { (k, v) -> "$k $v" }
                .joinToString("\n")
            return ShellResult(true, 0, out, "")
        }

        // v1.5: core-online listing loop (cpu_online task)
        if (cmd.startsWith("for c in /sys/devices/system/cpu/cpu")) {
            val out = sysfs.filterKeys { it.endsWith("/online") }
                .map { (k, v) ->
                    "${k.substringAfterLast("cpu").substringBefore("/online")}=$v"
                }
                .joinToString("\n")
            return ShellResult(true, 0, out, "")
        }

        // v1.5: device idle (Doze) API
        if (cmd.startsWith("cmd deviceidle help")) {
            return ShellResult(
                true, 0,
                "deviceidle commands:\n whitelist <pkg>\n whitelist-remove <pkg>", ""
            )
        }
        if (cmd == "cmd deviceidle whitelist") {
            return ShellResult(true, 0, dozeWhitelist.joinToString("\n"), "")
        }
        if (cmd == "dumpsys deviceidle whitelist") {
            val out = dozeWhitelist.joinToString("\n") { "Whitelist user app: $it" }
            return ShellResult(true, 0, out, "")
        }
        if (cmd.startsWith("cmd deviceidle whitelist")) {
            val remove = cmd.contains("whitelist-remove") ||
                Regex("whitelist\\s+-").containsMatchIn(cmd)
            val pkg = cmd.removePrefix("cmd deviceidle whitelist")
                .removePrefix("-remove")
                .trim()
                .removeSuffix("2>&1")
                .removeSuffix("2>/dev/null")
                .trim()
                .trimStart('+', '-')
                .trim()
            if (remove) dozeWhitelist.remove(pkg) else if (pkg.isNotEmpty()) dozeWhitelist.add(pkg)
            written.add("deviceidle:${if (remove) "rm:" else "add:"}$cmd")
            return ShellResult(true, 0, "", "")
        }
        if (cmd == "cmd thermalservice override-status -1" ||
            cmd == "cmd thermalservice override-status 0"
        ) {
            written.add("thermal:$cmd")
            return ShellResult(true, 0, "", "")
        }
        if (cmd == "pm trim-caches 9223372036854775807") {
            written.add("trim-caches")
            return ShellResult(true, 0, "", "")
        }
        Regex("am force-stop \"([^\"]+)\"").matchEntire(cmd)?.let { m ->
            written.add("force-stop:${m.groupValues[1]}")
            return ShellResult(true, 0, "", "")
        }
        if (cmd == "ps -A -o PID,RSS,NAME") {
            return ShellResult(true, 0, "PID RSS NAME\n", "")
        }

        // v1.5: Game Manager performance mode
        if (cmd.startsWith("cmd game set --mode")) {
            written.add("game-mode:$cmd")
            return ShellResult(true, 0, "", "")
        }

        // Simulate "echo value > path"
        Regex("echo (\\S+) > \"(.+?)\"").find(cmd)?.let { m ->
            sysfs[m.groupValues[2]] = m.groupValues[1]
            written.add("shell-echo:${m.groupValues[2]}")
            return ShellResult(true, 0, "", "")
        }

        // Simulate the Android 12+ Game Manager shell API
        if (cmd.startsWith("cmd game help")) {
            return ShellResult(
                true, 0,
                "Game manager (game) commands:\n help\n set --mode [2|3] [configs]\n reset", ""
            )
        }
        if (cmd.startsWith("cmd game set --downscale")) {
            written.add("game-api:$cmd")
            return ShellResult(true, 0, "", "")
        }
        if (cmd.startsWith("cmd game reset")) {
            written.add("game-api-reset:$cmd")
            return ShellResult(true, 0, "", "")
        }

        // Simulate "settings get" commands reading from the maps
        val getPattern = Regex("settings get (system|secure|global) (\\S+)")
        getPattern.find(cmd)?.let { m ->
            val table = when (m.groupValues[1]) {
                "system" -> sys
                "secure" -> secure
                else -> global
            }
            return ShellResult(true, 0, table[m.groupValues[2]] ?: "null", "")
        }
        val putPattern = Regex("settings put (system|secure|global) (\\S+) (\\S+)")
        putPattern.find(cmd)?.let { m ->
            val table = when (m.groupValues[1]) {
                "system" -> sys
                "secure" -> secure
                else -> global
            }
            table[m.groupValues[2]] = m.groupValues[3]
            written.add("settings:${m.groupValues[1]}:${m.groupValues[2]}")
            return ShellResult(true, 0, "", "")
        }
        val deletePattern = Regex("settings delete (system|secure|global) (\\S+)")
        deletePattern.matchEntire(cmd)?.let { m ->
            val table = when (m.groupValues[1]) {
                "system" -> sys
                "secure" -> secure
                else -> global
            }
            table.remove(m.groupValues[2])
            written.add("settings-delete:${m.groupValues[1]}:${m.groupValues[2]}")
            return ShellResult(true, 0, "", "")
        }
        // Fail closed: unhandled commands are test bugs or production drift.
        return ShellResult(false, -1, "", "fake_unhandled_command: $cmd")
    }

    override fun readSys(path: String): String? = sysfs[path]

    override fun writeSys(path: String, value: String): Boolean {
        sysfs[path] = value
        written.add("sysfs:$path")
        return true
    }

    override fun sysSettingGet(key: String): String? = sys[key]
    override fun sysSettingDelete(key: String): Boolean {
        if (!preserveSysDelete) sys.remove(key)
        written.add("sys-delete:$key")
        return true
    }
    override fun sysSettingPut(key: String, value: String): Boolean {
        if (failSys) return false
        sys[key] = value
        written.add("sys:$key")
        return true
    }

    override fun secureSettingGet(key: String): String? = secure[key]
    override fun secureSettingPut(key: String, value: String): Boolean {
        secure[key] = value
        written.add("secure:$key")
        return true
    }
    override fun secureSettingDelete(key: String): Boolean {
        secure.remove(key)
        written.add("secure-delete:$key")
        return true
    }

    override fun globalSettingGet(key: String): String? = global[key]
    override fun globalSettingPut(key: String, value: String): Boolean {
        global[key] = value
        written.add("global:$key")
        return true
    }
    override fun globalSettingDelete(key: String): Boolean {
        global.remove(key)
        written.add("global-delete:$key")
        return true
    }

    override fun dndFilterGet(): Int = dndFilter

    override fun dndFilterSet(filter: Int): Boolean {
        if (failDnd) return false
        dndFilter = filter
        written.add("dnd:$filter")
        return true
    }
}

/** Minimal test profile. */
fun testProfile(
    vararg modules: Module,
    dnd: Boolean = true,
    killAnimations: Boolean = true,
    aggressive: Boolean = false,
    thermalOverride: Boolean = false,
    gameMode: Int = 4
): AppProfile = AppProfile(
    packageName = "com.test.game",
    name = "Test Game",
    enabledModules = modules.toMutableSet(),
    dnd = dnd,
    killAnimations = killAnimations,
    aggressiveRamClean = aggressive,
    thermalOverride = thermalOverride,
    gameMode = gameMode
)
