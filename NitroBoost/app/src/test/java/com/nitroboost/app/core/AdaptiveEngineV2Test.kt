package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptivePolicy
import com.nitroboost.app.core.adaptive.AdaptiveSample
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.FrameTimeMetrics
import com.nitroboost.app.core.adaptive.LedgerEntry
import com.nitroboost.app.core.adaptive.ObjectiveWeightResolver
import com.nitroboost.app.core.adaptive.ObjectiveWeightProfile
import com.nitroboost.app.core.adaptive.ObjectiveWeights
import com.nitroboost.app.core.adaptive.PairOrder
import com.nitroboost.app.core.adaptive.PairObservation
import com.nitroboost.app.core.adaptive.ScoreComponents
import com.nitroboost.app.core.adaptive.SessionQualityGate
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.adaptive.TrialContext
import com.nitroboost.app.core.adaptive.TrialOutcome
import com.nitroboost.app.core.adaptive.Variant
import com.nitroboost.app.core.adaptive.WindowMetrics
import com.nitroboost.app.core.telemetry.FramePacingMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import java.io.File

class AdaptiveEngineV2Test {

    private val gamePackage = "com.test.game"
    private val baseConfig = TrialConfig(minPairs = 8, maxPairs = 40)

    private fun tempFile(tag: String): File =
        File.createTempFile("nitro_$tag", ".json").also { it.delete(); it.deleteOnExit() }

