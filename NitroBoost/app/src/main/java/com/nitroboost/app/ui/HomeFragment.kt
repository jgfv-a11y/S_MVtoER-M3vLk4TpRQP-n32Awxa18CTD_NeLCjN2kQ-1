package com.nitroboost.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.SessionState
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.GameSpaceDetector
import com.nitroboost.app.core.SessionReport
import com.nitroboost.app.core.adaptive.Bottleneck
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.platform.MonitorSnapshot
import com.nitroboost.app.platform.RootShell
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService
import com.nitroboost.app.service.FpsOverlayService

/**
 * The single main screen (v1.5 redesign): pick a game, hit the big boost
 * button, watch the live numbers. Everything else is one tap away.
 */
class HomeFragment : Fragment() {

    private var chips: LinearLayout? = null
    private var scoreValue: TextView? = null
    private var scorePotential: TextView? = null
    private var scoreBar: ProgressBar? = null
    private var sessionChip: TextView? = null
    private var sessionDetail: TextView? = null
    private var statCpu: TextView? = null
    private var statRam: TextView? = null
    private var statTemp: TextView? = null
    private var statFps: TextView? = null
    private var statPing: TextView? = null
    private var btnBoost: MaterialButton? = null
    private var reportCard: View? = null
    private var reportText: TextView? = null
    private var gameSpaceHint: TextView? = null
    private var adaptiveStatus: TextView? = null
    private var adaptiveBottleneck: TextView? = null
    private var adaptiveDecisions: TextView? = null
    private var shizukuCard: View? = null
    private var shizukuStatus: TextView? = null
    private var btnShizuku: MaterialButton? = null

