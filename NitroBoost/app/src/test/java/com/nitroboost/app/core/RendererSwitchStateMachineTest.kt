package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSwitchStateMachineTest {
    @Test
    fun `successful transaction completes only after verification`() {
        val machine = RendererSwitchStateMachine("skiavk", "opengl")
        machine.request()
        machine.applying()
        machine.verifying()
        machine.verified()
        machine.complete()
        assertEquals(RendererSwitchStateMachine.Phase.COMPLETE, machine.phase)
        assertEquals(null, machine.failure)
    }

    @Test
    fun `failure is explicit and terminal`() {
        val machine = RendererSwitchStateMachine("skiavk", "opengl")
        machine.request()
        machine.applying()
        machine.fail("property write failed")
        assertEquals(RendererSwitchStateMachine.Phase.FAILED, machine.phase)
        assertEquals("property write failed", machine.failure)
    }

    @Test
    fun `cannot complete without readback verification`() {
        val machine = RendererSwitchStateMachine("skiavk", "opengl")
        machine.request()
        machine.applying()
        assertThrows(IllegalStateException::class.java) { machine.complete() }
        assertTrue(machine.phase != RendererSwitchStateMachine.Phase.COMPLETE)
    }
}
