package com.nitroboost.app.core

/**
 * In-memory SystemExecutor for JVM unit tests.
 * Records every write so tests can assert exactly what happened.
 */
class FakeExecutor : SystemExecutor {

    val sys = HashMap<String, String>()
    val secure = HashMap<String, String>()
    val global = HashMap<String, String>()
    val sysfs = HashMap<String, String>()
    val written: MutableList<String> = mutableListOf()
    val shellLog: MutableList<String> = mutableListOf()

    var dndFilter = DndFilters.ALL
    var failDnd = false
    /** Simulates a device that refuses Settings.System writes (no WRITE_SETTINGS). */
    var failSys = false
    override var privileged: Boolean = true

    override fun shell(cmd: String): ShellResult {
        shellLog.add(cmd)

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
        if (cmd.startsWith("cmd deviceidle whitelist")) {
            val remove = cmd.contains("whitelist-remove")
            written.add("deviceidle:${if (remove) "rm:" else "add:"}$cmd")
            return ShellResult(true, 0, "", "")
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
        return ShellResult(true, 0, "", "")
    }

    override fun readSys(path: String): String? = sysfs[path]

    override fun writeSys(path: String, value: String): Boolean {
        sysfs[path] = value
        written.add("sysfs:$path")
        return true
    }

    override fun sysSettingGet(key: String): String? = sys[key]
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

    override fun globalSettingGet(key: String): String? = global[key]
    override fun globalSettingPut(key: String, value: String): Boolean {
        global[key] = value
        written.add("global:$key")
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
