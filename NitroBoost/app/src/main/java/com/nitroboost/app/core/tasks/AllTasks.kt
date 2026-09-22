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
        ThermalOverrideTask()
    )

    val byId: Map<String, BoostTask> = tasks.associateBy { it.id }
}
