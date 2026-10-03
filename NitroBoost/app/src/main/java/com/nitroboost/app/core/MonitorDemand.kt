package com.nitroboost.app.core

/** Owners that require live device sampling. Monitoring stops when all release it. */
enum class MonitorClient { UI, SESSION, OVERLAY }

class MonitorDemand {
    private val clients = mutableSetOf<MonitorClient>()

    @Synchronized
    fun set(client: MonitorClient, enabled: Boolean) {
        if (enabled) clients += client else clients -= client
    }

    @Synchronized
    fun shouldRun(): Boolean = clients.isNotEmpty()

    @Synchronized
    fun clients(): Set<MonitorClient> = clients.toSet()
}
