package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.tasks.AllTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.concurrent.Volatile

class AdaptiveLoopTest {

    private class StubSampler(
        @Volatile var fpsVal: Int? = null,
        @Volatile var priv: Boolean = true
    ) : AdaptiveSampler {
        override fun fps(): Int? = fpsVal
        override fun metrics(): FrameMetrics? =
            FrameMetrics(fpsVal, 60, 50, 50, 30, 0, 0, 35.0)
        override fun privileged(): Boolean = priv
    }

    private fun tempFile(tag: String): File =
        File.createTempFile("nitro_$tag", ".json").also { it.deleteOnExit() }

    private fun makeLoop(
        sampler: StubSampler,
        thermal: () -> Int = { 0 },
        modules: List<Module> = listOf(Module.CPU, Module.GPU, Module.TWEAKS, Module.NETWORK)
    ): Pair<AdaptiveLoop, () -> BoostContext> {
        val ex = FakeExecutor()
        ex.privileged = true
        val ledger = DecisionLedger(tempFile("ledger"), 40).also { it.load() }
        val profile = testProfile(*modules.toTypedArray())
        val ctxProvider: () -> BoostContext = {
            BoostContext(profile, ex, Journal(tempFile("journal")))
        }
        val loop = AdaptiveLoop(
            engine = BoostEngine(AllTasks.tasks),
            context = ctxProvider,
            ledger = ledger,
            sampler = sampler,
            cfg = TrialConfig(),
            effectiveThermal = thermal
        )
        return loop to ctxProvider
    }

    @Test fun `pauses without privilege`() {
        val (loop, _) = makeLoop(StubSampler(priv = false))
        assertEquals("needs-shizuku", loop.pauseReason())
    }

    @Test fun `pauses at moderate effective thermal`() {
        val (loop, _) = makeLoop(StubSampler(), thermal = { 2 })
        assertEquals("thermal:2", loop.pauseReason())
    }

    @Test fun `runs when privileged and cool`() {
        val (loop, _) = makeLoop(StubSampler(), thermal = { 1 })
        assertNull(loop.pauseReason())
    }

    @Test fun `privilege outranks thermal in pause reasons`() {
        val (loop, _) = makeLoop(StubSampler(priv = false), thermal = { 4 })
        assertEquals("needs-shizuku", loop.pauseReason())
    }

    @Test fun `candidate follows task order and profile gating`() {
        val (loop, ctx) = makeLoop(StubSampler())
        // First trial candidate in AllTasks order: dnd (DND module) and
        // animations (no privilege needed) are skipped, game_mode is first.
        assertEquals("game_mode", loop.nextCandidate(ctx())?.id)
    }

    @Test fun `resolved candidates are never retried`() {
        val (loop, ctx) = makeLoop(StubSampler())
        // mark game_mode resolved as kept
        val deltas = List(10) { 5.0 }
        loop.ledger.record("game_mode", "Game Mode", deltas,
            AdaptivePolicy.assess(deltas, TrialConfig()), 1L, TrialConfig())
        val next = loop.nextCandidate(ctx())
        assertNotNull(next)
        assertEquals("cpu_governor", next.id)
    }

    @Test fun `no candidates when profile has no trial modules`() {
        val (loop, ctx) = makeLoop(StubSampler(), modules = listOf(Module.DND))
        assertEquals(null, loop.nextCandidate(ctx()))
    }

    @Test fun `stop is idempotent and resets phase`() {
        val (loop, _) = makeLoop(StubSampler())
        loop.stop()
        loop.stop()
        assertEquals("idle", loop.phase)
        assertEquals(false, loop.isRunning)
    }
}
