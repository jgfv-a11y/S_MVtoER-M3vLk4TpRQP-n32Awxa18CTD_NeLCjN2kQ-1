package com.nitroboost.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.usage.UsageStatsManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.MutableLiveData
import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostEngine
import com.nitroboost.app.core.Journal
import com.nitroboost.app.core.MonitorClient
import com.nitroboost.app.core.MonitorDemand
import com.nitroboost.app.core.SessionSampleAccumulator
import com.nitroboost.app.core.ShellInput
import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.Bottleneck
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.FrameTimeAnalysis
import com.nitroboost.app.core.adaptive.LedgerEntry
import com.nitroboost.app.core.adaptive.ThermalTrend
import com.nitroboost.app.core.adaptive.TrialContext
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.performance.PerformanceStateEngine
import com.nitroboost.app.core.performance.PerformanceStateResult
import com.nitroboost.app.core.ScoreEngine
import com.nitroboost.app.core.SessionReport
import com.nitroboost.app.core.SessionReportBuilder
import com.nitroboost.app.core.SessionSampleSummary
import com.nitroboost.app.core.ThermalGuard
import com.nitroboost.app.core.TaskState
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.tasks.AllTasks
import com.nitroboost.app.core.tasks.BackgroundSelector
import com.nitroboost.app.core.tasks.RamKillTask
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.data.SessionLog
import com.nitroboost.app.platform.AndroidExecutor
import com.nitroboost.app.platform.MonitorHub
import com.nitroboost.app.platform.MonitorSnapshot
import com.nitroboost.app.platform.UsageEventForegroundResolver
import com.nitroboost.app.service.BoosterService
import com.nitroboost.app.service.WidgetProvider
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

sealed class SessionState {
    object Idle : SessionState()
    data class Boosting(val profileName: String) : SessionState()
    data class Boosted(
        val profileName: String,
        val score: Int,
        val applied: Int,
        val failed: Int,
        val startedAt: Long
    ) : SessionState()
}

/**
 * Central runtime state + action dispatcher.
 * Fragments observe LiveData; all heavy work runs on IO dispatchers.
 */
object AppStore {

    private const val MAX_PROCESS_RSS_KB = 1_048_576L // cap malformed output at 1 GiB/process

    @Volatile
    private var app: Context? = null

    val engine = BoostEngine(AllTasks.tasks)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val monitorLock = Any()
    private val monitorDemand = MonitorDemand()
    private val sessionStateLock = Any()
    /** Serializes boost requests against session teardown and whole-journal restore. */
    private val sessionLifecycleLock = Any()
    /** Fences queued boost requests so an old request cannot run after a stop/restore. */
    private val sessionLifecycleGeneration = AtomicLong(0L)

    val monitor = MutableLiveData<MonitorSnapshot>(MonitorSnapshot.EMPTY)
    val session = MutableLiveData<SessionState>(SessionState.Idle)
    val tasks = MutableLiveData<List<TaskState>>(emptyList())
    val score = MutableLiveData(0)
    val logLines = MutableLiveData<List<String>>(emptyList())
    val report = MutableLiveData<SessionReport?>(null)

    /** Adaptive-engine UI state, refreshed with every monitor sample. */
    data class AdaptiveUi(
        val enabled: Boolean,
        val running: Boolean,
        val phase: String,
        val pausedReason: String?,
        val effectiveThermal: Int,
        val osThermal: Int,
        val decisions: List<LedgerEntry>,
        val etaMinutes: Int,
        val performanceState: PerformanceStateResult? = null
    )

    val adaptiveUi = MutableLiveData<AdaptiveUi>(
        AdaptiveUi(false, false, "idle", null, 0, 0, emptyList(), 0)
    )

    private var hub: MonitorHub? = null
    private var theJournal: Journal? = null
    private var theLedger: DecisionLedger? = null
    private var adaptiveLoop: AdaptiveLoop? = null
    private val trialConfig = TrialConfig()
    private val adaptiveContextLock = Any()
    @Volatile private var cachedHardwareKeys: Pair<String, String>? = null
    @Volatile private var cachedProfileKey: Pair<String, String>? = null

    private val trend = ThermalTrend()
    private val performanceStateEngine = PerformanceStateEngine()
    @Volatile private var lastPerformanceState: PerformanceStateResult? = null
    private var lastThermalObservationAtMs = Long.MIN_VALUE
    @Volatile
    private var lastFrame: FrameMetrics? = null
    @Volatile
    private var lastFpsTs = 0L
    @Volatile
    private var currentTargetFps = 60

    // ---------------- Session measurement ----------------
    // While a boost session is active every monitor sample is kept; at the
    // end of the session they are summarized into a SessionReport and the
    // average FPS becomes the baseline for the next session's delta.

    @Volatile
    private var sessStart = 0L

    @Volatile
    private var sessApplied = 0

    @Volatile
    private var sessFailed = 0

    fun ledger(): DecisionLedger {
        theLedger?.let { return it }
        val l = DecisionLedger(File(ctx().filesDir, "adaptive_ledger.json"))
        l.load()
        theLedger = l
        return l
    }

    /** OS thermal status escalated by the predictive trend (never below OS). */
    fun effectiveThermalStatus(): Int {
        val s = monitor.value ?: return rawThermalFloor(null)
        val predictive = trend.effectiveStatus(s.thermalStatus)
        // The raw thermistor floor is independent of the optional thermal
        // override and must outrank every adaptive/profile preference.
        val rawFloor = rawThermalFloor(s.tempC)
        return maxOf(s.thermalStatus, predictive, rawFloor)
    }

