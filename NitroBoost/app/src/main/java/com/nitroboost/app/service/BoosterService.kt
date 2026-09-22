package com.nitroboost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.core.BoostContext
import com.nitroboost.app.core.ThermalGuard
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.data.SessionLog
import com.nitroboost.app.platform.AndroidExecutor
import com.nitroboost.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Foreground service that keeps a boost session alive.
 * Responsibilities:
 *  - run the boost (delegated to AppStore for single-source-of-truth);
 *  - thermal watchdog: de-escalate when the device runs hot (safety);
 *  - game watcher: auto-restore when the game closes (conflict-free teardown);
 *  - restart after reboot (START_STICKY).
 */
class BoosterService : Service() {

    companion object {
        const val ACTION_START = "com.nitroboost.app.action.BOOST_START"
        const val ACTION_STOP = "com.nitroboost.app.action.BOOST_STOP"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL_SESSION = "boost_session"

        @Volatile
        var active: Boolean = false

        @Volatile
        var lastScore: Int = 0

        fun start(ctx: Context, profilePkg: String?) {
            if (active) return
            val i = Intent(ctx, BoosterService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE, profilePkg)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, BoosterService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                // service already dead
            }
        }

        fun pushScore(ctx: Context, score: Int) {
            lastScore = score
            try {
                WidgetProvider.update(ctx)
            } catch (e: Exception) {
            }
        }
    }

    private var scope: CoroutineScope? = null
    private val jobs = mutableListOf<Job>()
    private var gamePackage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession()
            return START_NOT_STICKY
        }
        // Android 14+ (target 34): the specialUse type must be passed at runtime.
        val n = buildNotification(getString(R.string.session_running), getString(R.string.session_notify_body))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, n)
        }
        val profilePkg = intent?.getStringExtra(EXTRA_PROFILE)
        scope?.launch {
            startSession(profilePkg)
        }
        return START_STICKY
    }

    private fun startSession(profilePkg: String?) {
        active = true
        val profile = ProfileStore(this).resolve(profilePkg ?: Prefs.activeProfile(this))
        gamePackage = profile.packageName
        AppStore.setGamePackage(gamePackage)
        SessionLog.log(this, "session_start", profile.name)

        // The actual boost runs through the shared AppStore so the UI state
        // and the service always agree.
        AppStore.boost(profile.packageName)

        launchThermalWatch()
        if (Prefs.getBool(this, Prefs.KEY_AUTO_RESTORE, true)) {
            launchGameWatch(profile.packageName)
        }
    }

    private fun endSession() {
        if (!active) return
        active = false
        gamePackage = null
        AppStore.setGamePackage(null)
        SessionLog.log(this, "session_stop")
        jobs.forEach { it.cancel() }
        jobs.clear()
        AppStore.restoreAll()
        try {
            WidgetProvider.update(this)
        } catch (e: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Every 5s: read the thermal status; when the guard asks for de-escalation
     * the offending modules are reverted automatically and journaled.
     */
    private fun launchThermalWatch() {
        val job = scope?.launch {
            while (isActive) {
                delay(5000)
                try {
                    val ex = AndroidExecutor(this@BoosterService)
                    val status = ex.thermalStatus()
                    // Predictive: the adaptive engine's temperature trend can
                    // escalate one tier early, de-escalating BEFORE the OS
                    // thermal status flips and frames actually drop.
                    val eff = maxOf(status, AppStore.effectiveThermalStatus())
                    val drop = ThermalGuard.modulesToDrop(eff)
                    if (drop.isNotEmpty()) {
                        val bctx = BoostContext(
                            ProfileStore(this@BoosterService)
                                .resolve(Prefs.activeProfile(this@BoosterService)),
                            ex,
                            AppStore.journal()
                        ) { line -> AppStore.appendLog(line) }
                        AppStore.engine.deescalate(bctx, drop)
                        SessionLog.log(
                            this@BoosterService,
                            "thermal_deescalate",
                            "status=$status dropped=$drop"
                        )
                    }
                } catch (e: Exception) {
                    // keep watching
                }
            }
        }
        if (job != null) jobs.add(job)
    }

    /**
     * Every 5s: check whether the game is still running. When it has been
     * gone for two consecutive checks (10s) we restore everything — the
     * "restore when the game closes" behaviour, but conflict-free because the
     * journal owns all reverts.
     */
    private fun launchGameWatch(pkg: String) {
        val job = scope?.launch {
            var goneOnce = false
            while (isActive) {
                delay(5000)
                try {
                    val running = AppStore.isPackageRunning(pkg)
                    if (!running) {
                        if (goneOnce) {
                            SessionLog.log(this@BoosterService, "game_exited", pkg)
                            endSession()
                            return@launch
                        }
                        goneOnce = true
                    } else {
                        goneOnce = false
                    }
                } catch (e: Exception) {
                    // keep watching
                }
            }
        }
        if (job != null) jobs.add(job)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_SESSION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SESSION,
                    getString(R.string.session_notify_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BoosterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.session_stop_action), stop)
            .build()
    }

    override fun onDestroy() {
        scope?.cancel()
        active = false
        super.onDestroy()
    }
}
