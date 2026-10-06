package com.nitroboost.app.core

/**
 * Write-ahead journal wrapper used only while a BoostTask applies changes.
 * A reversible mutation is durably recorded before it reaches the platform;
 * if persistence fails, the underlying write is not attempted.
 */
class JournaledSystemExecutor(
    private val delegate: SystemExecutor,
    private val journal: Journal,
    private val taskId: String
) : SystemExecutor {

    override val privileged: Boolean get() = delegate.privileged

    override fun shell(cmd: String): ShellResult {
        SETTINGS_PUT.matchEntire(cmd)?.let { match ->
            val kind = match.groupValues[1]
            val key = match.groupValues[2]
            val value = match.groupValues[3]
            return putShellSetting(cmd, kind, key, value)
        }
        SETTINGS_DELETE.matchEntire(cmd)?.let { match ->
            val kind = match.groupValues[1]
            val key = match.groupValues[2]
            return deleteShellSetting(cmd, kind, key)
        }
        DENSITY_PUT.matchEntire(cmd)?.let { match ->
            val dpi = match.groupValues[1].toIntOrNull() ?: return ShellResult.fail("invalid_density")
            if (taskId != "display" || dpi !in MIN_DPI..MAX_DPI) {
                return ShellResult.fail("unowned_density_mutation")
            }
            return setDensity(cmd, dpi)
        }
        GAME_DOWNSCALE.matchEntire(cmd)?.let { match ->
            val level = match.groupValues[1]
            val pkg = match.groupValues[2]
            if (taskId != "game_api_downscale" || !ShellInput.isPackageName(pkg)) {
                return ShellResult.fail("unowned_game_mutation")
            }
            return runUnverifiableMutation(
                JournalEntry(
                    taskId, JournalEntry.Kind.CMD, "game_api:$pkg", "none", "--downscale $level",
                    "cmd game reset $pkg 2>/dev/null"
                ), cmd
            )
        }
        GAME_MODE.matchEntire(cmd)?.let { match ->
            val pkg = match.groupValues[1]
            if (taskId != "game_perf_mode" || !ShellInput.isPackageName(pkg)) {
                return ShellResult.fail("unowned_game_mutation")
            }
            return runUnverifiableMutation(
                JournalEntry(
                    taskId, JournalEntry.Kind.CMD, "game_mode_api:$pkg", "1", "2",
                    "cmd game set --mode 1 $pkg 2>/dev/null"
                ), cmd
            )
        }
        DEVICE_IDLE_WHITELIST.matchEntire(cmd)?.let { match ->
            val prefix = match.groupValues[1]
            val pkg = match.groupValues[2]
            if (!ShellInput.isPackageName(pkg)) return ShellResult.fail("invalid_deviceidle_package")
            val entry = if (prefix == "+" && taskId == "doze_whitelist") {
                JournalEntry(
                    taskId, JournalEntry.Kind.CMD, "doze_whitelist:$pkg", "absent", "present",
                    "cmd deviceidle whitelist -$pkg 2>/dev/null"
                )
            } else if (prefix.isEmpty() && taskId == "device_idle") {
                JournalEntry(
                    taskId, JournalEntry.Kind.CMD, "device_idle:$pkg", null, "whitelisted",
                    "cmd deviceidle whitelist-remove $pkg 2>/dev/null"
                )
            } else return ShellResult.fail("unowned_deviceidle_mutation")
            return runUnverifiableMutation(entry, cmd)
        }
        if (cmd == "cmd thermalservice override-status 0") {
            if (taskId != "thermal_override") return ShellResult.fail("unowned_thermal_mutation")
            return runUnverifiableMutation(
                JournalEntry(
                    taskId, JournalEntry.Kind.THERMAL_OVERRIDE, "thermal_override", "-1", "0",
                    "cmd thermalservice override-status -1"
                ), cmd
            )
        }
        return delegate.shell(cmd)
    }

    override fun readSys(path: String): String? = delegate.readSys(path)

    override fun writeSys(path: String, value: String): Boolean {
        if (!ShellInput.isSysPath(path) || !ShellInput.isSysValue(value)) return false
        val oldValue = delegate.readSys(path)?.trim() ?: return false
        if (oldValue == value) return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.SYSFS, path, oldValue, value)
        return runTypedMutation(entry, { delegate.readSys(path)?.trim() }) {
            delegate.writeSys(path, value)
        }
    }

    override fun sysSettingGet(key: String): String? = delegate.sysSettingGet(key)

    override fun sysSettingPut(key: String, value: String): Boolean {
        if (!ShellInput.isSettingKey(key) || !ShellInput.isSettingValue(value)) return false
        val oldValue = delegate.sysSettingGet(key)
        if (oldValue == value) return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.SYS_SETTING, key, oldValue, value)
        return runTypedMutation(entry, { delegate.sysSettingGet(key) }) {
            delegate.sysSettingPut(key, value)
        }
    }

    override fun sysSettingDelete(key: String): Boolean {
        if (!ShellInput.isSettingKey(key)) return false
        val oldValue = delegate.sysSettingGet(key) ?: return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.SYS_SETTING, key, oldValue, null)
        return runTypedMutation(entry, { delegate.sysSettingGet(key) }) {
            delegate.sysSettingDelete(key)
        }
    }

    override fun secureSettingGet(key: String): String? = delegate.secureSettingGet(key)

    override fun secureSettingPut(key: String, value: String): Boolean {
        if (!ShellInput.isSettingKey(key) || !ShellInput.isSettingValue(value)) return false
        val oldValue = delegate.secureSettingGet(key)
        if (oldValue == value) return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.SECURE_SETTING, key, oldValue, value)
        return runTypedMutation(entry, { delegate.secureSettingGet(key) }) {
            delegate.secureSettingPut(key, value)
        }
    }

    override fun secureSettingDelete(key: String): Boolean {
        if (!ShellInput.isSettingKey(key)) return false
        val oldValue = delegate.secureSettingGet(key) ?: return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.SECURE_SETTING, key, oldValue, null)
        return runTypedMutation(entry, { delegate.secureSettingGet(key) }) {
            delegate.secureSettingDelete(key)
        }
    }

    override fun globalSettingGet(key: String): String? = delegate.globalSettingGet(key)

    override fun globalSettingPut(key: String, value: String): Boolean {
        if (!ShellInput.isSettingKey(key) || !ShellInput.isSettingValue(value)) return false
        val oldValue = delegate.globalSettingGet(key)
        if (oldValue == value) return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.GLOBAL_SETTING, key, oldValue, value)
        return runTypedMutation(entry, { delegate.globalSettingGet(key) }) {
            delegate.globalSettingPut(key, value)
        }
    }

    override fun globalSettingDelete(key: String): Boolean {
        if (!ShellInput.isSettingKey(key)) return false
        val oldValue = delegate.globalSettingGet(key) ?: return true
        val entry = JournalEntry(taskId, JournalEntry.Kind.GLOBAL_SETTING, key, oldValue, null)
        return runTypedMutation(entry, { delegate.globalSettingGet(key) }) {
            delegate.globalSettingDelete(key)
        }
    }

    override fun dndFilterGet(): Int = delegate.dndFilterGet()

    override fun dndFilterSet(filter: Int): Boolean {
        if (filter !in DndFilters.ALL..DndFilters.NONE) return false
        val oldFilter = delegate.dndFilterGet()
        if (oldFilter !in DndFilters.ALL..DndFilters.NONE) return false
        if (oldFilter == filter) return true
        val entry = JournalEntry(
            taskId, JournalEntry.Kind.DND, "interruption_filter", oldFilter.toString(), filter.toString()
        )
        return runTypedMutation(entry, { delegate.dndFilterGet().toString() }) {
            delegate.dndFilterSet(filter)
        }
    }

    private fun putShellSetting(cmd: String, namespace: String, key: String, value: String): ShellResult {
        if (!ShellInput.isSettingKey(key) || !ShellInput.isSettingValue(value)) {
            return ShellResult.fail("invalid_setting_input")
        }
        val kind = settingKind(namespace) ?: return ShellResult.fail("invalid_setting_namespace")
        val oldValue = readSetting(namespace, key)
        if (oldValue == value) return delegate.shell(cmd)
        val entry = JournalEntry(taskId, kind, key, oldValue, value)
        return runShellMutation(entry, { readSetting(namespace, key) }, value, cmd)
    }

    private fun deleteShellSetting(cmd: String, namespace: String, key: String): ShellResult {
        if (!ShellInput.isSettingKey(key)) return ShellResult.fail("invalid_setting_key")
        val kind = settingKind(namespace) ?: return ShellResult.fail("invalid_setting_namespace")
        val oldValue = readSetting(namespace, key) ?: return ShellResult(true, 0, "", "")
        val entry = JournalEntry(taskId, kind, key, oldValue, null)
        return runShellMutation(entry, { readSetting(namespace, key) }, null, cmd)
    }

    private fun readSetting(namespace: String, key: String): String? = when (namespace) {
        "system" -> delegate.sysSettingGet(key)
        "secure" -> delegate.secureSettingGet(key)
        "global" -> delegate.globalSettingGet(key)
        else -> null
    }

    private fun settingKind(namespace: String): JournalEntry.Kind? = when (namespace) {
        "system" -> JournalEntry.Kind.SYS_SETTING
        "secure" -> JournalEntry.Kind.SECURE_SETTING
        "global" -> JournalEntry.Kind.GLOBAL_SETTING
        else -> null
    }

    private data class DensityState(val readable: Boolean, val overrideDpi: Int?)

    private fun densityState(): DensityState {
        val result = delegate.shell("wm density")
        if (!result.ok) return DensityState(false, null)
        val physical = Regex("Physical density:\\s*(\\d+)").find(result.stdout)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val overrideText = Regex("Override density:\\s*(\\d+)").find(result.stdout)
            ?.groupValues?.getOrNull(1)
        val override = overrideText?.toIntOrNull()
        val validOverride = overrideText == null || override != null && override in MIN_DPI..MAX_DPI
        return DensityState(
            readable = physical != null && physical in MIN_DPI..MAX_DPI && validOverride,
            overrideDpi = override
        )
    }

    private fun setDensity(cmd: String, dpi: Int): ShellResult {
        val before = densityState()
        if (!before.readable) return ShellResult.fail("original_density_unavailable")
        if (before.overrideDpi == dpi) return delegate.shell(cmd)
        val oldValue = before.overrideDpi?.toString()
        val entry = JournalEntry(
            taskId = taskId,
            kind = JournalEntry.Kind.CMD,
            key = "wm_density",
            oldValue = oldValue,
            newValue = dpi.toString(),
            revertCmd = if (oldValue == null) "wm density reset" else "wm density $oldValue"
        )
        return runShellMutation(entry, {
            val state = densityState()
            if (state.readable) state.overrideDpi?.toString() else UNKNOWN_READBACK
        }, dpi.toString(), cmd)
    }

    private fun runTypedMutation(
        entry: JournalEntry,
        readBack: () -> String?,
        operation: () -> Boolean
    ): Boolean {
        val existedBefore = journal.containsTaskKey(entry.taskId, entry.key)
        if (!prepare(entry, existedBefore)) return false
        val operationResult = try {
            operation()
        } catch (e: Exception) {
            cleanupIfUnchanged(entry, existedBefore, readBack())
            throw e
        }
        val current = readBack()
        return when {
            current == entry.newValue -> true
            current == entry.oldValue -> {
                cleanupIfUnchanged(entry, existedBefore, current)
                false
            }
            else -> operationResult && current == entry.newValue
        }
    }

    private fun runShellMutation(
        entry: JournalEntry,
        readBack: () -> String?,
        expected: String?,
        cmd: String
    ): ShellResult {
        val existedBefore = journal.containsTaskKey(entry.taskId, entry.key)
        if (!prepare(entry, existedBefore)) return ShellResult.fail("journal_persist_failed")
        val result = try {
            delegate.shell(cmd)
        } catch (e: Exception) {
            cleanupIfUnchanged(entry, existedBefore, readBack())
            throw e
        }
        val current = readBack()
        return when {
            current == expected -> if (result.ok) result else ShellResult(true, 0, result.stdout, result.stderr)
            current == entry.oldValue -> {
                cleanupIfUnchanged(entry, existedBefore, current)
                result
            }
            current == UNKNOWN_READBACK -> result.copy(ok = false, stderr = "post-write state unavailable")
            else -> if (result.ok) result.copy(ok = false, stderr = "post-write verification failed") else result
        }
    }

    private fun runUnverifiableMutation(entry: JournalEntry, cmd: String): ShellResult {
        val existedBefore = journal.containsTaskKey(entry.taskId, entry.key)
        if (!prepare(entry, existedBefore)) return ShellResult.fail("journal_persist_failed")
        return try {
            delegate.shell(cmd)
        } catch (e: Exception) {
            throw e // The durable write-ahead entry is intentionally retained.
        }
    }

    private fun prepare(entry: JournalEntry, existedBefore: Boolean): Boolean {
        if (journal.recoveryBlocked || !journal.add(listOf(entry))) {
            if (!existedBefore) journal.remove(listOf(entry))
            return false
        }
        return true
    }

    private fun cleanupIfUnchanged(entry: JournalEntry, existedBefore: Boolean, current: String?) {
        if (!existedBefore && current == entry.oldValue) journal.remove(listOf(entry))
    }

    companion object {
        private const val MIN_DPI = 72
        private const val MAX_DPI = 1_000
        private const val UNKNOWN_READBACK = "\u0000unknown-readback"
        private val SETTINGS_PUT = Regex(
            "settings put (system|secure|global) ([A-Za-z0-9_.-]{1,100}) ([A-Za-z0-9_.:+-]{1,256})"
        )
        private val SETTINGS_DELETE = Regex(
            "settings delete (system|secure|global) ([A-Za-z0-9_.-]{1,100})"
        )
        private val DENSITY_PUT = Regex("wm density (\\d{1,4})")
        private val GAME_DOWNSCALE = Regex(
            "cmd game set --downscale (0\\.9|0\\.8|0\\.7) ([A-Za-z0-9_.]+) (?:2>&1|2>/dev/null)"
        )
        private val GAME_MODE = Regex(
            "cmd game set --mode 2 ([A-Za-z0-9_.]+) 2>&1"
        )
        private val DEVICE_IDLE_WHITELIST = Regex(
            "cmd deviceidle whitelist (\\+?)([A-Za-z0-9_.]+) (2>&1|2>/dev/null)"
        )
    }
}
