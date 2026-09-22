package com.nitroboost.app.core

/**
 * Boost score 0..100.
 *  - 60 pts: fraction of applicable tasks actually applied
 *  - 15 pts: thermal headroom
 *  - 15 pts: free RAM
 *  - 10 pts: free storage
 */
object ScoreEngine {

    data class Inputs(
        val appliedTasks: Int,
        val applicableTasks: Int,
        val thermalStatus: Int, // 0 nominal .. 4+ critical
        val ramFreePct: Double, // 0..1
        val storageFreePct: Double // 0..1
    ) {
        companion object {
            val EMPTY = Inputs(0, 0, 0, 0.0, 0.0)
        }
    }

    fun compute(i: Inputs): Int {
        // No applicable work -> nothing boosted -> zero, period.
        if (i.applicableTasks <= 0) return 0
        val base = (i.appliedTasks * 60.0 / i.applicableTasks).toInt()
        val thermal = when {
            i.thermalStatus <= 1 -> 15
            i.thermalStatus == 2 -> 8
            else -> 0
        }
        val ram = (i.ramFreePct.coerceIn(0.0, 1.0) * 15.0).toInt()
        val storage = (i.storageFreePct.coerceIn(0.0, 1.0) * 10.0).toInt()
        return (base + thermal + ram + storage).coerceIn(0, 100)
    }
}
