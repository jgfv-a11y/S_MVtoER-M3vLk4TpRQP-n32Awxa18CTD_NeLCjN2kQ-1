package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.AdaptiveSample
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.FrameTimeMetrics
import com.nitroboost.app.core.adaptive.PairObservation
import com.nitroboost.app.core.adaptive.ScoreComponents
import com.nitroboost.app.core.adaptive.SessionQualityGate
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.adaptive.TrialContext
import com.nitroboost.app.core.adaptive.TrialOutcome
import com.nitroboost.app.core.adaptive.Variant
import com.nitroboost.app.core.adaptive.WindowMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdaptiveEngineV2Test {

    private val gamePackage = "com.test.game"
    private val baseConfig = TrialConfig(minPairs = 8, maxPairs = 40)

    private fun metrics(
        fps: Double = 60.0,
        lowFps: Double = 45.0,
        temp: Double? = 37.0,
        slope: Double? = 0.1,
        tier: Int = 0,
        ram: Double? = 50.0,
        frameTime: FrameTimeMetrics? = null,
        sampleCount: Int = 12,
        fpsSampleCount: Int = 8,
        thermalSamples: Int = 12,
        maxAge: Long = 0,
        maxGap: Long = 1_000,
        processEpoch: Long = 0,
        complete: Boolean = true,
        requestedMs: Long = 12_000
    ) = WindowMetrics(
        fpsMean = fps,
        lowFps = lowFps,
        fpsSampleCount = fpsSampleCount,
        sampleCount = sampleCount,
        thermalSampleCount = thermalSamples,
        thermalTier = tier,
        minThermalTier = tier,
        maxThermalTier = tier,
        tempMeanC = temp,
        tempMaxC = temp,
        thermalSlopeCPerMin = slope,
        ramMeanPct = ram,
        energyMeanMah = null,
        frameTime = frameTime,
        firstTimestampMs = 1_000,
        lastTimestampMs = 1_000 + requestedMs,
        windowComplete = complete,
        requestedWindowMs = requestedMs,
        maxSampleGapMs = maxGap,
        maxMonitorAgeMs = maxAge,
        gamePackage = gamePackage,
        processEpoch = processEpoch,
        targetFps = 60
    )

    private fun score(value: Double) = ScoreComponents(
        averageFpsGain = value,
        lowFpsGain = value,
        frameStabilityGain = null,
        hitchGain = null,
        memoryGain = null,
        energyGain = null,
        thermalTemperatureCost = 0.0,
        thermalSlopeCost = 0.0,
        thermalTierCost = 0.0,
        thermalHeadroomFactor = 1.0,
        score = value
    )

    private fun observation(
        variant: String,
        session: String,
        index: Int,
        score: Double,
        baseline: WindowMetrics = metrics(),
        candidate: WindowMetrics = metrics(fps = 63.0, lowFps = 48.0)
    ) = PairObservation(
        sampleId = "$session|$variant|$index",
        sessionId = session,
        observedAtMs = 10_000L + index,
        baseline = baseline,
        candidate = candidate,
        score = score(score)
    )

    private fun context(
        game: String = gamePackage,
        tier: Int = 0,
        android: String = "35:15",
        device: String = "soc-test",
        boost: Int = 3,
        thermalSignature: String = ""
    ) = TrialContext(
        deviceKey = device,
        androidVersion = android,
        gamePackage = game,
        boostLevel = boost,
        thermalTier = tier,
        capabilityKey = "caps-1",
        profileKey = "cpu,gpu",
        thermalSignature = thermalSignature
    )

    @Test fun `baseline candidate score is normalized and leaves unavailable frame data absent`() {
        val base = metrics(fps = 60.0, lowFps = 42.0, frameTime = null)
        val candidate = metrics(fps = 66.0, lowFps = 50.0, frameTime = null)
        val result = AdaptivePolicy.scoreComparison(base, candidate)
        assertTrue(result.averageFpsGain > 0.0)
        assertTrue(result.lowFpsGain > result.averageFpsGain)
        assertEquals(null, result.frameStabilityGain)
        assertEquals(null, result.hitchGain)
        assertTrue(result.score in 0.0..1.0)
    }

    @Test fun `actual frame time and hitch samples contribute only when available`() {
        val baselineFrames = FrameTimeMetrics(100, 30.0, 40.0, 60.0, 100.0, 10, 0.10, 33.3)
        val candidateFrames = FrameTimeMetrics(100, 25.0, 30.0, 40.0, 20.0, 2, 0.02, 33.3)
        val result = AdaptivePolicy.scoreComparison(
            metrics(frameTime = baselineFrames),
            metrics(frameTime = candidateFrames)
        )
        assertTrue(result.frameStabilityGain!! > 0.0)
        assertTrue(result.hitchGain!! > 0.0)
        assertEquals(null, AdaptivePolicy.scoreComparison(metrics(), metrics()).frameStabilityGain)
    }

    @Test fun `significant composite improvement is kept`() {
        val result = AdaptivePolicy.assessScores(List(8) { 0.10 }, baseConfig)
        assertEquals(Decision.KEEP, result.decision)
        assertTrue(result.ciLow!! > baseConfig.minEffectScore)
        assertEquals(0.95, result.confidenceLevel!!, 1e-9)
    }

    @Test fun `insignificant positive effect is not kept`() {
        val result = AdaptivePolicy.assessScores(List(8) { 0.01 }, baseConfig)
        assertEquals(Decision.MORE_DATA, result.decision)
        assertTrue(result.ciLow!! < baseConfig.minEffectScore)
    }

    @Test fun `uncertain small sample explicitly requests more data`() {
        val result = AdaptivePolicy.assessScores(List(3) { 0.5 }, baseConfig)
        assertEquals(Decision.MORE_DATA, result.decision)
        assertEquals(3, result.pairs)
    }

    @Test fun `temperature rise offsets an FPS-only improvement`() {
        val base = metrics(fps = 60.0, lowFps = 42.0, temp = 37.0, slope = 0.1)
        val hot = metrics(fps = 68.0, lowFps = 52.0, temp = 43.0, slope = 0.1)
        val result = AdaptivePolicy.scoreComparison(base, hot)
        assertTrue(result.thermalTemperatureCost!! > 0.0)
        assertTrue("thermal risk must offset raw FPS gain", result.score < 0.0)
    }

    @Test fun `rapid thermal slope penalizes an FPS gain`() {
        val base = metrics(fps = 60.0, lowFps = 42.0, temp = 37.0, slope = 0.0)
        val rising = metrics(fps = 66.0, lowFps = 48.0, temp = 37.0, slope = 2.0)
        val result = AdaptivePolicy.scoreComparison(base, rising)
        assertTrue(result.thermalSlopeCost!! > 0.8)
        assertTrue(result.score < 0.0)
    }

    @Test fun `thermal tier change invalidates the comparison`() {
        val base = metrics(tier = 0)
        val hot = metrics(tier = 1)
        val result = SessionQualityGate.compare(base, hot, gamePackage, baseConfig)
        assertFalse(result.accepted)
        assertTrue(result.reason.contains("thermal state changed"))
    }

    @Test fun `process epoch reset invalidates otherwise fresh paired windows`() {
        val baseline = metrics(processEpoch = 0)
        val candidate = metrics(processEpoch = 1)
        val result = SessionQualityGate.compare(baseline, candidate, gamePackage, baseConfig)
        assertFalse(result.accepted)
        assertTrue(result.reason.contains("process changed"))
    }

    @Test fun `high score variance remains MORE_DATA`() {
        val scores = listOf(-0.4, 0.4, -0.35, 0.45, -0.3, 0.5, -0.25, 0.55)
        val result = AdaptivePolicy.assessScores(
            scores,
            baseConfig.copy(minPairs = 8, maxScoreStdDev = 0.10)
        )
        assertEquals(Decision.MORE_DATA, result.decision)
        assertTrue(result.reason.contains("variance"))
    }

    @Test fun `window with insufficient monitor and FPS samples is rejected`() {
        val poor = metrics(sampleCount = 3, fpsSampleCount = 2, thermalSamples = 2)
        val result = SessionQualityGate.validateWindow(poor, gamePackage, baseConfig)
        assertFalse(result.accepted)
    }

    @Test fun `stale monitor cannot teach the ledger`() {
        val stale = metrics(maxAge = 5_000)
        val result = SessionQualityGate.validateWindow(stale, gamePackage, baseConfig)
        assertFalse(result.accepted)
        assertEquals("monitor stale", result.reason)
    }

    @Test fun `cancelled candidate restores the task in NonCancellable cleanup`() = runBlocking {
        val f = FakeExecutor().apply { sysfs[PATH] = "0" }
        val task = MutationTask()
        val fixture = fixture(task, f, wait = { millis ->
            if (f.readSys(PATH) == "1") throw CancellationException("test cancellation")
            fixtureClock[0] += millis
        })
        // The closure above refers to this deterministic clock by object identity.
        try {
            fixture.loop.runTrial(task, fixture.ctx)
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals("0", f.readSys(PATH))
        assertTrue(fixture.ctx.journal.isEmpty())
        assertEquals(Decision.MORE_DATA, fixture.ledger.decisionFor(task.id))
    }

    @Test fun `exception during candidate restores any already journaled partial change`() = runBlocking {
        val f = FakeExecutor().apply { sysfs[PATH] = "0" }
        val task = MutationTask(throwAfterSelfJournal = true)
        val fixture = fixture(task, f)
        fixture.loop.runTrial(task, fixture.ctx)
        assertEquals("0", f.readSys(PATH))
        assertTrue(fixture.ctx.journal.isEmpty())
        assertEquals(Decision.MORE_DATA, fixture.ledger.decisionFor(task.id))
    }

    @Test fun `restore failure retains journal and refuses a final verdict`() = runBlocking {
        val base = FakeExecutor().apply { sysfs[PATH] = "0" }
        val failingRestore = object : SystemExecutor by base {
            override fun writeSys(path: String, value: String): Boolean =
                if (path == PATH && value == "0") false else base.writeSys(path, value)
        }
        val task = MutationTask()
        val fixture = fixture(task, failingRestore)
        fixture.loop.runTrial(task, fixture.ctx)
        assertEquals("1", base.readSys(PATH))
        assertFalse(fixture.ctx.journal.isEmpty())
        assertEquals(Decision.MORE_DATA, fixture.ledger.decisionFor(task.id))
    }

    @Test fun `duplicate task journal entries preserve the original state once`() {
        val journal = Journal(tempFile("dedupe"))
        journal.add(listOf(JournalEntry("task", JournalEntry.Kind.SYSFS, PATH, "0", "1")))
        journal.add(listOf(JournalEntry("task", JournalEntry.Kind.SYSFS, PATH, "1", "2")))
        assertEquals(1, journal.entries.size)
        assertEquals("0", journal.entries.single().oldValue)
    }

    @Test fun `variant persistence retains each pair and the local context`() {
        val file = tempFile("v2ledger")
        val before = DecisionLedger(file)
        val currentContext = context()
        val samples = List(8) { observation("a", "session-a", it, 0.1) }
        before.recordVariant(
            "task", "Task", "a", "variant=a", samples, 3,
            currentContext, 20_000L, baseConfig
        )
        val after = DecisionLedger(file).also { it.load() }
        val entry = after.entries["task"]!!
        val arm = entry.variants["a"]!!
        assertEquals(currentContext, entry.context)
        assertEquals(8, arm.observations.size)
        assertEquals(0.1, arm.observations.first().score.score, 1e-9)
        assertEquals(3, arm.comparisonCount)
        assertNotNull(arm.observations.first().baseline.tempMeanC)
    }

    @Test fun `cache context and expiry invalidate old decisions`() {
        val file = tempFile("context")
        val ledger = DecisionLedger(file)
        val now = System.currentTimeMillis()
        val current = context()
        ledger.entries["task"] = LedgerEntry(
            taskId = "task", taskTitle = "Task", decision = Decision.KEEP,
            deltas = emptyList(), meanDelta = 1.0, ciLow = 0.5, ciHigh = 1.5,
            pairs = 8, sessions = 1, evaluatedAt = now, context = current
        )
        assertTrue(ledger.isResolved("task", current, now, 60_000L))
        assertFalse(ledger.isResolved("task", context(game = "com.other.game"), now, 60_000L))
        assertFalse(ledger.isResolved("task", context(android = "36:16"), now, 60_000L))
        assertFalse(ledger.isResolved("task", context(tier = 1), now, 60_000L))
        assertFalse(ledger.isResolved("task", context(thermalSignature = "near-floor:rapid-rise"), now, 60_000L))
        assertFalse(ledger.isResolved("task", current, now + 60_001L, 60_000L))
    }

    @Test fun `every variant gets an independent corrected baseline comparison`() {
        val ledger = DecisionLedger(tempFile("variants"))
        val ctx = context()
        ledger.recordVariant(
            "sweep", "Sweep", "a", "A",
            List(8) { observation("a", "session-a", it, 0.10) },
            comparisonCount = 2, context = ctx, nowMs = 30_000L, cfg = baseConfig
        )
        // The task-level ledger is still unresolved before all arms finish.
        assertEquals(Decision.MORE_DATA, ledger.decisionFor("sweep"))
        ledger.recordVariant(
            "sweep", "Sweep", "b", "B",
            List(8) { observation("b", "session-b", it, 0.06) },
            comparisonCount = 2, context = ctx, nowMs = 31_000L, cfg = baseConfig
        )
        val entry = ledger.finalizeSweep("sweep", "Sweep", listOf("a", "b"), ctx, 32_000L, true)
        assertEquals(Decision.KEEP, entry.decision)
        assertEquals("A", entry.detail)
        assertEquals(8, entry.variants["a"]!!.observations.size)
        assertEquals(8, entry.variants["b"]!!.observations.size)
        assertTrue(entry.variants.values.all { it.comparisonCount == 2 })
    }

    @Test fun `Bonferroni intervals protect against selecting the best noisy arm`() {
        val noisyPositive = listOf(0.018, 0.058, 0.018, 0.058, 0.018, 0.058, 0.018, 0.058)
        val uncorrected = AdaptivePolicy.assessScores(noisyPositive, baseConfig, comparisons = 1)
        val corrected = AdaptivePolicy.assessScores(noisyPositive, baseConfig, comparisons = 3)
        assertEquals(Decision.KEEP, uncorrected.decision)
        assertEquals(Decision.MORE_DATA, corrected.decision)
        assertTrue(AdaptivePolicy.tCritical(7, 3) > AdaptivePolicy.tQuantile(7))
        assertEquals(3, corrected.comparisonCount)
    }

    @Test fun `ledger preserves a post-thermal assessment rather than recalculating KEEP`() {
        val ledger = DecisionLedger(tempFile("thermal-verdict"))
        val positive = List(8) { 2.0 }
        val guarded = TrialOutcome(
            decision = Decision.NEUTRAL,
            meanDelta = 2.0,
            ciLow = 2.0,
            ciHigh = 2.0,
            pairs = 8,
            reason = "thermal regression"
        )
        val final = ledger.record("thermal-task", "Task", positive, guarded, 40_000L, baseConfig)
        assertEquals(Decision.NEUTRAL, final.decision)
        assertEquals("thermal regression", final.reason)
    }

    @Test fun `ledger corruption recovers last known good backup`() {
        val file = tempFile("ledger-backup")
        val ledger = DecisionLedger(file)
        ledger.entries["first"] = simpleEntry("first", 1_000L)
        assertTrue(ledger.save())
        ledger.entries["second"] = simpleEntry("second", 2_000L)
        assertTrue(ledger.save())
        file.writeText("not valid json")

        val recovered = DecisionLedger(file).also { it.load() }
        assertTrue(recovered.entries.containsKey("first"))
        assertFalse(recovered.entries.containsKey("second"))
        assertTrue(File(file.parentFile, file.name + ".corrupt").exists())
    }

    private fun simpleEntry(id: String, at: Long) = LedgerEntry(
        taskId = id, taskTitle = id, decision = Decision.MORE_DATA,
        deltas = emptyList(), meanDelta = null, ciLow = null, ciHigh = null,
        pairs = 0, sessions = 0, evaluatedAt = at, context = context()
    )

    private class MutationTask(
        private val throwAfterSelfJournal: Boolean = false
    ) : BoostTask {
        override val id = "trial_task"
        override val titleAr = "اختبار"
        override val titleEn = "Trial task"
        override val descAr = ""
        override val descEn = ""
        override val module = Module.CPU
        override val requiresPrivilege = true
        override fun isSupported(ctx: BoostContext) = true
        override fun isApplied(ctx: BoostContext) = ctx.executor.readSys(PATH) == "1"

        override fun apply(ctx: BoostContext): TaskResult {
            val old = ctx.executor.readSys(PATH) ?: "0"
            if (old == "1") return TaskResult(id, TaskStatus.NoChange)
            if (!ctx.executor.writeSys(PATH, "1")) return TaskResult(id, TaskStatus.Skipped)
            val entry = JournalEntry(id, JournalEntry.Kind.SYSFS, PATH, old, "1")
            if (throwAfterSelfJournal) {
                // Simulates a partial task that records its reversible operation
                // before a later operation throws.
                ctx.journal.add(listOf(entry))
                throw IllegalStateException("after sysfs write")
            }
            return TaskResult(id, TaskStatus.Applied, entries = listOf(entry))
        }
    }

    private class LiveSampler(
        private val now: () -> Long,
        private val executor: SystemExecutor
    ) : AdaptiveSampler {
        override fun poll(): AdaptiveSample = AdaptiveSample(
            fps = if (executor.readSys(PATH) == "1") 66 else 60,
            thermal = 0,
            timestampMs = now(),
            tempC = 37.0,
            thermalSlopeCPerMin = 0.1,
            ramPct = 50,
            gamePackage = "com.test.game",
            processEpoch = 0,
            thermalValid = true,
            monitorAgeMs = 0,
            targetFps = 60
        )
        override fun fps(): Int? = 60
        override fun metrics(): FrameMetrics? = null
        override fun privileged(): Boolean = true
    }

    private data class Fixture(
        val loop: AdaptiveLoop,
        val ctx: BoostContext,
        val ledger: DecisionLedger
    )

    private val fixtureClock = longArrayOf(1_000L)

    private fun fixture(
        task: BoostTask,
        executor: SystemExecutor,
        wait: suspend (Long) -> Unit = { millis -> fixtureClock[0] += millis }
    ): Fixture {
        fixtureClock[0] = 1_000L
        val journal = Journal(tempFile("trial-journal"))
        val ctx = BoostContext(testProfile(Module.CPU), executor, journal)
        val ledger = DecisionLedger(tempFile("trial-ledger"))
        val cfg = TrialConfig(
            minPairs = 8,
            maxPairs = 40,
            windowMs = 12_000L,
            settleMs = 2_000L,
            sampleIntervalMs = 1_000L
        )
        val loop = AdaptiveLoop(
            engine = BoostEngine(listOf(task)),
            context = { ctx },
            ledger = ledger,
            sampler = LiveSampler({ fixtureClock[0] }, executor),
            cfg = cfg,
            effectiveThermal = { 0 },
            trialContextFactory = { c, tier ->
                TrialContext("test-device", "35", c.profile.packageName, 3, tier,
                    "test-capability", "cpu")
            },
            clockMs = { fixtureClock[0] },
            wallClockMs = { fixtureClock[0] + 50_000L },
            wait = wait,
            sessionIdFactory = { "jvm-session" }
        )
        return Fixture(loop, ctx, ledger)
    }

    companion object {
        private const val PATH = "/sys/test/adaptive_node"
    }
}