    private fun metrics(
        fps: Double = 60.0,
        lowFps: Double = 45.0,
        temp: Double? = 37.0,
        slope: Double? = 0.1,
        tier: Int = 0,
        ram: Double? = 50.0,
        frameTime: FrameTimeMetrics? = null,
        fpsCv: Double? = 0.0,
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
        targetFps = 60,
        fpsCoefficientOfVariation = fpsCv
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
        score = score(score),
        blockIndex = index,
        order = if (index % 2 == 0) PairOrder.BASELINE_THEN_CANDIDATE
            else PairOrder.CANDIDATE_THEN_BASELINE,
        temporalDistanceMs = 14_000L,
        blockDurationMs = 25_000L
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

    @Test fun `measured frame stability and hitch improvements affect score but missing data stays omitted`() {
        val baselineFrames = FrameTimeMetrics(100, 30.0, 40.0, 60.0, 100.0, 10, 0.10, 33.3)
        val improvedFrames = FrameTimeMetrics(100, 25.0, 30.0, 40.0, 20.0, 2, 0.02, 33.3)
        val fpsOnly = AdaptivePolicy.scoreComparison(
            metrics(fps = 60.0, lowFps = 45.0),
            metrics(fps = 60.0, lowFps = 45.0)
        )
        val measured = AdaptivePolicy.scoreComparison(
            metrics(fps = 60.0, lowFps = 45.0, frameTime = baselineFrames),
            metrics(fps = 60.0, lowFps = 45.0, frameTime = improvedFrames)
        )
        assertEquals(null, fpsOnly.frameStabilityGain)
        assertEquals(null, fpsOnly.hitchGain)
        assertTrue(measured.frameStabilityGain!! > 0.0)
        assertTrue(measured.hitchGain!! > 0.0)
        assertTrue("real hitch/stability gains must influence the composite", measured.score > fpsOnly.score)
    }

    @Test fun `objective weights normalize and invalid values fall back safely`() {
        val normalized = ObjectiveWeightResolver.normalize(ObjectiveWeights(0.2, 0.1, 0.1, 0.1))!!
        assertEquals(0.4, normalized.performance, 1e-12)
        assertEquals(0.2, normalized.temperature, 1e-12)
        assertEquals(1.0, normalized.performance + normalized.temperature +
            normalized.thermalSlope + normalized.thermalTier, 1e-12)

        val invalid = listOf(
            ObjectiveWeights(-0.1, 0.5, 0.3, 0.3),
            ObjectiveWeights(Double.NaN, 0.0, 0.0, 0.0),
            ObjectiveWeights(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0),
            ObjectiveWeights(0.0, 0.0, 0.0, 0.0),
            ObjectiveWeights(1.01, 0.0, 0.0, 0.0)
        )
        invalid.forEach { values ->
            assertEquals(null, ObjectiveWeightResolver.normalize(values))
            assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS,
                ObjectiveWeightResolver.normalizeOrDefault(values))
        }
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS,
            AdaptivePolicy.scoreComparison(metrics(), metrics(), invalid.first()).objectiveWeights)
        val unavailableObjective = ObjectiveWeights(0.0, 1.0, 0.0, 0.0)
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS,
            AdaptivePolicy.scoreComparison(
                metrics(temp = null, slope = null), metrics(temp = null, slope = null), unavailableObjective
            ).objectiveWeights
        )
    }

    @Test fun `context objective policy is declared and requires enough valid support`() {
        val default = ObjectiveWeightResolver.resolve(context(tier = 1), priorValidPairs = 7)
        val thermal = ObjectiveWeightResolver.resolve(context(tier = 1), priorValidPairs = 8)
        val cool = ObjectiveWeightResolver.resolve(context(tier = 0), priorValidPairs = 80)
        val invalidConfig = ObjectiveWeightResolver.resolve(
            context(tier = 1), priorValidPairs = 80, minEvidencePairs = 0
        )
        assertEquals("balanced-v1", default.profileId)
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS, default.weights)
        assertEquals("thermal-cautious-v1", thermal.profileId)
        assertEquals(0.50, thermal.weights.performance, 1e-12)
        assertEquals(0.20, thermal.weights.temperature, 1e-12)
        assertEquals(0.20, thermal.weights.thermalSlope, 1e-12)
        assertEquals(0.10, thermal.weights.thermalTier, 1e-12)
        assertEquals("balanced-v1", cool.profileId)
        assertEquals("balanced-v1", invalidConfig.profileId)

        val conflictingIds = ObjectiveWeightResolver.resolve(
            context(tier = 1), priorValidPairs = 80,
            thermalProfile = ObjectiveWeightProfile("balanced-v1", ObjectiveWeights(0.5, 0.2, 0.2, 0.1))
        )
        assertEquals("balanced-v1", conflictingIds.profileId)
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS, conflictingIds.weights)
    }

    @Test fun `loop switches only to its declared thermal policy after enough valid context-matched evidence`() {
        val task = MutationTask()
        val fixture = fixture(task, FakeExecutor().apply { sysfs[PATH] = "0" })
        val defaultContext = fixture.loop.trialContextForTask(task.id, fixture.ctx, 1)
        assertEquals(TrialContext.DEFAULT_OBJECTIVE_POLICY_KEY, defaultContext.objectivePolicyKey)

        fixture.ledger.recordVariant(
            task.id, task.titleEn, AdaptiveLoop.DEFAULT_VARIANT_ID, AdaptiveLoop.DEFAULT_VARIANT_ID,
            List(8) { observation(AdaptiveLoop.DEFAULT_VARIANT_ID, "prior-session", it, 0.05) },
            comparisonCount = 1, context = defaultContext, nowMs = 100_000L, cfg = baseConfig
        )

        val selected = fixture.loop.trialContextForTask(task.id, fixture.ctx, 1)
        assertEquals("thermal-cautious-v1", selected.objectivePolicyKey)
        assertFalse(defaultContext == selected)
        assertEquals(TrialContext.OBJECTIVE_POLICY_REVISION, selected.objectivePolicyRevision)
    }

    @Test fun `FPS variability must be present finite and below the configured gate`() {
        assertFalse(SessionQualityGate.validateWindow(metrics(fpsCv = null), gamePackage, baseConfig).accepted)
        assertFalse(SessionQualityGate.validateWindow(metrics(fpsCv = Double.NaN), gamePackage, baseConfig).accepted)
        assertFalse(SessionQualityGate.validateWindow(metrics(fpsCv = 0.76), gamePackage, baseConfig).accepted)
        assertTrue(SessionQualityGate.validateWindow(metrics(fpsCv = 0.75), gamePackage, baseConfig).accepted)
    }

    @Test fun `paired window temporal separation is quality gated`() {
        val baseline = metrics()
        val candidate = metrics().copy(firstTimestampMs = 100_000L, lastTimestampMs = 112_000L)
        val result = SessionQualityGate.compare(baseline, candidate, gamePackage, baseConfig)
        assertFalse(result.accepted)
        assertEquals("paired windows are too far apart", result.reason)
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
        val attempt = fixture.ledger.entries[task.id]!!.variants[AdaptiveLoop.DEFAULT_VARIANT_ID]!!
        assertEquals(1, attempt.attemptCount)
        assertEquals(PairOrder.BASELINE_THEN_CANDIDATE, attempt.lastAttemptOrder)
        assertEquals("adaptive session cancelled", attempt.lastInvalidReason)
    }

    @Test fun `paired trial alternates order and records block timing without retaining an insufficient candidate`() = runBlocking {
        val executor = FakeExecutor().apply { sysfs[PATH] = "0" }
        val task = MutationTask()
        val fixture = fixture(task, executor)

        fixture.loop.runTrial(task, fixture.ctx)

        val entry = fixture.ledger.entries[task.id]!!
        val observations = entry.variants[AdaptiveLoop.DEFAULT_VARIANT_ID]!!.observations
        assertEquals(2, observations.size)
        assertEquals(
            listOf(PairOrder.BASELINE_THEN_CANDIDATE, PairOrder.CANDIDATE_THEN_BASELINE),
            observations.map { it.order }
        )
        assertEquals(listOf(0, 1), observations.map { it.blockIndex })
        assertTrue(observations.all { it.qualityValid && it.invalidReason == null })
        assertTrue(observations.all { it.temporalDistanceMs in 1L..16_000L })
        assertTrue(observations.all { it.blockDurationMs >= 12_000L })
        assertTrue(observations.all { it.baseline.fpsMean == 60.0 && it.candidate.fpsMean == 66.0 })
        assertEquals(Decision.MORE_DATA, entry.decision)
        assertEquals("0", executor.readSys(PATH))
        assertTrue(fixture.ctx.journal.isEmpty())
    }

    @Test fun `variant sweep uses paired opposite orders and keeps independent evidence for each arm`() = runBlocking {
        val executor = FakeExecutor().apply { sysfs[GOVERNOR_PATH] = "schedutil" }
        val task = GovernorSweepDispatchTask()
        val fixture = fixture(task, executor)

        fixture.loop.runTrial(task, fixture.ctx)

        val entry = fixture.ledger.entries[task.id]!!
        val expectedOrder = listOf(PairOrder.BASELINE_THEN_CANDIDATE, PairOrder.CANDIDATE_THEN_BASELINE)
        for (governor in AdaptiveLoop.GOVERNOR_VARIANTS) {
            val observations = entry.variants["governor=$governor"]!!.observations
            assertEquals(2, observations.size)
            assertEquals(expectedOrder, observations.map { it.order })
            assertTrue(observations.all { it.qualityValid && it.invalidReason == null })
            assertTrue(observations.all { it.temporalDistanceMs <= 16_000L })
        }
        assertEquals(Decision.MORE_DATA, entry.decision)
        assertEquals("schedutil", executor.readSys(GOVERNOR_PATH))
        assertTrue(fixture.ctx.journal.isEmpty())
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
        assertEquals(samples.first().order, arm.observations.first().order)
        assertEquals(samples.first().temporalDistanceMs, arm.observations.first().temporalDistanceMs)
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS, arm.observations.first().score.objectiveWeights)
        assertEquals(3, arm.comparisonCount)
        assertNotNull(arm.observations.first().baseline.tempMeanC)
    }

    @Test fun `pair attempt alternation survives a ledger reload before measurement`() {
        val file = tempFile("pair-order")
        val context = context()
        val ledger = DecisionLedger(file)
        val first = ledger.beginPairAttempt("task", "Task", "default", "default", 1, context, 1_000L)!!
        val second = ledger.beginPairAttempt("task", "Task", "default", "default", 1, context, 2_000L)!!
        val reloaded = DecisionLedger(file).also { it.load() }
        val third = reloaded.beginPairAttempt("task", "Task", "default", "default", 1, context, 3_000L)!!

        assertEquals(listOf(0, 1, 2), listOf(first.blockIndex, second.blockIndex, third.blockIndex))
        assertEquals(
            listOf(PairOrder.BASELINE_THEN_CANDIDATE, PairOrder.CANDIDATE_THEN_BASELINE,
                PairOrder.BASELINE_THEN_CANDIDATE),
            listOf(first.order, second.order, third.order)
        )
        assertEquals(3, reloaded.entries["task"]!!.variants["default"]!!.attemptCount)
    }

    @Test fun `invalid and non-finite paired observations do not enter arm evidence`() {
        val ledger = DecisionLedger(tempFile("invalid-pair"))
        val valid = observation("a", "session", 0, 0.1)
        val validSecond = observation("a", "session", 1, 0.1)
        val nonFinite = valid.copy(sampleId = "non-finite", score = score(Double.NaN))
        val contaminated = valid.copy(sampleId = "contaminated", qualityValid = false)
        val wrongPolicy = valid.copy(
            sampleId = "wrong-policy",
            score = valid.score.copy(objectiveWeights = ObjectiveWeightResolver.THERMAL_CONTEXT_PROFILE.weights)
        )
        ledger.recordVariant(
            "task", "Task", "a", "A", listOf(valid, validSecond, nonFinite, contaminated, wrongPolicy),
            comparisonCount = 1, context = context(), nowMs = 21_000L, cfg = baseConfig
        )
        val arm = ledger.entries["task"]!!.variants["a"]!!
        assertEquals(2, arm.observations.size)
        assertEquals(2, arm.observations.count { it.qualityValid && it.score.score.isFinite() })
        assertEquals(0.1, arm.meanScore!!, 1e-9)
        assertEquals(2, ledger.validObservationCounts("task", context())["a"])
    }

    @Test fun `frame pacing metrics survive ledger round trip`() {
        val file = tempFile("frame-pacing-ledger")
        val ledger = DecisionLedger(file)
        val context = context()
        val pacing = FramePacingMetrics(
            targetFps = 60,
            averageFps = 58.5,
            medianFps = 59.0,
            meanFrameTimeMs = 17.0,
            medianFrameTimeMs = 16.7,
            p95FrameTimeMs = 25.0,
            p99FrameTimeMs = 34.0,
            frameTimeVarianceMs2 = 8.0,
            jankRate = 0.02,
            estimatedDroppedFrameRate = 0.01,
            stabilityScore = 91.0,
            smoothnessScore = 88.0,
            fpsSampleCount = 8,
            frameSampleCount = 600,
            intendedVsyncIntervalCount = 598
        )
        val measured = metrics().copy(framePacing = pacing)
        ledger.recordVariant(
            "frame_pacing", "Frame pacing", "default", "default",
            listOf(observation(
                "default", "frame-session", 0, 0.1,
                baseline = measured,
                candidate = measured
            )),
            comparisonCount = 1,
            context = context,
            nowMs = 20_000L,
            cfg = baseConfig
        )

        val loaded = DecisionLedger(file).also { it.load() }
        val observation = loaded.entries["frame_pacing"]!!.variants["default"]!!.observations.single()
        assertEquals(pacing, observation.baseline.framePacing)
        assertEquals(pacing, observation.candidate.framePacing)
    }

    @Test fun `v1_10 ledger observations load with explicit safe legacy defaults`() {
        val file = tempFile("legacy-objective")
        val oldShape = DecisionLedger(file)
        val currentContext = context()
        oldShape.recordVariant(
            "legacy", "Legacy", "a", "A",
            List(8) { observation("a", "legacy-session", it, 0.1) },
            comparisonCount = 1, context = currentContext, nowMs = 20_000L, cfg = baseConfig
        )

        val entry = JSONArray(file.readText()).getJSONObject(0)
        val contextJson = entry.getJSONObject("context")
        contextJson.remove("objectivePolicyRevision")
        contextJson.remove("objectivePolicyKey")
        val variantJson = entry.getJSONObject("variants").getJSONObject("a")
        variantJson.remove("attemptCount")
        variantJson.remove("lastAttemptIndex")
        variantJson.remove("lastAttemptOrder")
        variantJson.remove("lastAttemptAtMs")
        variantJson.remove("lastInvalidReason")
        val oldObservations = variantJson.getJSONArray("observations")
        for (i in 0 until oldObservations.length()) {
            val observation = oldObservations.getJSONObject(i)
            observation.remove("blockIndex")
            observation.remove("order")
            observation.remove("temporalDistanceMs")
            observation.remove("blockDurationMs")
            observation.remove("qualityValid")
            observation.remove("invalidReason")
            observation.getJSONObject("score").remove("objectiveWeights")
            observation.getJSONObject("baseline").remove("fpsCoefficientOfVariation")
            observation.getJSONObject("candidate").remove("fpsCoefficientOfVariation")
        }
        file.writeText(JSONArray().put(entry).toString())

        val loaded = DecisionLedger(file).also { it.load() }
        val loadedEntry = loaded.entries["legacy"]!!
        val legacyContext = loadedEntry.context!!
        val oldObservation = loadedEntry.variants["a"]!!.observations.first()
        assertEquals(0, legacyContext.objectivePolicyRevision)
        assertEquals(TrialContext.LEGACY_OBJECTIVE_POLICY_KEY, legacyContext.objectivePolicyKey)
        assertEquals(PairOrder.LEGACY_SEQUENTIAL, oldObservation.order)
        assertEquals(null, oldObservation.baseline.fpsCoefficientOfVariation)
        assertEquals(ObjectiveWeightResolver.DEFAULT_WEIGHTS, oldObservation.score.objectiveWeights)
        assertFalse(loaded.isCurrent(loadedEntry, currentContext, 20_001L, 60_000L))
    }

    @Test fun `cache context and expiry invalidate old decisions`() {
        val file = tempFile("context")
        val ledger = DecisionLedger(file)
        val now = System.currentTimeMillis()
        val current = context()
        ledger.recordVariant(
            "task", "Task", "default", "default",
            List(8) { observation("default", "session", it, 0.1) },
            comparisonCount = 1, context = current, nowMs = now, cfg = baseConfig
        )
        ledger.finalizeSingle("task", "Task", "default", current, now)
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

    private class GovernorSweepDispatchTask : BoostTask {
        override val id = "cpu_governor"
        override val titleAr = "اختبار"
        override val titleEn = "Governor sweep test"
        override val descAr = ""
        override val descEn = ""
        override val module = Module.CPU
        override val requiresPrivilege = true
        override fun isSupported(ctx: BoostContext) = true
        override fun isApplied(ctx: BoostContext) = false
        override fun apply(ctx: BoostContext) = TaskResult(id, TaskStatus.NoChange)
    }

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
            fps = if (executor.readSys(PATH) == "1" || executor.readSys(GOVERNOR_PATH) == "performance") 66 else 60,
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
        private const val GOVERNOR_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
    }
}
