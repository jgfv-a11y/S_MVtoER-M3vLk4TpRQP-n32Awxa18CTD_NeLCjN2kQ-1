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

    private var hub: MonitorHub? = null
    private var theJournal: Journal? = null

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
        var applied = 0
        var failed = 0
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
            start, end, fps, temp, ping, ram, applied, failed, prev
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
                deltaFps = if (j.isNull("deltaFps")) null else j.optInt("deltaFps")
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
    }

    private fun collectSample(s: MonitorSnapshot) {
        if (sessStart == 0L) return
        synchronized(measLock) {
            s.fps?.let { sessFps.add(it) }
            s.tempC?.let { sessTemp.add(it.toInt()) }
            s.pingMs?.let { sessPing.add(it) }
            if (s.ramUsedMb > 0) sessRam.add(s.ramUsedMb.toInt())
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
                if (BoosterService.active) BoosterService.pushScore(c, computeScore(profile))
                refreshTaskStates()
                val s = computeScore(profile)
                score.postValue(s)
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
            } catch (e: Exception) {
                appendLog("boost crashed: ${e.message}")
                session.postValue(SessionState.Boosting(profile.name))
            }
        }
    }

    fun stopSession() {
        scope.launch(Dispatchers.IO) {
            try {
                val executor = AndroidExecutor(ctx())
                val bctx = BoostContext(
                    ProfileStore(ctx()).resolve(Prefs.activeProfile(ctx())),
                    executor, journal()
                ) { line -> appendLog(line) }
                engine.restoreAll(bctx)
                setGamePackage(null)
                finishSessionMeasurement()
                refreshTaskStates()
                score.postValue(computeScore(
                    ProfileStore(ctx()).resolve(Prefs.activeProfile(ctx()))
                ))
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

    private fun computeScore(profile: AppProfile): Int {
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
            return ScoreEngine.compute(
                ScoreEngine.Inputs(applied, applicable, thermal, ramFree, storage)
            )
        } catch (e: Exception) {
            return 0
        }
    }

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
