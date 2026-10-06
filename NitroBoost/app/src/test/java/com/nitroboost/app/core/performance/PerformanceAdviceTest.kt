package com.nitroboost.app.core.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceAdviceTest {
    @Test
    fun `each performance state maps to cautious actionable guidance`() {
        val expected = mapOf(
            PerformanceState.CPU_BOUND to PerformanceAdviceCode.REVIEW_CPU_HEAVY_GAME_SETTINGS,
            PerformanceState.GPU_BOUND to PerformanceAdviceCode.REVIEW_IN_GAME_GRAPHICS,
            PerformanceState.MEMORY_BOUND to PerformanceAdviceCode.REVIEW_UNUSED_APPS,
            PerformanceState.THERMAL_BOUND to PerformanceAdviceCode.COOL_DEVICE_AND_REDUCE_BOOST,
            PerformanceState.NETWORK_BOUND to PerformanceAdviceCode.VERIFY_GAME_NETWORK_PATH,
            PerformanceState.DISPLAY_BOUND to PerformanceAdviceCode.MATCH_TARGET_TO_REFRESH,
            PerformanceState.MIXED_BOUND to PerformanceAdviceCode.CHANGE_ONE_SETTING_AT_A_TIME,
            PerformanceState.HEALTHY to PerformanceAdviceCode.KEEP_CURRENT_SETTINGS,
            PerformanceState.UNKNOWN to PerformanceAdviceCode.WAIT_FOR_RELIABLE_DATA
        )

        assertEquals(PerformanceState.values().toSet(), expected.keys)
        expected.forEach { (state, advice) ->
            assertEquals(state.name, advice, PerformanceAdviceResolver.resolve(state))
        }
    }
}