    private val shizukuPoller = object : Runnable {
        override fun run() {
            if (!isResumed || view == null) return
            refreshShizukuCard()
            view?.postDelayed(this, 2_000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view)
        val c = requireContext()

        chips = view.findViewById(R.id.chips_container)
        scoreValue = view.findViewById(R.id.score_value)
        scorePotential = view.findViewById(R.id.score_potential)
        scoreBar = view.findViewById(R.id.score_bar)
        sessionChip = view.findViewById(R.id.session_chip)
        sessionDetail = view.findViewById(R.id.session_detail)
        statCpu = view.findViewById(R.id.stat_cpu_value)
        statRam = view.findViewById(R.id.stat_ram_value)
        statTemp = view.findViewById(R.id.stat_temp_value)
        statFps = view.findViewById(R.id.stat_fps_value)
        statPing = view.findViewById(R.id.stat_ping_value)
        btnBoost = view.findViewById(R.id.btn_boost)
        reportCard = view.findViewById(R.id.report_card)
        reportText = view.findViewById(R.id.report_text)
        gameSpaceHint = view.findViewById(R.id.game_space_hint)
        adaptiveStatus = view.findViewById(R.id.adaptive_status)
        adaptiveBottleneck = view.findViewById(R.id.adaptive_bottleneck)
        adaptiveDecisions = view.findViewById(R.id.adaptive_decisions)
        shizukuCard = view.findViewById(R.id.shizuku_card)
        shizukuStatus = view.findViewById(R.id.shizuku_card_status)
        btnShizuku = view.findViewById(R.id.btn_shizuku)

        buildChips()

        btnBoost?.setOnClickListener {
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
        btnShizuku?.setOnClickListener { onShizukuAction() }

        renderReport(AppStore.loadLastReport())
        detectGameSpace()
        observeAll(viewLifecycleOwner)
    }

    override fun onResume() {
        super.onResume()
        buildChips()
        shizukuPoller.run()
    }

    override fun onPause() {
        super.onPause()
        view?.removeCallbacks(shizukuPoller)
    }

    // ---------------- Game chips ----------------

    private fun buildChips() {
        val container = chips ?: return
        container.removeAllViews()
        val c = requireContext()
        val store = ProfileStore(c)
        val active = Prefs.activeProfile(c) ?: ""
        val inflater = LayoutInflater.from(c)
        for (p in store.all()) {
            val chip = inflater.inflate(R.layout.item_chip, container, false)
            val row = chip.findViewById<LinearLayout>(R.id.chip_root)
            chip.findViewById<TextView>(R.id.chip_name).text = p.name
            chip.findViewById<View>(R.id.chip_active).visibility =
                if (p.packageName == active) View.VISIBLE else View.GONE
            row.setOnClickListener {
                Prefs.setActiveProfile(c, p.packageName)
                if (BoosterService.active) AppStore.boost(p.packageName)
                buildChips()
            }
            val m = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            m.marginEnd = (8 * c.resources.displayMetrics.density).toInt()
            row.layoutParams = m
            container.addView(chip)
        }
    }

    // ---------------- Shizuku / root card ----------------

    private fun refreshShizukuCard() {
        val card = shizukuCard ?: return
        val c = requireContext()
        Thread {
            val state = try {
                ShizukuShell.state(c)
            } catch (e: Exception) {
                ShizukuShell.ShizukuState.NOT_STARTED
            }
            val root = try {
                RootShell.isAvailable()
            } catch (e: Exception) {
                false
            }
            val ready = state == ShizukuShell.ShizukuState.READY || root
            activity?.runOnUiThread {
                val v = view ?: return@runOnUiThread
                if (ready) {
                    card.visibility = View.GONE
                    return@runOnUiThread
                }
                card.visibility = View.VISIBLE
                val (statusText, buttonLabel) = when (state) {
                    ShizukuShell.ShizukuState.NOT_INSTALLED ->
                        getString(R.string.shizuku_state_not_installed) to getString(R.string.btn_open_shizuku)
                    ShizukuShell.ShizukuState.NOT_STARTED ->
                        getString(R.string.shizuku_state_not_started) to getString(R.string.btn_open_shizuku)
                    ShizukuShell.ShizukuState.PENDING_PERMISSION ->
                        getString(R.string.shizuku_state_pending) to getString(R.string.btn_grant)
                    ShizukuShell.ShizukuState.NOT_BOUND ->
                        getString(R.string.shizuku_state_not_bound) to getString(R.string.btn_retry)
                    ShizukuShell.ShizukuState.READY ->
                        getString(R.string.shizuku_ready) to getString(R.string.btn_retry)
                }
                shizukuStatus?.text = statusText
                btnShizuku?.text = buttonLabel
            }
        }.start()
    }

    private fun onShizukuAction() {
        val c = requireContext()
        val act = activity ?: return
        Thread {
            val state = try {
                ShizukuShell.state(c)
            } catch (e: Exception) {
                ShizukuShell.ShizukuState.NOT_STARTED
            }
            when (state) {
                ShizukuShell.ShizukuState.NOT_INSTALLED,
                ShizukuShell.ShizukuState.NOT_STARTED ->
                    ShizukuShell.openShizukuApp(c)
                ShizukuShell.ShizukuState.PENDING_PERMISSION ->
                    ShizukuShell.requestPermission()
                else -> ShizukuShell.ensureBound(c)
            }
            act.runOnUiThread {
                if (isResumed) refreshShizukuCard()
            }
        }.start()
    }

    // ---------------- Game-space hint ----------------

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

    // ---------------- Renderers ----------------

    private fun renderReport(rep: SessionReport?) {
        val card = reportCard ?: return
        if (rep == null) {
            card.visibility = View.GONE
            return
        }
        val lines = mutableListOf(
            if (rep.avgFps != null) {
                buildString {
                    append(getString(R.string.report_fps, rep.avgFps.toString()))
                    rep.deltaFps?.let { append("  ").append(getString(R.string.report_fps_delta, it)) }
                }
            } else getString(R.string.report_fps_none),
            getString(R.string.report_tasks, rep.applied, rep.failed),
            getString(R.string.report_duration, rep.durationSec)
        )
        rep.peakTempC?.let { lines.add(getString(R.string.report_temp, it)) }
        rep.minFps?.let { lines.add(getString(R.string.report_min_fps, it)) }
        if (rep.peakRamMb > 0) lines.add(getString(R.string.report_peak_ram, rep.peakRamMb / 1024))
        rep.minPingMs?.let { lines.add(getString(R.string.report_ping, it)) }
        rep.endBottleneck?.let {
            if (it != Bottleneck.UNKNOWN && it != Bottleneck.NONE) {
                lines.add(
                    getString(
                        R.string.report_end_bottleneck,
                        when (it) {
                            Bottleneck.CPU -> "CPU"
                            Bottleneck.GPU -> "GPU"
                            Bottleneck.MEMORY -> getString(R.string.bottleneck_memory)
                            Bottleneck.NETWORK -> getString(R.string.bottleneck_network)
                            Bottleneck.THERMAL -> getString(R.string.bottleneck_thermal)
                            else -> it.name
                        }
                    )
                )
            }
        }
        reportText?.text = lines.joinToString("\n")
        card.visibility = View.VISIBLE
    }

    private fun renderAdaptive(ui: AppStore.AdaptiveUi) {
        val v = view ?: return
        adaptiveStatus?.text = when {
            !ui.enabled -> getString(R.string.adaptive_off)
            ui.pausedReason != null ->
                getString(R.string.adaptive_paused, ui.pausedReason)
            ui.phase == "done" -> getString(R.string.adaptive_done)
            ui.phase.startsWith("trial:") ->
                getString(R.string.adaptive_trial, ui.phase.removePrefix("trial:"))
            else -> getString(R.string.adaptive_idle)
        }
        val eta = if (ui.etaMinutes > 0)
            "  · " + getString(R.string.adaptive_eta, ui.etaMinutes) else ""
        adaptiveStatus?.text = adaptiveStatus?.text?.toString() + eta
        adaptiveBottleneck?.text = buildString {
            append(getString(R.string.adaptive_bottleneck))
            append(": ")
            append(
                when (ui.bottleneck) {
                    Bottleneck.UNKNOWN -> getString(R.string.bottleneck_unknown)
                    Bottleneck.NONE -> getString(R.string.bottleneck_none)
                    Bottleneck.CPU -> "CPU"
                    Bottleneck.GPU -> "GPU"
                    Bottleneck.MEMORY -> getString(R.string.bottleneck_memory)
                    Bottleneck.NETWORK -> getString(R.string.bottleneck_network)
                    Bottleneck.THERMAL -> getString(R.string.bottleneck_thermal)
                }
            )
            if (ui.effectiveThermal > ui.osThermal) {
                append("  ")
                append(getString(R.string.thermal_predicted))
            }
        }
        val dec = adaptiveDecisions ?: return
        if (ui.decisions.isEmpty()) {
            dec.visibility = View.GONE
            return
        }
        val sb = StringBuilder()
        for (d in ui.decisions) {
            sb.append(d.taskTitle).append(": ")
            when (d.decision) {
                Decision.KEEP -> sb.append(
                    getString(R.string.decision_keep,
                        d.meanDelta?.let { String.format("%+.1f", it) } ?: "--", d.pairs)
                )
                Decision.DROP -> sb.append(
                    getString(R.string.decision_drop,
                        d.meanDelta?.let { String.format("%.1f", it) } ?: "--", d.pairs)
                )
                Decision.NEUTRAL -> sb.append(getString(R.string.decision_neutral, d.pairs))
                Decision.NEEDS_MORE -> sb.append(getString(R.string.decision_pending, d.pairs, 8))
            }
            sb.append('\n')
        }
        dec.text = sb.toString().trimEnd()
        dec.visibility = View.VISIBLE
    }

    // ---------------- Observers ----------------

    private fun observeAll(owner: LifecycleOwner) {
        AppStore.score.observe(owner, Observer { s ->
            scoreValue?.text = s.toString()
            scoreBar?.progress = s ?: 0
        })
        AppStore.scorePotential.observe(owner, Observer { p ->
            scorePotential?.text = "/ $p"
        })
        AppStore.monitor.observe(owner, Observer { s ->
            val snap = s ?: MonitorSnapshot.EMPTY
            statCpu?.text = "${snap.cpuPct}%"
            statRam?.text = "${snap.ramUsedMb / 1024}GB"
            statTemp?.text = snap.tempC?.let { "${Math.round(it)}\u00B0" } ?: "--"
            statFps?.text = snap.fps?.toString() ?: "--"
            statPing?.text = snap.pingMs?.let { "${it}ms" } ?: "--"
        })
        AppStore.session.observe(owner, Observer { st ->
            when (st) {
                is SessionState.Idle -> {
                    sessionChip?.text = getString(R.string.session_idle)
                    sessionDetail?.text = ""
                    btnBoost?.text = getString(R.string.btn_boost_start)
                }
                is SessionState.Boosting -> {
                    sessionChip?.text = getString(R.string.session_boosting)
                    sessionDetail?.text = st.profileName
                    btnBoost?.text = getString(R.string.btn_boost_running)
                    btnBoost?.isEnabled = false
                }
                is SessionState.Boosted -> {
                    sessionChip?.text = getString(R.string.session_active)
                    sessionDetail?.text = st.profileName
                    btnBoost?.text = getString(R.string.btn_boost_stop)
                    btnBoost?.isEnabled = true
                }
            }
            btnBoost?.isEnabled = st !is SessionState.Boosting
        })
        AppStore.report.observe(owner, Observer { renderReport(it) })
        AppStore.adaptiveUi.observe(owner, Observer { renderAdaptive(it) })
    }
}
