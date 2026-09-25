package com.nitroboost.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.MutableLiveData
import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.BoostEngine
import com.nitroboost.app.core.Journal
import com.nitroboost.app.core.adaptive.AdaptiveLoop
import com.nitroboost.app.core.adaptive.AdaptiveSampler
import com.nitroboost.app.core.adaptive.Bottleneck
import com.nitroboost.app.core.adaptive.BottleneckDetector
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.LedgerEntry
import com.nitroboost.app.core.adaptive.ThermalTrend
import com.nitroboost.app.core.adaptive.TrialConfig
import com.nitroboost.app.core.ScoreEngine
import com.nitroboost.app.core.SessionReport
import com.nitroboost.app.core.SessionReportBuilder
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

    @Volatile
    private var app: Context? = null

    val engine = BoostEngine(AllTasks.tasks)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        val bottleneck: Bottleneck,
        val effectiveThermal: Int,
        val osThermal: Int,
        val decisions: List<LedgerEntry>,
        val etaMinutes: Int
    )

    val adaptiveUi = MutableLiveData<AdaptiveUi>(
        AdaptiveUi(false, false, "idle", null, Bottleneck.UNKNOWN, 0, 0, emptyList(), 0)
    )

    private var hub: MonitorHub? = null
    private var theJournal: Journal? = null
    private var theLedger: DecisionLedger? = null
    private var adaptiveLoop: AdaptiveLoop? = null

    private val trend = ThermalTrend()
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
        val s = monitor.value ?: return 0
        return maxOf(s.thermalStatus, trend.effectiveStatus(s.thermalStatus))
    }

    /**
     * Loop-side view of the monitor: [poll] returns one FRESH aligned
     * (fps, thermal) pair per monitor tick (ts-deduped) so the adaptive
     * engine's baseline/arm windows stay sample-aligned.
     */
    private val sampler = object : AdaptiveSampler {
        override fun poll(): com.nitroboost.app.core.adaptive.AdaptiveSample? {
            val s = monitor.value ?: return null
            if (s.ts == lastFpsTs) return null
            lastFpsTs = s.ts
            return com.nitroboost.app.core.adaptive.AdaptiveSample(s.fps, s.thermalStatus)
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

    private val measLock = Any()
    private val sessFps = mutableListOf<Int>()
    private val sessTemp = mutableListOf<Int>()
    private val sessPing = mutableListOf<Int>()
    private val sessRam = mutableListOf<Int>()

    private fun beginSessionMeasurement() {
        synchronized(measLock) {
            sessFps.clear()
            sessTemp.clear()
            sessPing.clear()
            sessRam.clear()
            sessApplied = 0
            sessFailed = 0
            sessStart = System.currentTimeMillis()
        }
    }

    private fun finishSessionMeasurement() {
        val end = System.currentTimeMillis()
        val fps = mutableListOf<Int>()
        val temp = mutableListOf<Int>()
        val ping = mutableListOf<Int>()
        val ram = mutableListOf<Int>()
        var applied: Int
        var failed: Int
        val start = synchronized(measLock) {
            fps += sessFps
            temp += sessTemp
            ping += sessPing
            ram += sessRam
            applied = sessApplied
            failed = sessFailed
            sessFps.clear()
            sessTemp.clear()
            sessPing.clear()
            sessRam.clear()
            sessApplied = 0
            sessFailed = 0
            val s = sessStart
            sessStart = 0L
            s
        }
        if (start == 0L) return
        val prev = Prefs.getInt(ctx(), Prefs.KEY_PREV_FPS, -1).takeIf { it > 0 }
        val rep = SessionReportBuilder.summarize(
            start, end, fps, temp, ping, ram, applied, failed, prev,
            endBottleneck = lastFrame?.let { BottleneckDetector.detect(it) }
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

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val h = MonitorHub(ctx.applicationContext)
        h.gamePackage = { gamePackage() }
        hub = h
        h.start { s ->
            monitor.postValue(s)
            collectSample(s)
        }
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
            cfg = TrialConfig(),
            effectiveThermal = { effectiveThermalStatus() },
            log = { line -> appendLog("adaptive: $line") }
        )
        // Self-healing monitor: if the sampling hub ever dies (process
        // pressure, ANR recovery), restart it so the UI and the adaptive
        // engine never go blind.
        scope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    val v = monitor.value
                    if (v == null || v.ts == 0L) {
                        try {
                            hub?.stop()
                        } catch (e: Exception) {
                        }
                        val h = MonitorHub(ctx().applicationContext)
                        h.gamePackage = { gamePackage() }
                        hub = h
                        h.start { s ->
                            monitor.postValue(s)
                            collectSample(s)
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun collectSample(s: MonitorSnapshot) {
        // Predictive thermal trend + frame metrics feed the adaptive engine
        // every sample, session or not.
        trend.record(s.ts, s.tempC)
        lastFrame = FrameMetrics(
            fps = s.fps,
            targetFps = currentTargetFps,
            cpuPct = s.cpuPct,
            ramPct = s.ramPct,
            pingMs = s.pingMs,
            retransPerSec = s.retransPerSec,
            thermalStatus = s.thermalStatus,
            tempC = s.tempC
        )
        postAdaptiveUi()
        if (sessStart == 0L) return
        synchronized(measLock) {
            s.fps?.let { sessFps.add(it) }
            s.tempC?.let { sessTemp.add(it.toInt()) }
            s.pingMs?.let { sessPing.add(it) }
            if (s.ramUsedMb > 0) sessRam.add(s.ramUsedMb.toInt())
        }
    }

    private fun postAdaptiveUi() {
        try {
            val s = monitor.value ?: return
            val frame = lastFrame ?: return
            val loop = adaptiveLoop
            val loop2 = loop
            var eta = 0
            if (loop2?.isRunning == true) {
                try {
                    val c = ctx()
                    eta = loop2.estimateRemainingMinutes(
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
                    running = loop2?.isRunning == true,
                    phase = loop2?.phase ?: "idle",
                    pausedReason = loop2?.pausedReason,
                    bottleneck = BottleneckDetector.detect(frame),
                    effectiveThermal = effectiveThermalStatus(),
                    osThermal = s.thermalStatus,
                    decisions = ledger().entries.values.toList()
                        .sortedByDescending { it.pairs }
                        .take(5),
                    etaMinutes = eta
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
        session.postValue(SessionState.Boosting(profile.name))
        beginSessionMeasurement()
        scope.launch(Dispatchers.IO) {
            try {
                val journal = journal()
                val executor = AndroidExecutor(c)
                wireRamKill(profile, executor)
                val bctx = BoostContext(profile, executor, journal) { line -> appendLog(line) }
                val report = engine.boost(bctx)
                sessApplied = report.appliedCount + report.noChangeCount
                sessFailed = report.failedCount
                setGamePackage(profile.packageName)
                currentTargetFps = profile.fpsCap
                    .takeIf { it > 0 }
                    ?: profile.refreshRate.takeIf { it > 0 }
                    ?: 60
                honorLedger(bctx)
                if (Prefs.getBool(c, Prefs.KEY_ADAPTIVE_ON, true)) {
                    adaptiveLoop?.start()
                }
                refreshTaskStates()
                postScore(profile)
                val s = score.value ?: 0
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
                if (Prefs.getBool(c, Prefs.KEY_OVERLAY_ON, true)) {
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
                session.postValue(SessionState.Boosting(profile.name))
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
    private fun honorLedger(ctx: BoostContext) {
        for (entry in ledger().entries.values) {
            val entries = ctx.journal.entries.filter { it.taskId == entry.taskId }
            when (entry.decision) {
                com.nitroboost.app.core.adaptive.Decision.DROP -> {
                    if (entries.isEmpty()) continue
                    val ok = entries.filter { com.nitroboost.app.core.Journal.restore(it, ctx.executor) }
                    if (ok.isNotEmpty()) {
                        ctx.journal.remove(ok)
                        appendLog("adaptive: reverted dropped task ${entry.taskId}")
                    }
                }
                com.nitroboost.app.core.adaptive.Decision.KEEP -> {
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
                    val ok = entries.filter { com.nitroboost.app.core.Journal.restore(it, ctx.executor) }
                    if (ok.isNotEmpty()) ctx.journal.remove(ok)
                    val r = try {
                        winner.first.apply(ctx)
                    } catch (e: Exception) {
                        null
                    }
                    if (r != null && r.entries.isNotEmpty() && r.status.success) {
                        ctx.journal.add(r.entries)
                        appendLog("adaptive: restored winning ${winner.second}")
                    }
                }
                else -> Unit
            }
        }
    }

    fun stopSession() {
        scope.launch(Dispatchers.IO) {
            try {
                adaptiveLoop?.stop()
                val executor = AndroidExecutor(ctx())
                val bctx = BoostContext(
                    ProfileStore(ctx()).resolve(Prefs.activeProfile(ctx())),
                    executor, journal()
                ) { line -> appendLog(line) }
                engine.restoreAll(bctx)
                setGamePackage(null)
                finishSessionMeasurement()
                refreshTaskStates()
                postScore(
                    ProfileStore(ctx()).resolve(Prefs.activeProfile(ctx()))
                )
                session.postValue(SessionState.Idle)
                try {
                    WidgetProvider.update(ctx())
                } catch (e: Exception) {
                }
            } catch (e: Exception) {
                appendLog("stop failed: ${e.message}")
            }
        }
    }

    fun restoreAll() = stopSession()

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

    private fun postScore(profile: AppProfile) {
        val (s, p) = computeScores(profile)
        score.postValue(s)
        scorePotential.postValue(p)
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
                var foreground: String? = null
                var fgTime = -1L
                for (s in stats) {
                    val pkg = s.packageName ?: continue
                    val last = s.lastTimeUsed
                    val existing = background[pkg]
                    if (existing == null || last > existing) background[pkg] = last
                    if (last > fgTime) {
                        fgTime = last
                        foreground = pkg
                    }
                }
                // refine foreground: what was used in the last 30 seconds
                val recent = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30_000, now)
                var fg30: String? = null
                var fg30t = -1L
                for (s in recent) {
                    val last = s.lastTimeUsed
                    if (last > fg30t) {
                        fg30t = last
                        fg30 = s.packageName
                    }
                }
                foreground = fg30 ?: foreground

                val protectedSet = HashSet<String>()
                protectedSet.addAll(profile.extraProtected)
                protectedSet.addAll(Prefs.protectedList(c))
                protectedSet.add(c.packageName)
                protectedSet.add("android")

                // drop system apps (uid < 10000) — they are never killable
                val userApps = background.keys.filter { pkg ->
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        ai.uid >= 10000
                    } catch (e: Exception) {
                        false
                    }
                }
                val userMap = userApps.associateWith { background[it] ?: 0L }
                BackgroundSelector.select(
                    userMap, foreground, c.packageName, protectedSet, now
                )
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun foregroundPackage(): String? {
        return try {
            val c = ctx()
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val recent = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30_000, now)
            var fg: String? = null
            var best = -1L
            for (s in recent) {
                if (s.lastTimeUsed > best) {
                    best = s.lastTimeUsed
                    fg = s.packageName
                }
            }
            fg
        } catch (e: Exception) {
            null
        }
    }

    fun isPackageRunning(pkg: String): Boolean {
        val fg = foregroundPackage()
        if (fg == pkg) return true
        return try {
            val c = ctx()
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val recent = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
            recent.any { it.packageName == pkg && it.lastTimeUsed > now - 60_000 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Top RAM consumers for the System tab.
     *  - with a bound Shizuku service: real per-process RSS via
     *    `ps -A -o PID,RSS,NAME` (non-blocking call);
     *  - otherwise: recently used apps (Android has no public per-app
     *    memory API without privilege) — the RAM column stays 0.
     */
    fun topProcesses(limit: Int = 20): List<ProcessInfo> {
        try {
            val c = ctx()
            val ex = AndroidExecutor(c)
            val r = ex.shellNonBlocking("ps -A -o PID,RSS,NAME")
            if (r != null && r.ok) {
                val pm = c.packageManager
                val list = r.stdout.lineSequence()
                    .drop(1)
                    .mapNotNull { line ->
                        val p = line.trim().split(Regex("\\s+"))
                        if (p.size < 3) return@mapNotNull null
                        val rssKb = p[1].toLongOrNull() ?: return@mapNotNull null
                        // On Android the ps process name is the package
                        // (possibly with a ":suffix" for child processes).
                        val procName = p.drop(2).joinToString(" ")
                        val pkg = procName.substringBefore(":")
                        if (!pkg.contains(".")) return@mapNotNull null // kernel/system thread
                        val name = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (e: Exception) {
                            pkg
                        }
                        ProcessInfo(pkg, name, rssKb * 1024L, 0L)
                    }
                    .sortedByDescending { it.ramBytes }
                    .take(limit)
                    .toList()
                if (list.size >= 3) return list
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
                val name = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                ProcessInfo(pkg, name, 0L, s.lastTimeUsed)
            }
                .sortedByDescending { it.lastUsed }
                .take(limit)
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
                    try {
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
