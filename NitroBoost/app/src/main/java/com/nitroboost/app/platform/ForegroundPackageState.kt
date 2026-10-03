package com.nitroboost.app.platform

/** Pure reducer for foreground-package state reconstructed from UsageEvents. */
internal class ForegroundPackageState(private val ignoredPackages: Set<String> = emptySet()) {
    private data class ActivityKey(val packageName: String, val className: String)
    private data class ActiveActivity(val packageName: String, val order: Long)

    private val resumedActivities = LinkedHashMap<ActivityKey, ActiveActivity>()
    private val legacyForeground = LinkedHashMap<String, Long>()
    private var order = 0L

    fun activityResumed(packageName: String?, className: String?) {
        if (packageName.isNullOrBlank()) return
        order += 1L
        val key = ActivityKey(packageName, className.orEmpty())
        resumedActivities[key] = ActiveActivity(packageName, order)
        legacyForeground.remove(packageName)
    }

    fun activityPaused(packageName: String?, className: String?) {
        if (packageName.isNullOrBlank()) return
        if (className.isNullOrBlank()) {
            resumedActivities.keys.removeAll { it.packageName == packageName }
        } else {
            resumedActivities.remove(ActivityKey(packageName, className))
        }
    }

    fun packageForeground(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        order += 1L
        legacyForeground[packageName] = order
    }

    fun packageBackground(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        legacyForeground.remove(packageName)
        resumedActivities.keys.removeAll { it.packageName == packageName }
    }

    fun foregroundPackages(): Set<String> = buildSet {
        resumedActivities.values.forEach { add(it.packageName) }
        legacyForeground.keys.forEach { add(it) }
        removeAll(ignoredPackages)
    }

    fun currentPackage(): String? = sequence {
        resumedActivities.values.forEach { yield(it.packageName to it.order) }
        legacyForeground.forEach { (pkg, resumedAt) -> yield(pkg to resumedAt) }
    }
        .filter { (pkg, _) -> pkg !in ignoredPackages }
        .maxByOrNull { it.second }
        ?.first
}
