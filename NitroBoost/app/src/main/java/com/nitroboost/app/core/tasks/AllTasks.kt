package com.nitroboost.app.core.tasks

import com.nitroboost.app.core.BoostTask

/**
 * The full task roster. Order matters: cheap & non-privileged tasks first,
 * privileged ones after, so a boost always has visible progress even when
 * Shizuku is missing.
 */
object AllTasks {

    val tasks: List<BoostTask> = listOf(
        DndTask(),
        AnimationsTask(),
        GameModeTask(),
        GovernorTask(),
        CpuFloorTask(),
        GpuTask(),
        GameApiTask(),
        DisplayTask(),
        PowerTask(),
        PowerSaveWhitelistTask(),
        RamTrimTask(),
        RamKillTask(),
        NetworkTask(),
        WaltSchedulerTask(),
        TouchBoostTask(),
        // ThermalGuard is always active. Never expose
        // cmd thermalservice override-status: it disables the OS governor
        // and cannot set a safe temperature threshold.
        // v1.5 Turbo Kit
        PeakBrightnessTask(),
        IoSchedulerTask(),
        CpuOnlineTask(),
        DeviceIdleTask(),
        GamePerfModeTask()
    )

    val byId: Map<String, BoostTask> = tasks.associateBy { it.id }
}
