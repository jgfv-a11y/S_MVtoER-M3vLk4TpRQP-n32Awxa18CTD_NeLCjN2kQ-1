package com.nitroboost.app.core.performance

/**
 * Product-safe next-step guidance for a diagnostic state.
 *
 * Advice is deliberately descriptive: it never initiates a system mutation,
 * and consumers must not treat it as a boost command.
 */
enum class PerformanceAdviceCode {
    REVIEW_CPU_HEAVY_GAME_SETTINGS,
    REVIEW_IN_GAME_GRAPHICS,
    REVIEW_UNUSED_APPS,
    COOL_DEVICE_AND_REDUCE_BOOST,
    VERIFY_GAME_NETWORK_PATH,
    MATCH_TARGET_TO_REFRESH,
    CHANGE_ONE_SETTING_AT_A_TIME,
    KEEP_CURRENT_SETTINGS,
    WAIT_FOR_RELIABLE_DATA
}

object PerformanceAdviceResolver {
    fun resolve(state: PerformanceState): PerformanceAdviceCode = when (state) {
        PerformanceState.CPU_BOUND -> PerformanceAdviceCode.REVIEW_CPU_HEAVY_GAME_SETTINGS
        PerformanceState.GPU_BOUND -> PerformanceAdviceCode.REVIEW_IN_GAME_GRAPHICS
        PerformanceState.MEMORY_BOUND -> PerformanceAdviceCode.REVIEW_UNUSED_APPS
        PerformanceState.THERMAL_BOUND -> PerformanceAdviceCode.COOL_DEVICE_AND_REDUCE_BOOST
        PerformanceState.NETWORK_BOUND -> PerformanceAdviceCode.VERIFY_GAME_NETWORK_PATH
        PerformanceState.DISPLAY_BOUND -> PerformanceAdviceCode.MATCH_TARGET_TO_REFRESH
        PerformanceState.MIXED_BOUND -> PerformanceAdviceCode.CHANGE_ONE_SETTING_AT_A_TIME
        PerformanceState.HEALTHY -> PerformanceAdviceCode.KEEP_CURRENT_SETTINGS
        PerformanceState.UNKNOWN -> PerformanceAdviceCode.WAIT_FOR_RELIABLE_DATA
    }
}
