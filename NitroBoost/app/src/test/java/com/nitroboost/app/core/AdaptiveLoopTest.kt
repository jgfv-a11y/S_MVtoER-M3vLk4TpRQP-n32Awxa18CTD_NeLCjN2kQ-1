package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptiveSample
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.SweepArm
import com.nitroboost.app.core.adaptive.TrialContext
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.adaptive.LedgerEntry
import com.nitroboost.app.core.adaptive.pickBestArm
import com.nitroboost.app.core.tasks.AllTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile

class AdaptiveLoopTest {

    private class StubSampler(
        @Volatile var fpsVal: Int? = null,
        @Volatile var priv: Boolean = true
    ) : AdaptiveSampler {
        override fun poll(): AdaptiveSample? = AdaptiveSample(fpsVal, 0)
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
        // A context-free v1 ledger record must not be reused by the v2 cache.
        val profileKey = "cpu,gpu,network,tweaks:0:0:4"
        val context = TrialContext(
            "unknown-device", "unknown-android", "com.test.game", 3, 0,
            FakeExecutor::class.java.name, profileKey
        )
        val now = System.currentTimeMillis()
        loop.ledger.entries["game_mode"] = LedgerEntry(
            taskId = "game_mode", taskTitle = "Game Mode", decision = com.nitroboost.app.core.adaptive.Decision.KEEP,
            deltas = List(10) { 5.0 }, meanDelta = 5.0, ciLow = 5.0, ciHigh = 5.0,
            pairs = 10, sessions = 1, evaluatedAt = now, context = context
        )
        val next = loop.nextCandidate(ctx())
        assertEquals("cpu_governor", next!!.id)
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

    @Test fun `transient step exception is retried instead of stopping the adaptive loop`() {
        val executor = FakeExecutor().also { it.privileged = true }
        val profile = testProfile(Module.CPU, Module.GPU, Module.TWEAKS, Module.NETWORK)
        val ledger = DecisionLedger(tempFile("retry-ledger"), 40).also { it.load() }
        val contextCalls = AtomicInteger()
        val recovered = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        val context = {
            if (contextCalls.incrementAndGet() == 1) {
                throw IllegalStateException("temporary context failure")
            }
            recovered.countDown()
            BoostContext(profile, executor, Journal(tempFile("retry-journal")))
        }
        val loop = AdaptiveLoop(
            engine = BoostEngine(AllTasks.tasks),
            context = context,
            ledger = ledger,
            sampler = StubSampler(),
            cfg = TrialConfig(windowMs = 0L, settleMs = 0L),
            effectiveThermal = { 0 },
            log = { line -> logs.add(line) },
            wait = { Thread.yield() }
        )

        loop.start()
        try {
            assertTrue("the loop should retry the failed context", recovered.await(3, TimeUnit.SECONDS))
            assertTrue(logs.any { it.contains("adaptive step failed; retrying") })
            assertTrue(loop.isRunning)
        } finally {
            loop.stopAndJoinBlocking()
        }
    }

    @Test fun `sweep levels are legal AOSP ratios, mildest first`() {
        AdaptiveLoop.SWEEP_LEVELS.forEach { level ->
            val f = level.toFloat()
            assertTrue(f in 0.3f..0.9f)
            // AOSP accepts 0.05 steps
            assertTrue((f * 100f % 5f) == 0f)
        }
        assertTrue(AdaptiveLoop.SWEEP_LEVELS.first().toFloat() >
            AdaptiveLoop.SWEEP_LEVELS.last().toFloat())
    }

    @Test fun `pickBestArm chooses the highest mean delta`() {
        val best = pickBestArm(
            listOf(
                SweepArm("0.9", listOf(1.0, 1.0, 1.0)),
                SweepArm("0.8", listOf(3.0, 3.0, 3.0)),
                SweepArm("0.7", listOf(0.5, 0.5))
            )
        )
        assertEquals("0.8", best!!.level)
    }

    @Test fun `pickBestArm handles empty and negative arms`() {
        assertEquals(null, pickBestArm(emptyList()))
        val best = pickBestArm(
            listOf(
                SweepArm("0.9", emptyList()),
                SweepArm("0.8", listOf(-1.0, -0.5))
            )
        )
        assertEquals("0.8", best!!.level) // -0.75 beats NEGATIVE_INFINITY
        assertTrue(best.mean < 0)
    }
}
