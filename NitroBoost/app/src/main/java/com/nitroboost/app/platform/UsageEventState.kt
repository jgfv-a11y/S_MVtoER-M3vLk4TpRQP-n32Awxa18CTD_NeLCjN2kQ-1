package com.nitroboost.app.platform

/**
 * Pure state reducer for a package's UsageEvents activity lifecycle. Keeping
 * this separate from Android's UsageStatsManager makes the event ordering
 * behavior unit-testable without a device.
 */
internal class UsageEventState {
    private val resumedActivities = LinkedHashSet<String>()

    var isForeground: Boolean? = null
        private set

    fun packageForeground() {
        resumedActivities.clear()
        isForeground = true
    }

    fun packageBackground() {
        resumedActivities.clear()
        isForeground = false
    }

    fun activityResumed(className: String?) {
        if (!className.isNullOrBlank()) resumedActivities += className
        isForeground = true
    }

    fun activityPaused(className: String?) {
        if (!className.isNullOrBlank()) resumedActivities -= className
        isForeground = resumedActivities.isNotEmpty()
    }
}
