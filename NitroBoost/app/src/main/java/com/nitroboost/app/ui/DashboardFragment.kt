package com.nitroboost.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.SessionState
import com.nitroboost.app.core.GameSpaceDetector
import com.nitroboost.app.core.SessionReport
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.platform.MonitorSnapshot
import com.nitroboost.app.service.BoosterService
import com.nitroboost.app.service.FpsOverlayService

class DashboardFragment : Fragment() {

    private var scoreValue: TextView? = null
    private var scoreBar: ProgressBar? = null
    private var sessionStatus: TextView? = null
    private var sessionDetail: TextView? = null
    private var statCpu: TextView? = null
    private var statRam: TextView? = null
    private var statTemp: TextView? = null
    private var statBat: TextView? = null
    private var statFps: TextView? = null
    private var statPing: TextView? = null
    private var btnBoost: MaterialButton? = null
    private var reportText: TextView? = null
    private var gameSpaceHint: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val c = requireContext()

        scoreValue = view.findViewById(R.id.score_value)
        scoreBar = view.findViewById(R.id.score_bar)
        sessionStatus = view.findViewById(R.id.session_status)
        sessionDetail = view.findViewById(R.id.session_detail)
        statCpu = view.findViewById(R.id.stat_cpu_value)
        statRam = view.findViewById(R.id.stat_ram_value)
        statTemp = view.findViewById(R.id.stat_temp_value)
        statBat = view.findViewById(R.id.stat_bat_value)
        statFps = view.findViewById(R.id.stat_fps_value)
        statPing = view.findViewById(R.id.stat_ping_value)
        btnBoost = view.findViewById(R.id.btn_boost)
        reportText = view.findViewById(R.id.report_text)
        gameSpaceHint = view.findViewById(R.id.game_space_hint)

        view.findViewById<MaterialButton>(R.id.btn_boost).setOnClickListener {
            if (BoosterService.active) {
                BoosterService.stop(c)
            } else {
                BoosterService.start(c, Prefs.activeProfile(c))
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_restore).setOnClickListener {
            AppStore.restoreAll()
            BoosterService.stop(c)
        }
        view.findViewById<MaterialButton>(R.id.btn_overlay).setOnClickListener {
            if (FpsOverlayService.visible) {
                FpsOverlayService.stop(c)
                Prefs.setBool(c, Prefs.KEY_OVERLAY_ON, false)
            } else {
                Prefs.setBool(c, Prefs.KEY_OVERLAY_ON, true)
                if (android.provider.Settings.canDrawOverlays(c)) {
                    FpsOverlayService.start(c)
                } else {
                    PermissionGuide.show(requireActivity())
                }
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_perms).setOnClickListener {
            PermissionGuide.show(requireActivity())
        }

        renderReport(AppStore.loadLastReport())
        detectGameSpace()
        observeAll(viewLifecycleOwner)
    }

    /** OEM game-space apps (detection only — the user decides what to enable). */
    private fun detectGameSpace() {
        val c = requireContext()
        Thread {
            val found = GameSpaceDetector.detect(c)
            val tv = gameSpaceHint ?: return@Thread
            if (found.isEmpty()) {
                tv.post { tv.visibility = View.GONE }
            } else {
                val names = found.joinToString(", ") { it.second }
                tv.post {
                    tv.text = getString(R.string.game_space_hint, names)
                    tv.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun renderReport(rep: SessionReport?) {
        val tv = reportText ?: return
        if (rep == null) {
            tv.visibility = View.GONE
            return
        }
        val lines = mutableListOf(
            getString(R.string.report_title),
            if (rep.avgFps != null) {
                buildString {
                    append(getString(R.string.report_fps, rep.avgFps.toString()))
                    rep.deltaFps?.let { append(" ").append(getString(R.string.report_fps_delta, it)) }
                }
            } else {
                getString(R.string.report_fps_none)
            },
            getString(R.string.report_tasks, rep.applied, rep.failed),
            getString(R.string.report_duration, rep.durationSec)
        )
        rep.peakTempC?.let { lines.add(getString(R.string.report_temp, it)) }
        rep.minPingMs?.let { lines.add(getString(R.string.report_ping, it)) }
        tv.text = lines.joinToString("\n")
        tv.visibility = View.VISIBLE
    }

    private fun observeAll(owner: LifecycleOwner) {
        AppStore.score.observe(owner, Observer { s ->
            scoreValue?.text = s.toString()
            scoreBar?.progress = s ?: 0
        })
        AppStore.monitor.observe(owner, Observer { s ->
            val snap = s ?: MonitorSnapshot.EMPTY
            statCpu?.text = "${snap.cpuPct}%"
            statRam?.text = "${snap.ramUsedMb / 1024}GB"
            statTemp?.text = snap.tempC?.let { "${Math.round(it)}\u00B0C" } ?: "--"
            val charging = if (snap.charging) " \u26A1" else ""
            statBat?.text = "${snap.batteryPct}%$charging"
            statFps?.text = snap.fps?.toString() ?: "--"
            statPing?.text = snap.pingMs?.let { "${it}ms" } ?: "--"
        })
        AppStore.session.observe(owner, Observer { st ->
            when (st) {
                is SessionState.Idle -> {
                    sessionStatus?.text = getString(R.string.session_idle)
                    sessionDetail?.text = getString(R.string.session_idle_hint)
                    btnBoost?.text = getString(R.string.btn_boost_start)
                }
                is SessionState.Boosting -> {
                    sessionStatus?.text = getString(R.string.session_boosting)
                    sessionDetail?.text = st.profileName
                    btnBoost?.text = getString(R.string.btn_boost_running)
                    btnBoost?.isEnabled = false
                }
                is SessionState.Boosted -> {
                    sessionStatus?.text = getString(R.string.session_active)
                    sessionDetail?.text = st.profileName
                    btnBoost?.text = getString(R.string.btn_boost_stop)
                    btnBoost?.isEnabled = true
                }
            }
            btnBoost?.isEnabled = st !is SessionState.Boosting
        })
        AppStore.report.observe(owner, Observer { rep ->
            renderReport(rep)
        })
    }
}