    private fun rawThermalFloor(tempC: Double?): Int = ThermalGuard.rawStatusFor(
        tempC,
        thermalOverrideActive = runCatching { journal().containsTask("thermal_override") }
            .getOrDefault(false)
    )

    /**
     * Local-only cache key. Hardware identity is hashed and no context or
     * measurements leave the device. App version/capabilities invalidate old
     * task results when behavior or platform support may have changed.
     */
    private fun makeTrialContext(bctx: BoostContext, thermalTier: Int): TrialContext {
        val sdk = Build.VERSION.SDK_INT
        val staticKeys = cachedHardwareKeys ?: synchronized(adaptiveContextLock) {
            cachedHardwareKeys ?: run {
                val soc = if (sdk >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
                val appVersionCode = try {
                    val info = ctx().packageManager.getPackageInfo(ctx().packageName, 0)
                    if (sdk >= 28) info.longVersionCode else {
                        @Suppress("DEPRECATION")
                        val legacyCode = info.versionCode
                        legacyCode.toLong()
                    }
                } catch (_: Exception) {
                    0L
                }
                val deviceSeed = listOf(Build.MANUFACTURER, Build.MODEL, Build.BOARD, Build.HARDWARE, soc)
                    .joinToString("|")
                val capabilitySeed = listOf(
                    sdk.toString(), Build.VERSION.RELEASE, soc,
                    Build.SUPPORTED_ABIS.joinToString(","), appVersionCode.toString(),
                    TrialContext.TASK_REVISION.toString()
                ).joinToString("|")
                (stableLocalHash(deviceSeed) to stableLocalHash(capabilitySeed))
                    .also { cachedHardwareKeys = it }
            }
        }
        val profile = bctx.profile
        val profileSeed = listOf(
            profile.enabledModules.map { it.key }.sorted().joinToString(","),
            profile.fpsCap.toString(), profile.refreshRate.toString(), profile.dpi.toString(),
            profile.gameMode.toString(), profile.thermalOverride.toString()
        ).joinToString("|")
        val profileKey = cachedProfileKey?.takeIf { it.first == profileSeed }?.second
            ?: synchronized(adaptiveContextLock) {
                cachedProfileKey?.takeIf { it.first == profileSeed }?.second
                    ?: stableLocalHash(profileSeed).also { cachedProfileKey = profileSeed to it }
            }
        val currentTemp = monitor.value?.tempC
        val temperatureBand = when {
            currentTemp == null -> "unknown"
            currentTemp < 36.0 -> "cool"
            currentTemp < 40.0 -> "warm"
            currentTemp < 42.0 -> "hot"
            else -> "near-floor"
        }
        val slope = trend.slopePerMin()
        val slopeBand = when {
            slope >= ThermalGuard.STRONG_HEAT_SLOPE_PER_MIN -> "rapid-rise"
            slope >= ThermalGuard.EARLY_WARNING_SLOPE_PER_MIN -> "rising"
            slope <= -0.3 -> "cooling"
            else -> "flat"
        }
        return TrialContext(
            deviceKey = staticKeys.first,
            androidVersion = "$sdk:${Build.VERSION.RELEASE}",
            gamePackage = profile.packageName,
            boostLevel = Prefs.boostLevel(ctx()),
            thermalTier = thermalTier,
            capabilityKey = staticKeys.second,
            profileKey = profileKey,
            thermalSignature = "$temperatureBand:$slopeBand"
        )
    }

    private fun stableLocalHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    /**
     * Loop-side view of the monitor: poll returns one fused snapshot per tick
     * (capture-time deduped). Individual source timestamps preserve cache age;
     * only measured frame times and raw thermal context feed adaptive metrics.
     */
    private val sampler = object : AdaptiveSampler {
        override fun poll(): com.nitroboost.app.core.adaptive.AdaptiveSample? {
            val s = monitor.value ?: return null
            val now = android.os.SystemClock.elapsedRealtime()
            // A future or regressing capture time is malformed, not a fresh
            // zero-age observation; do not let it enter an A/B window.
            if (s.ts <= lastFpsTs || s.ts > now) return null
            lastFpsTs = s.ts
            val thermal = maxOf(
                s.thermalStatus,
                trend.effectiveStatus(s.thermalStatus),
                rawThermalFloor(s.tempC)
            )
            return com.nitroboost.app.core.adaptive.AdaptiveSample(
                fps = s.fps,
                thermal = thermal,
                timestampMs = s.ts,
                tempC = s.tempC,
                thermalSlopeCPerMin = if (s.tempC != null) trend.slopePerMinOrNull() else null,
                ramPct = s.ramPct.takeIf { s.ramSampleAvailable },
                frameTimesMs = s.performance?.frame?.frameTimesMs ?: s.frameTimesMs,
                energyMah = s.energyMah,
                gamePackage = s.gamePackage,
                processEpoch = s.processEpoch,
                thermalValid = s.thermalSampleAvailable || s.tempC != null,
                monitorAgeMs = now - s.ts,
                targetFps = s.performance?.frame?.targetFps ?: currentTargetFps,
                frameIntervalsMs = s.performance?.frame?.intendedVsyncIntervalsMs ?: s.frameIntervalsMs
            )
        }
        override fun fps(): Int? = monitor.value?.fps
        override fun metrics(): FrameMetrics? = lastFrame
        override fun privileged(): Boolean =
            try {
                AndroidExecutor(ctx()).privileged
            } catch (e: Exception) {
                false
            }
    }

    private val sessionSamples = SessionSampleAccumulator()

    private fun beginSessionMeasurement() {
        synchronized(sessionStateLock) {
            sessionSamples.clear()
            sessApplied = 0
            sessFailed = 0
            sessStart = System.currentTimeMillis()
        }
    }

    private fun finishSessionMeasurement() {
        val end = System.currentTimeMillis()
        var applied = 0
        var failed = 0
        var sampleSummary = SessionSampleSummary.EMPTY
        val start = synchronized(sessionStateLock) {
            sampleSummary = sessionSamples.snapshot()
            sessionSamples.clear()
            applied = sessApplied
            failed = sessFailed
            sessApplied = 0
            sessFailed = 0
            val s = sessStart
            sessStart = 0L
            s
        }
        if (start == 0L) return
        val prev = Prefs.getInt(ctx(), Prefs.KEY_PREV_FPS, -1).takeIf { it > 0 }
        val rep = SessionReportBuilder.summarize(
            start, end, sampleSummary, applied, failed, prev
        )
        try {
            val j = org.json.JSONObject()
                .put("startedAt", rep.startedAt)
                .put("endedAt", rep.endedAt)
                .put("durationSec", rep.durationSec)
                .put("avgFps", rep.avgFps ?: JSONObject.NULL)
                .put("minFps", rep.minFps ?: JSONObject.NULL)
                .put("peakTempC", rep.peakTempC ?: JSONObject.NULL)
                .put("minPingMs", rep.minPingMs ?: JSONObject.NULL)
                .put("peakRamMb", rep.peakRamMb)
                .put("applied", rep.applied)
                .put("failed", rep.failed)
                .put("previousAvgFps", rep.previousAvgFps ?: JSONObject.NULL)
                .put("deltaFps", rep.deltaFps ?: JSONObject.NULL)
                .put("endBottleneck", rep.endBottleneck?.name ?: JSONObject.NULL)
            Prefs.putString(ctx(), Prefs.KEY_LAST_REPORT, j.toString())
            if (rep.avgFps != null) Prefs.putInt(ctx(), Prefs.KEY_PREV_FPS, rep.avgFps)
        } catch (e: Exception) {
            // persistence is best-effort — still surface the report
        }
        report.postValue(rep)
    }

    /** Read the last persisted session report (survives app restarts). */
    fun loadLastReport(): SessionReport? {
        val raw = Prefs.sp(ctx()).getString(Prefs.KEY_LAST_REPORT, null) ?: return null
        return try {
            val j = org.json.JSONObject(raw)
            SessionReport(
                startedAt = j.optLong("startedAt"),
                endedAt = j.optLong("endedAt"),
                durationSec = j.optInt("durationSec"),
                avgFps = if (j.isNull("avgFps")) null else j.optInt("avgFps"),
                minFps = if (j.isNull("minFps")) null else j.optInt("minFps"),
                peakTempC = if (j.isNull("peakTempC")) null else j.optInt("peakTempC"),
                minPingMs = if (j.isNull("minPingMs")) null else j.optInt("minPingMs"),
                peakRamMb = j.optInt("peakRamMb"),
                applied = j.optInt("applied"),
                failed = j.optInt("failed"),
                previousAvgFps = if (j.isNull("previousAvgFps")) null else j.optInt("previousAvgFps"),
                deltaFps = if (j.isNull("deltaFps")) null else j.optInt("deltaFps"),
                endBottleneck = if (j.isNull("endBottleneck")) null
                else runCatching { Bottleneck.valueOf(j.optString("endBottleneck")) }.getOrNull()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Start/stop expensive monitoring only while a live consumer needs samples. */
    fun setMonitorClient(client: MonitorClient, enabled: Boolean) {
        synchronized(monitorLock) {
            monitorDemand.set(client, enabled)
            if (monitorDemand.shouldRun()) {
                startMonitorLocked()
            } else {
                hub?.stop()
                hub = null
                clearPerformanceState()
                monitor.postValue(MonitorSnapshot.EMPTY)
            }
        }
    }

    private fun startMonitorLocked() {
        if (hub != null) return
        val context = app ?: return
        clearPerformanceState()
        val newHub = MonitorHub(context.applicationContext)
        newHub.gamePackage = { gamePackage() }
        newHub.targetFps = { currentTargetFps.takeIf { gamePackage() != null } }
        hub = newHub
        newHub.start { sample ->
            monitor.postValue(collectSample(sample))
        }
    }

    private fun clearPerformanceState() {
        performanceStateEngine.reset()
        lastPerformanceState = null
        adaptiveUi.value?.let { current ->
            adaptiveUi.postValue(current.copy(performanceState = null))
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        loadLogs()
        adaptiveLoop = AdaptiveLoop(
            engine = engine,
            context = {
                val c = ctx()
                BoostContext(
                    ProfileStore(c).resolve(Prefs.activeProfile(c)),
                    AndroidExecutor(c),
                    journal()
                ) { line -> appendLog(line) }
            },
            ledger = ledger(),
            sampler = sampler,
            cfg = trialConfig,
            effectiveThermal = { effectiveThermalStatus() },
            log = { line -> appendLog("adaptive: $line") },
            maxLevel = { Prefs.boostLevel(ctx()) },
            trialContextFactory = { bctx, tier -> makeTrialContext(bctx, tier) }
        )
        // Self-healing monitor: if the sampling hub ever dies (process
        // pressure, ANR recovery), restart it so the UI and the adaptive
        // engine never go blind.
        scope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    if (!monitorDemand.shouldRun()) continue
                    val v = monitor.value
                    val now = android.os.SystemClock.elapsedRealtime()
                    // Restart not only on an empty snapshot: if the hub died
                    // the last posted sample stays around with a stale ts.
                    val stale = v == null || v.ts == 0L || now - v.ts > 20_000L
                    if (stale) {
                        synchronized(monitorLock) {
                            if (monitorDemand.shouldRun()) {
                                hub?.stop()
                                hub = null
                                startMonitorLocked()
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        // Crash resilience (v1.5.1): if a previous session died without
        // restoring (reboot, process kill, service stop), revert what the
        // journal still holds — nothing stays applied across a dead session.
        startStaleJournalGuard()
    }

    /**
     * Reverts journal leftovers of a DEAD session. No-op while the journal
     * is empty or a session is live. Retries every 10 s, which also covers
     * the window where Shizuku is not bound yet at app start (auto-activate
     * takes a few seconds), so even late-bound restores complete.
     */
    private fun startStaleJournalGuard() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                try {
                    val j = journal()
                    if (j.isEmpty()) continue // no stale work yet; keep watching future sessions
                    if (BoosterService.active) continue
                    val c = ctx()
                    val ex = AndroidExecutor(c)
                    val profile = ProfileStore(c).resolve(Prefs.activeProfile(c))
                    val before = j.snapshot().size
                    // Re-check right before the destructive step: a boost
                    // started in the gap owns the journal and wins.
                    if (BoosterService.active) continue
                    engine.restoreAll(BoostContext(profile, ex, j) { line -> appendLog(line) })
                    val remaining = j.snapshot().size
                    if (remaining < before) {
                        appendLog(
                            "stale-journal guard: reverted ${before - remaining} " +
                                "entr(ies) left by a dead session"
                        )
                    }
                } catch (e: Exception) {
                    // keep watching
                }
            }
        }
    }

    private fun collectSample(s: MonitorSnapshot): MonitorSnapshot {
        // Record only a new sensor observation; cached temperatures can appear
        // in several one-second monitor snapshots with the same source time.
        val temperatureAtMs = s.performance?.sourceTimestamps?.temperatureAtMs
            ?: if (s.performance == null && s.tempC != null) s.ts else null
        if (temperatureAtMs != null && temperatureAtMs in 0L..s.ts &&
            temperatureAtMs != lastThermalObservationAtMs
        ) {
            trend.record(temperatureAtMs, s.tempC)
            lastThermalObservationAtMs = temperatureAtMs
        }
        // The existing predictive thermal trend enriches the snapshot; its
        // slope stays null until two distinct, real readings are available.
        val thermalSlope = if (s.tempC != null) trend.slopePerMinOrNull() else null
        val effectiveThermal = maxOf(
            s.thermalStatus,
            trend.effectiveStatus(s.thermalStatus),
            rawThermalFloor(s.tempC)
        )
        val enriched = s.copy(
            performance = s.performance?.let { performance ->
                performance.copy(
                    thermal = performance.thermal.copy(
                        slopeCPerMin = thermalSlope,
                        effectiveStatus = effectiveThermal
                    )
                )
            }
        )
        lastPerformanceState = performanceStateEngine.analyze(
            snapshot = enriched.performance,
            timestampMs = enriched.ts,
            gamePackage = enriched.gamePackage,
            processEpoch = enriched.processEpoch
        )
        lastFrame = FrameMetrics(
            fps = s.fps,
            targetFps = s.performance?.frame?.targetFps ?: currentTargetFps,
            cpuPct = s.cpuPct,
            ramPct = s.ramPct,
            pingMs = s.pingMs,
            retransPerSec = s.retransPerSec,
            thermalStatus = s.thermalStatus,
            tempC = s.tempC,
            frameTime = FrameTimeAnalysis.summarize(
                s.performance?.frame?.frameTimesMs ?: s.frameTimesMs,
                currentTargetFps
            ),
            monitorTimestampMs = s.ts,
            gamePackage = s.gamePackage
        )
        postAdaptiveUi(enriched)
        synchronized(sessionStateLock) {
            if (sessStart != 0L) {
                sessionSamples.add(
                    fps = s.fps,
                    tempC = s.tempC?.toInt(),
                    pingMs = s.pingMs,
                    ramMb = s.ramUsedMb.toInt().takeIf { it > 0 }
                )
            }
        }
        return enriched
    }

    private fun postAdaptiveUi(s: MonitorSnapshot) {
        try {
            val loop = adaptiveLoop
            var eta = 0
            if (loop?.isRunning == true) {
                try {
                    val c = ctx()
                    eta = loop.estimateRemainingMinutes(
                        BoostContext(
                            ProfileStore(c).resolve(Prefs.activeProfile(c)),
                            AndroidExecutor(c),
                            journal()
                        )
                    )
                } catch (e: Exception) {
                }
            }
            adaptiveUi.postValue(
                AdaptiveUi(
                    enabled = Prefs.getBool(ctx(), Prefs.KEY_ADAPTIVE_ON, true),
                    running = loop?.isRunning == true,
                    phase = loop?.phase ?: "idle",
                    pausedReason = loop?.pausedReason,
                    effectiveThermal = maxOf(
                        s.thermalStatus,
                        s.performance?.thermal?.effectiveStatus ?: 0,
                        rawThermalFloor(s.tempC)
                    ),
                    osThermal = s.thermalStatus,
                    decisions = ledger().snapshotEntries()
                        .sortedByDescending { it.pairs }
                        .take(5),
                    etaMinutes = eta,
                    performanceState = lastPerformanceState
                )
            )
        } catch (e: Exception) {
            // UI state must never break the monitor
        }
    }

    fun ctx(): Context {
        val c = app ?: error("AppStore not initialized")
        return c
    }

    fun journal(): Journal {
        theJournal?.let { return it }
        val j = Journal(File(ctx().filesDir, "journal.json"))
        theJournal = j
        return j
    }

    fun executor(): AndroidExecutor = AndroidExecutor(ctx())

    /** Currently boosted game package (set by BoosterService). */
    @Volatile
    private var currentGame: String? = null

    fun setGamePackage(pkg: String?) {
        currentGame = pkg
    }

    fun gamePackage(): String? =
        currentGame ?: Prefs.activeProfile(ctx())?.takeIf { it.isNotBlank() }

    fun appendLog(line: String) {
        val list = logLines.value?.toMutableList() ?: mutableListOf()
        list.add(line)
        if (list.size > 100) list.removeAt(0)
        logLines.postValue(list)
        try {
            SessionLog.log(ctx(), "engine", line)
        } catch (e: Exception) {
        }
    }

    fun loadLogs() {
        try {
            val lines = SessionLog.lines(ctx()).map { line ->
                try {
                    val o = org.json.JSONObject(line)
                    "${o.optString("ts")}  ${o.optString("event")}: ${o.optString("detail")}"
                } catch (e: Exception) {
                    line
                }
            }
            logLines.postValue(lines.reversed())
        } catch (e: Exception) {
            logLines.postValue(emptyList())
        }
    }

    // ---------------- Actions ----------------

    fun boost(profilePkg: String? = null) {
        val c = ctx()
        val profile = ProfileStore(c).resolve(profilePkg ?: Prefs.activeProfile(c))
        val requestGeneration = sessionLifecycleGeneration.get()
        scope.launch(Dispatchers.IO) {
            synchronized(sessionLifecycleLock) {
            if (requestGeneration != sessionLifecycleGeneration.get() || BoosterService.isEnding() ||
                !sessionLifecycleGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
            ) return@synchronized
            val previousSessionState = session.value ?: SessionState.Idle
            session.postValue(SessionState.Boosting(profile.name))
            var activeJournal: Journal? = null
            var activeExecutor: AndroidExecutor? = null
            var measurementStarted = false
            try {
                val journal = journal().also { activeJournal = it }
                val executor = AndroidExecutor(c).also { activeExecutor = it }
                val previousPackage = gamePackage()
                val switchingProfile = !previousPackage.isNullOrBlank() && previousPackage != profile.packageName
                val adaptive = adaptiveLoop
                if (adaptive?.isRunning == true) adaptive.stopAndJoinBlocking()
                val mustRestorePriorSession = switchingProfile ||
                    (!BoosterService.active && !journal.isEmpty())
                if (mustRestorePriorSession && !journal.isEmpty()) {
                    val oldProfile = ProfileStore(c).resolve(previousPackage ?: Prefs.activeProfile(c))
                    val restore = engine.restoreAll(
                        BoostContext(oldProfile, executor, journal) { line -> appendLog(line) }
                    )
                    if (restore.failedCount > 0 || !journal.isEmpty()) {
                        appendLog("profile boost cancelled: prior session restore incomplete; journal retained")
                        session.postValue(previousSessionState)
                        return@launch
                    }
                }
                if (switchingProfile) finishSessionMeasurement()
                beginSessionMeasurement()
                measurementStarted = true
                wireRamKill(profile, executor)
                val bctx = BoostContext(profile, executor, journal) { line -> appendLog(line) }
                // Never re-apply the candidate the adaptive engine is
                // currently measuring — it would corrupt its arm window.
                val reserved: Set<String> = adaptiveLoop?.let {
                    if (it.isRunning) setOfNotNull(it.candidateId) else emptySet()
                } ?: emptySet()
                // v1.5: level gate — 1 = basics, 2 = standard, 3 = max.
                val maxLevel = Prefs.boostLevel(c)
                val report = engine.boost(bctx, exclude = reserved, maxLevel = maxLevel)
                // Apply the non-disableable thermal floor immediately after
                // initial tasks; the service watchdog remains a second line.
                val thermalFloor = effectiveThermalStatus()
                if (thermalFloor >= ThermalGuard.STATUS_MODERATE) {
                    engine.deescalate(bctx, ThermalGuard.modulesToDrop(thermalFloor))
                }
                synchronized(sessionStateLock) {
                    sessApplied = report.appliedCount + report.noChangeCount
                    sessFailed = report.failedCount
                }
                setGamePackage(profile.packageName)
                BoosterService.updateActiveGame(profile.packageName)
                currentTargetFps = profile.fpsCap
                    .takeIf { it > 0 }
                    ?: profile.refreshRate.takeIf { it > 0 }
                    ?: 60
                honorLedger(bctx, skip = reserved, maxLevel = maxLevel)
                if (Prefs.getBool(c, Prefs.KEY_ADAPTIVE_ON, true)) {
                    adaptiveLoop?.start()
                }
                refreshTaskStates()
                val s = postScore(profile)
                if (BoosterService.active) BoosterService.pushScore(c, s)
                session.postValue(
                    SessionState.Boosted(
                        profile.name, s,
                        report.appliedCount + report.noChangeCount,
                        report.failedCount,
                        System.currentTimeMillis()
                    )
                )
                try {
                    WidgetProvider.update(c)
                } catch (e: Exception) {
                }
                // The floating monitor goes up with the session — that is
                // where the user expects it (over the game).
                if (Prefs.getBool(c, Prefs.KEY_OVERLAY_ON, false)) {
                    if (android.provider.Settings.canDrawOverlays(c)) {
                        try {
                            com.nitroboost.app.service.FpsOverlayService.start(c)
                        } catch (e: Exception) {
                        }
                    } else {
                        overlayPermNeeded.value = true
                    }
                }
            } catch (e: Exception) {
                appendLog("boost crashed: ${e.message}")
                try {
                    adaptiveLoop?.stopAndJoinBlocking()
                } catch (cleanupError: Exception) {
                    appendLog("adaptive cleanup after boost failure failed: ${cleanupError.message}")
                }
                val cleanupJournal = activeJournal ?: try {
                    journal()
                } catch (_: Exception) {
                    null
                }
                val rollbackComplete = when {
                    cleanupJournal == null -> false
                    cleanupJournal.isEmpty() -> true
                    else -> {
                        val cleanupExecutor = activeExecutor ?: try {
                            AndroidExecutor(c)
                        } catch (_: Exception) {
                            null
                        }
                        val restore = cleanupExecutor?.let { ex ->
                            runCatching {
                                engine.restoreAll(
                                    BoostContext(profile, ex, cleanupJournal) { line -> appendLog(line) }
                                )
                            }.getOrNull()
                        }
                        restore != null && restore.failedCount == 0 && cleanupJournal.isEmpty()
                    }
                }
                if (rollbackComplete) {
                    setGamePackage(null)
                    if (measurementStarted) runCatching { finishSessionMeasurement() }
                    refreshTaskStates()
                    session.postValue(SessionState.Idle)
                    appendLog("boost failure rolled back; session returned to idle")
                } else {
                    appendLog("boost rollback incomplete; journal retained for retry")
                    session.postValue(SessionState.Boosting(profile.name))
                }
                // A foreground service must not keep advertising a session
                // whose initial/re-profile boost failed; it will retry restore
                // before releasing ownership of any retained journal entries.
                if (BoosterService.active) BoosterService.stop(c)
            }
            }
        }
    }

    /** Set once per session start when the overlay is on but not permitted. */
    val overlayPermNeeded = MutableLiveData(false)

    /**
     * The engine's verdicts are binding on every boost:
     *  - DROP  -> if the boost re-applied a measured-harmful task, revert it
     *    ("no conflicts" includes conflicts with our own past measurements);
     *  - KEEP with a sweep detail (e.g. "level=0.7") -> the normal boost
     *    applied the built-in default level; replace it with the winner the
     *    engine measured on THIS device.
     */
    private fun honorLedger(
        ctx: BoostContext,
        skip: Set<String> = emptySet(),
        maxLevel: Int = 3
    ) {
        // Context and expiry are part of the cache key: a result from another
        // device, OS, game, profile, thermal tier or app/task revision is not
        // applied blindly. The ledger remains private to filesDir.
        val decisions = ledger()
        val thermalTier = effectiveThermalStatus()
        val now = System.currentTimeMillis()
        // v1.5: the user's level valve wins over the ledger — a level-1
        // session must not re-apply a level-2/3 "winner".
        for (entry in decisions.snapshotEntries()) {
            val currentContext = adaptiveLoop?.trialContextForTask(entry.taskId, ctx, thermalTier)
                ?: makeTrialContext(ctx, thermalTier)
            if (!decisions.isCurrent(entry, currentContext, now, trialConfig.decisionTtlMs)) continue
            if (entry.taskId in skip) continue
            val task = com.nitroboost.app.core.tasks.AllTasks.byId[entry.taskId]
            if (task != null && task.boostLevel > maxLevel) continue
            val entries = ctx.journal.snapshot().filter { it.taskId == entry.taskId }
            when (entry.decision) {
                com.nitroboost.app.core.adaptive.Decision.DROP -> {
                    if (entries.isEmpty()) continue
                    engine.withTaskLock(entry.taskId) {
                        val ok = entries.filter { com.nitroboost.app.core.Journal.restore(it, ctx.executor) }
                        if (ok.isNotEmpty()) {
                            ctx.journal.remove(ok)
                            appendLog("adaptive: reverted dropped task ${entry.taskId}")
                        }
                        if (ok.size != entries.size) {
                            appendLog("adaptive: restore failed for dropped task ${entry.taskId}; journal retained")
                        }
                    }
                }
                com.nitroboost.app.core.adaptive.Decision.KEEP -> {
                    val currentThermal = effectiveThermalStatus()
                    if (currentThermal >= ThermalGuard.STATUS_MODERATE) {
                        appendLog("adaptive: skipped KEEP for ${entry.taskId}; thermal floor active ($currentThermal)")
                        continue
                    }
                    val detail = entry.detail ?: continue
                    val winner = when {
                        entry.taskId == "game_api_downscale" && detail.startsWith("level=") -> {
                            val level = detail.removePrefix("level=")
                            if (level == com.nitroboost.app.core.tasks.GameApiTask.DOWNSCALE) {
                                continue
                            }
                            com.nitroboost.app.core.tasks.GameApiTask(level = level) to "level $level"
                        }
                        entry.taskId == "cpu_governor" && detail.startsWith("governor=") -> {
                            val g = detail.removePrefix("governor=")
                            if (g == "performance") continue
                            com.nitroboost.app.core.tasks.GovernorTask(g) to "governor $g"
                        }
                        else -> continue
                    }
                    engine.withTaskLock(entry.taskId) {
                        val ok = entries.filter { com.nitroboost.app.core.Journal.restore(it, ctx.executor) }
                        if (ok.size != entries.size) {
                            if (ok.isNotEmpty()) ctx.journal.remove(ok)
                            appendLog("adaptive: restore failed before ${winner.second}; journal retained")
                            return@withTaskLock
                        }
                        if (ok.isNotEmpty()) ctx.journal.remove(ok)
                        val r = try {
                            engine.applyWithJournal(entry.taskId, ctx) { taskContext ->
                                winner.first.apply(taskContext)
                            }
                        } catch (e: Exception) {
                            null
                        }
                        if (r != null && r.status.success &&
                            (r.entries.isNotEmpty() || r.status == com.nitroboost.app.core.TaskStatus.NoChange)
                        ) {
                            appendLog("adaptive: restored winning ${winner.second}")
                        } else {
                            appendLog("adaptive: failed to apply winning ${winner.second}")
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    fun stopSession() {
        sessionLifecycleGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) { stopSessionBlockingInternal() }
    }

    /** Synchronous service-stop path; waits for adaptive cleanup before restoring the full journal. */
    fun stopSessionBlocking(): BoostEngine.Report? = stopSessionBlockingInternal()

    private fun stopSessionBlockingInternal(): BoostEngine.Report? = synchronized(sessionLifecycleLock) {
        sessionLifecycleGeneration.incrementAndGet()
        try {
            adaptiveLoop?.stopAndJoinBlocking()
            val c = ctx()
            val profile = ProfileStore(c).resolve(Prefs.activeProfile(c))
            val activeJournal = journal()
            val bctx = BoostContext(profile, AndroidExecutor(c), activeJournal) { line -> appendLog(line) }
            val restore = engine.restoreAll(bctx)
            if (restore.failedCount > 0 || !activeJournal.isEmpty()) {
                appendLog("session restore incomplete: ${restore.failedCount} change(s) failed; journal retained")
                return@synchronized restore
            }
            setGamePackage(null)
            finishSessionMeasurement()
            refreshTaskStates()
            val restoredScore = postScore(profile)
            if (BoosterService.active) BoosterService.pushScore(c, restoredScore)
            session.postValue(SessionState.Idle)
            try {
                WidgetProvider.update(c)
            } catch (_: Exception) {
            }
            restore
        } catch (e: Exception) {
            appendLog("stop failed: ${e.message}")
            null
        }
    }

    fun restoreAll() = stopSession()

    /**
     * Synchronous restore for process/service teardown. [stopSession] is
     * async (IO dispatcher) and would lose the race against a dying
     * process — this path runs on the caller's thread so `onDestroy`
     * actually reverts leftover tweaks.
     */
    fun restoreAllBlocking(): BoostEngine.Report? = synchronized(sessionLifecycleLock) {
        sessionLifecycleGeneration.incrementAndGet()
        try {
            adaptiveLoop?.stopAndJoinBlocking()
            val c = ctx()
            val activeJournal = journal()
            val bctx = BoostContext(
                ProfileStore(c).resolve(Prefs.activeProfile(c)),
                AndroidExecutor(c), activeJournal
            ) { line -> appendLog(line) }
            val restore = engine.restoreAll(bctx)
            if (restore.failedCount > 0 || !activeJournal.isEmpty()) {
                appendLog("blocking restore incomplete: ${restore.failedCount} change(s) failed; journal retained")
                return@synchronized restore
            }
            setGamePackage(null)
            session.postValue(SessionState.Idle)
            restore
        } catch (e: Exception) {
            try {
                appendLog("blocking restore failed: ${e.message}")
            } catch (_: Exception) {
            }
            null
        }
    }

    fun refreshTaskStates() {
        scope.launch(Dispatchers.IO) {
            try {
                val c = ctx()
                val profile = ProfileStore(c).resolve(Prefs.activeProfile(c))
                val states = engine.states(
                    BoostContext(profile, AndroidExecutor(c), journal())
                )
                tasks.postValue(states)
            } catch (e: Exception) {
                tasks.postValue(emptyList())
            }
        }
    }

    /**
     * (current score, potential score). The potential is what the SAME
     * device could reach with every applicable task applied — so the user
     * sees "3 / 62" instead of a mysterious "0" before the first boost.
     */
    private fun computeScores(profile: AppProfile): Pair<Int, Int> {
        val c = ctx()
        try {
            val states = tasks.value
            val applicable = states?.count { it.supported && profile.isEnabled(engine.taskFor(it.id) ?: return@count false) } ?: 0
            val applied = states?.count { it.applied } ?: 0
            val snap = monitor.value ?: MonitorSnapshot.EMPTY
            val ramFree = if (snap.ramTotalMb > 0) {
                (snap.ramTotalMb - snap.ramUsedMb).toDouble() / snap.ramTotalMb
            } else 0.0
            val storage = try {
                val st = StatFs(Environment.getDataDirectory().path)
                st.availableBlocks.toDouble() / st.blockCountLong
            } catch (e: Exception) {
                0.0
            }
            val thermal = try {
                AndroidExecutor(c).thermalStatus()
            } catch (e: Exception) {
                0
            }
            val current = ScoreEngine.compute(
                ScoreEngine.Inputs(applied, applicable, thermal, ramFree, storage)
            )
            // For the potential we count PENDING tasks too: they are not
            // applicable yet only because Shizuku/root is not connected,
            // not because the device lacks them.
            val applicableWithPending = states?.count {
                (it.supported || it.pending) &&
                    profile.isEnabled(engine.taskFor(it.id) ?: return@count false)
            } ?: applicable
            val potential = ScoreEngine.compute(
                ScoreEngine.Inputs(applicableWithPending, applicableWithPending, thermal, ramFree, storage)
            )
            return current to potential
        } catch (e: Exception) {
            return 0 to 0
        }
    }

    private fun postScore(profile: AppProfile): Int {
        val (s, p) = computeScores(profile)
        score.postValue(s)
        scorePotential.postValue(p)
        return s
    }

    /** Maximum the device can reach with this profile — shown as "X / Y". */
    val scorePotential = MutableLiveData(0)

    // ---------------- Background apps ----------------

    private fun wireRamKill(profile: AppProfile, executor: AndroidExecutor) {
        val task = AllTasks.byId["ram_kill"] as? RamKillTask ?: return
        task.killableProvider = {
            try {
                val c = ctx()
                val pm = c.packageManager
                val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val since = now - 24 * 3600 * 1000L
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
                val background = LinkedHashMap<String, Long>()
                for (s in stats) {
                    val pkg = s.packageName ?: continue
                    if (!ShellInput.isPackageName(pkg)) continue
                    val last = s.lastTimeUsed
                    val existing = background[pkg]
                    if (existing == null || last > existing) background[pkg] = last
                }
                val foreground = UsageEventForegroundResolver.foregroundPackages(c)

                val protectedSet = HashSet<String>()
                protectedSet.addAll(profile.extraProtected.filter(ShellInput::isPackageName))
                protectedSet.addAll(Prefs.protectedList(c))
                protectedSet.add(profile.packageName)
                protectedSet.add(c.packageName)
                protectedSet.add("android")

                // drop system apps (uid < 10000) — they are never killable
                val userApps = background.keys.filter { pkg ->
                    if (!ShellInput.isPackageName(pkg)) return@filter false
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        ai.uid >= 10000
                    } catch (e: Exception) {
                        false
                    }
                }
                val userMap = userApps.associateWith { background[it] ?: 0L }
                BackgroundSelector.select(
                    userMap, foreground.firstOrNull(), c.packageName, protectedSet, now,
                    gamePackage = profile.packageName,
                    foregroundPackages = foreground
                )
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun foregroundPackage(): String? {
        val c = ctx()
        return UsageEventForegroundResolver.currentPackage(c)
            ?.takeIf { it != c.packageName && ShellInput.isPackageName(it) }
    }

    /**
     * Top RAM consumers for the System tab.
     *  - with a bound Shizuku service: real per-process RSS via
     *    `ps -A -o PID,RSS,NAME` (non-blocking call);
     *  - otherwise: recently used apps (Android has no public per-app
     *    memory API without privilege) — the RAM column stays 0.
     */
    fun topProcesses(limit: Int = 20): List<ProcessInfo> {
        val safeLimit = limit.coerceIn(0, 100)
        if (safeLimit == 0) return emptyList()
        try {
            val c = ctx()
            val ex = AndroidExecutor(c)
            val r = ex.shellNonBlocking("ps -A -o PID,RSS,NAME")
            if (r != null && r.ok) {
                val pm = c.packageManager
                val processes = r.stdout.lineSequence()
                    .drop(1)
                    .mapNotNull { line ->
                        val p = line.trim().split(Regex("\\s+"))
                        if (p.size < 3) return@mapNotNull null
                        val rssKb = p[1].toLongOrNull()?.takeIf { it in 1..MAX_PROCESS_RSS_KB }
                            ?: return@mapNotNull null
                        // On Android the ps process name is the package
                        // (possibly with a ":suffix" for child processes).
                        val procName = p.drop(2).joinToString(" ")
                        val pkg = procName.substringBefore(":")
                        if (!ShellInput.isPackageName(pkg)) return@mapNotNull null
                        val appInfo = try {
                            pm.getApplicationInfo(pkg, 0)
                        } catch (_: Exception) {
                            return@mapNotNull null
                        }
                        if (appInfo.uid < 10000) return@mapNotNull null
                        val name = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (_: Exception) {
                            pkg
                        }
                        ProcessInfo(pkg, name, rssKb * 1024L, 0L)
                    }
                    .groupBy { it.pkg }
                    .map { (_, appProcesses) ->
                        val first = appProcesses.first()
                        first.copy(ramBytes = appProcesses.sumOf { it.ramBytes })
                    }
                    .sortedByDescending { it.ramBytes }
                    .take(safeLimit)
                if (processes.size >= 3) return processes
            }
        } catch (e: Exception) {
            // fall through to the UsageStats path
        }
        return try {
            val c = ctx()
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm = c.packageManager
            val now = System.currentTimeMillis()
            val since = now - 24 * 3600 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now)
            stats.mapNotNull { s ->
                val pkg = s.packageName ?: return@mapNotNull null
                if (!ShellInput.isPackageName(pkg)) return@mapNotNull null
                val appInfo = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                if (appInfo.uid < 10000) return@mapNotNull null
                val name = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }
                ProcessInfo(pkg, name, 0L, s.lastTimeUsed)
            }
                .sortedByDescending { it.lastUsed }
                .take(safeLimit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun installedGames(): List<ProcessInfo> {
        return try {
            val c = ctx()
            val pm = c.packageManager
            pm.getInstalledApplications(0)
                .filter { ai ->
                    ShellInput.isPackageName(ai.packageName) && try {
                        ai.enabled
                    } catch (e: Exception) {
                        false
                    }
                }
                .filter { ai ->
                    try {
                        val pkg = pm.getPackageInfo(ai.packageName, 0)
                        pkg.applicationInfo?.let {
                            it.enabled && ai.uid >= 10000
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                }
                .map { ai ->
                    val name = try {
                        pm.getApplicationLabel(ai).toString()
                    } catch (e: Exception) {
                        ai.packageName
                    }
                    ProcessInfo(ai.packageName, name, 0L, 0L)
                }
                .filter { it.name.isNotEmpty() }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun batteryInfo(): Pair<Int, Boolean> {
        return try {
            val c = ctx()
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val plugged = c.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            level to (plugged != 0)
        } catch (e: Exception) {
            0 to false
        }
    }

    data class ProcessInfo(
        val pkg: String,
        val name: String,
        val ramBytes: Long,
        val lastUsed: Long
    )
}
