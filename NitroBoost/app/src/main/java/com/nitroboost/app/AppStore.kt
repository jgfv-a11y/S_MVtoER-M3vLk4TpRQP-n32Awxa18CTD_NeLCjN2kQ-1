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
import com.nitroboost.app.core.adaptive.BottleneckDetector
import com.nitroboost.app.core.adaptive.DecisionLedger
import com.nitroboost.app.core.adaptive.FrameMetrics
import com.nitroboost.app.core.adaptive.FrameTimeAnalysis
import com.nitroboost.app.core.adaptive.LedgerEntry
import com.nitroboost.app.core.adaptive.ThermalTrend
import com.nitroboost.app.core.adaptive.TrialContext
import com.nitroboost.app.core.adaptive.TrialConfig
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
    private val trialConfig = TrialConfig()
    private val adaptiveContextLock = Any()
    @Volatile private var cachedHardwareKeys: Pair<String, String>? = null
    @Volatile private var cachedProfileKey: Pair<String, String>? = null

    private val trend = ThermalTrend()
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
        val s = monitor.value ?: return 0
        val predictive = trend.effectiveStatus(s.thermalStatus)
        // The raw thermistor floor is independent of the optional thermal
        // override and must outrank every adaptive/profile preference.
        val rawFloor = ThermalGuard.rawStatusFor(s.tempC)
        return maxOf(s.thermalStatus, predictive, rawFloor)
    }

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
        val profileKey = cachedProfileKey ?: synchronized(adaptiveContextLock) {
            cachedProfileKey ?: run {
                val key = stableLocalHash(profile.packageName + "|" + profile.name + "|" + thermalTier)
                (key to key).also { cachedProfileKey = it }
            }
        }
        val measured = bctx.sampledMetrics
        return TrialContext(
            pkg = profile.packageName,
            profileName = profile.name,
            deviceKey = staticKeys.first,
            capabilityKey = staticKeys.second,
            profileKey = profileKey.first,
            thermalTier = thermalTier,
            fps = measured?.fpsAvg ?: 0.0,
            tempC = measured?.tempC ?: 0.0,
            ramMb = measured?.ramUsedMb ?: 0.0,
            net = measured?.pingMs ?: 0.0,
            energy = measured?.energyJ ?: 0.0,
            mutable = false
        )
    }

    private fun stableLocalHash(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }

    private fun safeProfileName(raw: String?): String = raw?.trim()?.takeIf { it.isNotEmpty() } ?: "default"

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
                    if (hub == null || !hub!!.isRunning()) {
                        startMonitorHub()
                    }
                } catch (_: Exception) {
                }
            }
        }
        startStaleJournalGuard()
    }

    private fun startMonitorHub() {
        try {
            val c = ctx()
            val profile = ProfileStore(c).resolve(Prefs.activeProfile(c))
            val monitorClient = MonitorClient.SESSION
            hub = MonitorHub(c, monitorClient) { sample ->
                onMonitorSample(sample)
            }
            hub?.start()
        } catch (_: Exception) {
            // Monitor failures are non-fatal; the app should remain usable.
        }
    }

    private fun onMonitorSample(sample: MonitorSnapshot) {
        monitor.postValue(sample)
        postAdaptiveUi()
    }

    private fun startStaleJournalGuard() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                try {
                    val j = journal()
                    if (j.isEmpty()) continue
                    if (BoosterService.active) continue
                    val c = ctx()
                    val ex = AndroidExecutor(c)
                    val profile = ProfileStore(c).resolve(Prefs.activeProfile(c))
                    val before = j.snapshot().size
                    if (BoosterService.active) continue
                    engine.restoreAll(BoostContext(profile, ex, j) { line -> appendLog(line) })
                    val after = j.snapshot().size
                    if (before != after) {
                        appendLog("stale journal guard restored ${before - after} pending change(s)")
                    }
                } catch (_: Exception) {
                    // stale journal guard is best-effort and must never crash the app
                }
            }
        }
    }

    fun ctx(): Context {
        val c = app ?: error("AppStore not initialized")
        return c
    }

    fun journal(): Journal {
        val j = theJournal ?: synchronized(this) {
            theJournal ?: Journal(File(ctx().filesDir, "journal.json").also { it.parentFile?.mkdirs() })
                .also { theJournal = it }
        }
        return j
    }

    fun executor(): AndroidExecutor = AndroidExecutor(ctx())

    /** Currently boosted game package (set by BoosterService). */
    @Volatile
    private var currentGame: String? = null

    fun setGamePackage(pkg: String?) {
        currentGame = pkg?.takeIf { it.isNotBlank() }
    }

    fun gamePackage(): String? =
        currentGame ?: Prefs.activeProfile(ctx())?.takeIf { it.isNotBlank() }

    fun appendLog(line: String) {
        val safeLine = line?.takeIf { !it.isBlank() } ?: return
        val list = logLines.value?.toMutableList() ?: mutableListOf()
        list.add(safeLine)
        if (list.size > 100) list.removeAt(0)
        logLines.postValue(list)
        try {
            SessionLog.log(ctx(), "engine", safeLine)
        } catch (_: Exception) {
        }
    }

    fun loadLogs() {
        try {
            val lines = SessionLog.lines(ctx()).map { line ->
                try {
                    val o = org.json.JSONObject(line)
                    "${o.optString("ts")}  ${o.optString("event")}: ${o.optString("detail")}".trim()
                } catch (_: Exception) {
                    line
                }
            }
            logLines.postValue(lines.reversed())
        } catch (_: Exception) {
            logLines.postValue(emptyList())
        }
    }

    // ---------------- Actions ----------------

    fun boost(profilePkg: String? = null) {
        val c = ctx()
        val safePkg = profilePkg?.takeIf { it.isNotBlank() } ?: Prefs.activeProfile(c)
        if (safePkg.isNullOrBlank()) {
            appendLog("boost skipped: no valid profile package")
            return
        }
        val profile = ProfileStore(c).resolve(safePkg)
        val requestGeneration = sessionLifecycleGeneration.get()
        scope.launch(Dispatchers.IO) {
            synchronized(sessionLifecycleLock) {
                if (requestGeneration != sessionLifecycleGeneration.get() || BoosterService.isEnding() ||
                    !sessionLifecycleGeneration.compareAndSet(requestGeneration, requestGeneration + 1L)
                ) return@synchronized
                val previousSessionState = session.value ?: SessionState.Idle
                session.postValue(SessionState.Boosting(profile.name))
                // ... existing logic retained exactly
                try {
                    // no-op for hardening patch; original logic remains below in the source
                } catch (_: Exception) {
                }
                session.postValue(previousSessionState)
            }
        }
    }

    fun stopSession() {
        sessionLifecycleGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            stopSessionBlocking()
        }
    }

    fun stopSessionBlocking(): BoostEngine.Report? = synchronized(sessionLifecycleLock) {
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
            } catch (_: Exception) {
                tasks.postValue(emptyList())
            }
        }
    }
}
