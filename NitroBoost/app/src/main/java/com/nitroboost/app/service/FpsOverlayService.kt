package com.nitroboost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.ui.MainActivity
import kotlin.concurrent.Volatile

/**
 * Floating FPS / CPU / RAM / thermal / ping monitor.
 * Draggable; a tap (no drag) opens the app. Content is refreshed from
 * AppStore.monitor once per second.
 */
class FpsOverlayService : Service() {

    companion object {
        private const val CHANNEL_OVERLAY = "overlay"
        private const val TAG_TEXT = "overlay_text"

        @Volatile
        var visible: Boolean = false

        fun start(ctx: Context) {
            if (visible) return
            val i = Intent(ctx, FpsOverlayService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, FpsOverlayService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private var wm: WindowManager? = null
    private var view: View? = null
    private var text: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            updateText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(getString(R.string.overlay_channel_name))
            .setOngoing(true)
            .build()
        // Android 14+ (target 34): the specialUse type must be passed at runtime.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(1002, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, n)
        }
        if (!visible) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (view != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.view_overlay, null)
        val t = v.findViewById<TextView>(R.id.overlay_text)
        // Wrap instead of running off the screen edge — on narrow devices
        // the full line (FPS CPU RAM temp ping) must stay readable.
        t.maxWidth = (300 * resources.displayMetrics.density).toInt()
        view = v
        text = t

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 120
        }

        // Drag to move, tap to open app.
        var downX = 0f
        var downY = 0f
        var moved = false
        // OnTouchListener: first param is the View, second is the MotionEvent.
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    if (moved) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        downX = ev.rawX
                        downY = ev.rawY
                        try {
                            wm?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(v, params)
            visible = true
            handler.post(updater)
        } catch (e: Exception) {
            // overlay permission missing or window error — do not crash
            visible = false
        }
    }

    private fun updateText() {
        val t = text ?: return
        val s = AppStore.monitor.value ?: return
        val c = this
        t.text = buildString {
            if (Prefs.getBool(c, Prefs.KEY_OV_FPS, true)) {
                append("FPS ")
                append(s.fps?.toString() ?: "--")
            }
            if (Prefs.getBool(c, Prefs.KEY_OV_CPU, true)) {
                if (isNotEmpty()) append("  ")
                append(c.getString(R.string.overlay_cpu))
                append(" ")
                append(s.cpuPct)
                append("%")
            }
            if (Prefs.getBool(c, Prefs.KEY_OV_RAM, true)) {
                if (isNotEmpty()) append("  ")
                append(c.getString(R.string.overlay_ram))
                append(" ")
                append((s.ramUsedMb / 1024.0)).append("GB")
            }
            if (Prefs.getBool(c, Prefs.KEY_OV_TEMP, true)) {
                if (isNotEmpty()) append("  ")
                append(c.getString(R.string.overlay_temp))
                append(" ")
                append(
                    s.tempC?.let { "${Math.round(it)}\u00B0" } ?: "--"
                )
            }
            if (Prefs.getBool(c, Prefs.KEY_OV_PING, true)) {
                if (isNotEmpty()) append("  ")
                append(c.getString(R.string.overlay_ping))
                append(" ")
                append(s.pingMs?.toString() ?: "--")
                append("ms")
            }
        }.ifEmpty { "--" }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_OVERLAY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_OVERLAY,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        view?.let {
            try {
                wm?.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
        text = null
        visible = false
        super.onDestroy()
    }
}
