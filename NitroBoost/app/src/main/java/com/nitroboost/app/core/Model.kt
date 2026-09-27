package com.nitroboost.app.core

/**
 * Functional modules a boost profile can enable.
 * Keys match the reference profile JSON format so profiles stay compatible.
 */
enum class Module(val key: String) {
    CPU("cpu"),
    RAM("ram"),
    DISPLAY("display"),
    DND("dnd"),
    POWER("power"),
    NETWORK("network"),
    TWEAKS("tweaks"),
    GPU("gpu"),
    THERMAL("thermal");

    companion object {
        fun fromKey(key: String): Module? = values().firstOrNull { it.key == key }
    }
}

/**
 * One reversible system change. The journal replays these in reverse order
 * on restore — this is what guarantees "no conflicts": every change we make
 * is recorded with its original value before it is made.
 */
data class JournalEntry(
    val taskId: String,
    val kind: Kind,
    val key: String,
    val oldValue: String?,
    val newValue: String?,
    val revertCmd: String? = null,
    val ts: Long = System.currentTimeMillis()
) {
    enum class Kind {
        SYS_SETTING,      // Settings.System
        SECURE_SETTING,   // Settings.Secure
        GLOBAL_SETTING,   // Settings.Global
        SYSFS,            // /sys file
        DND,              // notification interruption filter
        THERMAL_OVERRIDE, // cmd thermalservice override-status
        CMD               // generic shell command with explicit revertCmd
    }
}

sealed class TaskStatus {
    object Applied : TaskStatus()
    object NoChange : TaskStatus()
    object Skipped : TaskStatus()
    data class Failed(val reason: String) : TaskStatus()

    val success: Boolean
        get() = this is Applied || this is NoChange
}

data class TaskResult(
    val taskId: String,
    val status: TaskStatus,
    val detail: String = "",
    val entries: List<JournalEntry> = emptyList()
)

/** UI-facing description of one task. */
data class TaskState(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val descAr: String,
    val descEn: String,
    val module: Module,
    val applied: Boolean,
    val supported: Boolean,
    val requiresPrivilege: Boolean,
    /**
     * Needs Shizuku/root but the hardware support cannot be verified yet
     * (no privileged shell to probe with). UI shows "بانتظار شيزوكو"
     * instead of "غير مدعوم" — once a privileged channel appears the real
     * support state is re-evaluated.
     */
    val pending: Boolean = false
)

/** A single reversible optimization. */
interface BoostTask {
    val id: String
    val titleAr: String
    val titleEn: String
    val descAr: String
    val descEn: String
    val module: Module
    val requiresPrivilege: Boolean

    /**
     * Boost level required (v1.5). 1 = basic (no privileges),
     * 2 = standard performance, 3 = aggressive. The engine applies a task
     * only when the user-selected level is >= this one — the intensity
     * valve that makes "max" opt-in instead of default.
     */
    val boostLevel: Int get() = 2

    /** False when the device/permission situation makes the task impossible. */
    fun isSupported(ctx: BoostContext): Boolean

    /** True when the target state is already active (idempotency check). */
    fun isApplied(ctx: BoostContext): Boolean

    /**
     * Apply the optimization. Must be idempotent: if the state is already
     * correct return [TaskStatus.NoChange] and no journal entries.
     * Only record journal entries for changes that actually happened.
     */
    fun apply(ctx: BoostContext): TaskResult
}
