package com.nitroboost.app.core

/**
 * Deterministic lifecycle for one renderer-property transaction. The transition
 * model is adapted from `RendererSwitchStateMachine.kt` in palincat/gama; see
 * THIRD_PARTY_NOTICES.md for the MIT notice. Shell execution remains outside
 * this class so the ordering can be tested on the JVM without an Android device.
 */
class RendererSwitchStateMachine(
    val target: String,
    val previous: String
) {
    enum class Phase { IDLE, REQUESTED, APPLYING, VERIFYING, VERIFIED, COMPLETE, FAILED }

    var phase: Phase = Phase.IDLE
        private set
    var failure: String? = null
        private set

    fun request() { move(Phase.IDLE, Phase.REQUESTED) }
    fun applying() { move(Phase.REQUESTED, Phase.APPLYING) }
    fun verifying() { move(Phase.APPLYING, Phase.VERIFYING) }
    fun verified() { move(Phase.VERIFYING, Phase.VERIFIED) }
    fun complete() { move(Phase.VERIFIED, Phase.COMPLETE) }

    fun fail(reason: String) {
        check(phase != Phase.COMPLETE) { "A completed transaction cannot fail" }
        phase = Phase.FAILED
        failure = reason
    }

    private fun move(from: Phase, to: Phase) {
        check(phase == from) { "Invalid renderer transaction transition: $phase -> $to" }
        phase = to
    }
}
