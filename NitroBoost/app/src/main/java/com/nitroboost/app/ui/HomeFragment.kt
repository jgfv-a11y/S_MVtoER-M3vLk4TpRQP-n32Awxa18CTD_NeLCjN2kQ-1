package com.nitroboost.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.BuildConfig
import com.nitroboost.app.R
import com.nitroboost.app.SessionState
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.FpsDisplayCache
import com.nitroboost.app.core.GameSpaceDetector
import com.nitroboost.app.core.SessionReport
import com.nitroboost.app.core.adaptive.Decision
import com.nitroboost.app.core.performance.EvidenceDomain
import com.nitroboost.app.core.performance.EvidenceScope
import com.nitroboost.app.core.performance.PerformanceAdviceCode
import com.nitroboost.app.core.performance.PerformanceAdviceResolver
import com.nitroboost.app.core.performance.PerformanceEvidence
import com.nitroboost.app.core.performance.PerformanceState
import com.nitroboost.app.core.performance.PerformanceStateResult
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
    private val fpsDisplayCache = FpsDisplayCache()
    private var btnBoost: MaterialButton? = null
    private var reportCard: View? = null
    private var reportText: TextView? = null
    private var gameSpaceHint: TextView? = null
    private var adaptiveStatus: TextView? = null
    private var adaptiveBottleneck: TextView? = null
    private var adaptiveEvidence: TextView? = null
    private var adaptiveAdviceTitle: TextView? = null
    private var adaptiveAdvice: TextView? = null
    private var adaptiveDecisions: TextView? = null
    private var shizukuCard: View? = null
    private var shizukuStatus: TextView? = null
    private var btnShizuku: MaterialButton? = null
    private var levelChips: List<TextView> = emptyList()

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
        super.onViewCreated(view, savedInstanceState)
        val c = requireContext()
        view.findViewById<TextView>(R.id.home_version).text =
            getString(R.string.home_version, BuildConfig.VERSION_NAME)

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
        adaptiveEvidence = view.findViewById(R.id.adaptive_evidence)
        adaptiveAdviceTitle = view.findViewById(R.id.adaptive_advice_title)
        adaptiveAdvice = view.findViewById(R.id.adaptive_advice)
        adaptiveDecisions = view.findViewById(R.id.adaptive_decisions)
        shizukuCard = view.findViewById(R.id.shizuku_card)
        shizukuStatus = view.findViewById(R.id.shizuku_card_status)
        btnShizuku = view.findViewById(R.id.btn_shizuku)
        levelChips = listOf(
            view.findViewById(R.id.lv_chip_1),
            view.findViewById(R.id.lv_chip_2),
            view.findViewById(R.id.lv_chip_3)
        )

        buildChips()
        setupLevelChips()

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
        if (isHidden) return
        shizukuPoller.run()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            view?.removeCallbacks(shizukuPoller)
        } else if (isResumed) {
            refreshVisibleHome()
        }
    }

    private fun refreshVisibleHome() {
        val chipScroll = chips?.parent as? android.widget.HorizontalScrollView
        val chipScrollX = chipScroll?.scrollX ?: 0
        buildChips()
        if (chipScroll != null && chipScrollX > 0) {
            chipScroll.post { chipScroll.scrollTo(chipScrollX, 0) }
        }
        renderLevelChips()
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

    // ---------------- Boost level chips (v1.5) ----------------

    private fun setupLevelChips() {
        val c = requireContext()
        levelChips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                Prefs.putInt(c, Prefs.KEY_BOOST_LEVEL, i + 1)
                renderLevelChips()
            }
        }
        renderLevelChips()
    }

    private fun renderLevelChips() {
        val c = requireContext()
        val sel = Prefs.boostLevel(c)
        val accent = ContextCompat.getColor(c, R.color.nb_accent)
        val normal = ContextCompat.getColor(c, R.color.nb_text)
        levelChips.forEachIndexed { i, chip ->
            val on = i + 1 == sel
            chip.setTextColor(if (on) accent else normal)
            chip.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
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
        ui.performanceState?.let { result ->
            val summary = getString(
                R.string.performance_state_summary,
                performanceStateLabel(result.state),
                (result.confidence.score * 100.0).toInt(),
                (result.dataQuality.score * 100.0).toInt()
            )
            adaptiveBottleneck?.text = if (ui.effectiveThermal > ui.osThermal) {
                "$summary · ${getString(R.string.thermal_predicted)}"
            } else summary
            adaptiveEvidence?.text = evidenceSummary(result)
            adaptiveEvidence?.visibility = View.VISIBLE
            adaptiveAdviceTitle?.visibility = View.VISIBLE
            adaptiveAdvice?.setText(performanceAdviceLabel(result.state))
            adaptiveAdvice?.visibility = View.VISIBLE
        } ?: run {
            adaptiveBottleneck?.text = getString(R.string.performance_state_waiting)
            adaptiveEvidence?.visibility = View.GONE
            adaptiveAdviceTitle?.visibility = View.GONE
            adaptiveAdvice?.visibility = View.GONE
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
                Decision.MORE_DATA, Decision.NEEDS_MORE -> sb.append(getString(R.string.decision_pending, d.pairs, 8))
            }
            sb.append('\n')
        }
        dec.text = sb.toString().trimEnd()
        dec.visibility = View.VISIBLE
    }

    private fun performanceStateLabel(state: PerformanceState): String = getString(
        when (state) {
            PerformanceState.CPU_BOUND -> R.string.performance_state_cpu_bound
            PerformanceState.GPU_BOUND -> R.string.performance_state_gpu_bound
            PerformanceState.MEMORY_BOUND -> R.string.performance_state_memory_bound
            PerformanceState.THERMAL_BOUND -> R.string.performance_state_thermal_bound
            PerformanceState.NETWORK_BOUND -> R.string.performance_state_network_bound
            PerformanceState.DISPLAY_BOUND -> R.string.performance_state_display_bound
            PerformanceState.MIXED_BOUND -> R.string.performance_state_mixed_bound
            PerformanceState.HEALTHY -> R.string.performance_state_healthy
            PerformanceState.UNKNOWN -> R.string.performance_state_unknown
        }
    )

    private fun performanceAdviceLabel(state: PerformanceState): Int = when (
        PerformanceAdviceResolver.resolve(state)
    ) {
        PerformanceAdviceCode.REVIEW_CPU_HEAVY_GAME_SETTINGS -> R.string.performance_advice_cpu
        PerformanceAdviceCode.REVIEW_IN_GAME_GRAPHICS -> R.string.performance_advice_gpu
        PerformanceAdviceCode.REVIEW_UNUSED_APPS -> R.string.performance_advice_memory
        PerformanceAdviceCode.COOL_DEVICE_AND_REDUCE_BOOST -> R.string.performance_advice_thermal
        PerformanceAdviceCode.VERIFY_GAME_NETWORK_PATH -> R.string.performance_advice_network
        PerformanceAdviceCode.MATCH_TARGET_TO_REFRESH -> R.string.performance_advice_display
        PerformanceAdviceCode.CHANGE_ONE_SETTING_AT_A_TIME -> R.string.performance_advice_mixed
        PerformanceAdviceCode.KEEP_CURRENT_SETTINGS -> R.string.performance_advice_healthy
        PerformanceAdviceCode.WAIT_FOR_RELIABLE_DATA -> R.string.performance_advice_unknown
    }

    private fun evidenceSummary(result: PerformanceStateResult): String {
        val domains = when (result.state) {
            PerformanceState.CPU_BOUND -> setOf(EvidenceDomain.CPU, EvidenceDomain.FRAME_PACING)
            PerformanceState.GPU_BOUND -> setOf(EvidenceDomain.GPU, EvidenceDomain.FRAME_PACING)
            PerformanceState.MEMORY_BOUND -> setOf(EvidenceDomain.MEMORY, EvidenceDomain.FRAME_PACING)
            PerformanceState.THERMAL_BOUND -> setOf(EvidenceDomain.THERMAL, EvidenceDomain.FRAME_PACING)
            PerformanceState.NETWORK_BOUND -> setOf(EvidenceDomain.NETWORK)
            PerformanceState.DISPLAY_BOUND -> setOf(EvidenceDomain.DISPLAY, EvidenceDomain.FRAME_PACING)
            PerformanceState.MIXED_BOUND -> EvidenceDomain.entries.toSet()
            PerformanceState.HEALTHY -> setOf(EvidenceDomain.FRAME_PACING)
            PerformanceState.UNKNOWN -> EvidenceDomain.entries.toSet()
        }
        val supportedEvidence = result.evidence
            .filter { it.domain in domains && it.supportsClassification }
        val selectedEvidence = if (result.state == PerformanceState.MIXED_BOUND) {
            supportedEvidence.distinctBy { it.domain }.take(3)
        } else supportedEvidence.take(3)
        val details = selectedEvidence.map(::formatEvidence)
        return if (details.isEmpty()) {
            getString(R.string.performance_evidence_none)
        } else {
            getString(R.string.performance_evidence_summary, details.joinToString(" · "))
        }
    }

    private fun formatEvidence(evidence: PerformanceEvidence): String {
        val domain = getString(
            when (evidence.domain) {
                EvidenceDomain.CPU -> R.string.performance_evidence_cpu
                EvidenceDomain.GPU -> R.string.performance_evidence_gpu
                EvidenceDomain.MEMORY -> R.string.performance_evidence_memory
                EvidenceDomain.THERMAL -> R.string.performance_evidence_thermal
                EvidenceDomain.NETWORK -> R.string.performance_evidence_network
                EvidenceDomain.DISPLAY -> R.string.performance_evidence_display
                EvidenceDomain.FRAME_PACING -> R.string.performance_evidence_frame
            }
        )
        if (evidence.code == "ANDROID_LOW_MEMORY_FLAG") {
            return withEvidenceMetadata(getString(R.string.performance_evidence_low_memory), evidence)
        }
        if (evidence.code == "CONFIGURED_TARGET_ABOVE_ACTIVE_REFRESH") {
            val value = getString(
                R.string.performance_evidence_display_cap,
                evidence.observedValue?.toInt() ?: 0,
                evidence.comparisonValue?.toInt() ?: 0
            )
            return withEvidenceMetadata(value, evidence)
        }
        if (evidence.code == "MEASURED_FRAME_CADENCE_NEAR_ACTIVE_REFRESH") {
            val value = getString(
                R.string.performance_evidence_display_cadence,
                evidence.observedValue?.let { formatNumber(it) } ?: "--",
                evidence.comparisonValue?.let { formatNumber(it) } ?: "--"
            )
            return withEvidenceMetadata(value, evidence)
        }
        val value = evidence.observedValue ?: return withEvidenceMetadata(domain, evidence)
        val formatted = when (evidence.unit) {
            "%", "% below target", "% (estimated)" -> formatNumber(value) + "%"
            "°C" -> formatNumber(value) + "°C"
            "°C/min" -> formatNumber(value) + "°C/min"
            "ms" -> formatNumber(value) + "ms"
            "FPS" -> formatNumber(value) + "fps"
            "Hz from measured vsync interval" -> formatNumber(value) + "Hz"
            "kHz" -> formatNumber(value) + "kHz"
            "bytes" -> formatNumber(value / (1024.0 * 1024.0)) + "MB"
            "segments/s" -> formatNumber(value) + "/s"
            "status" -> "#${value.toInt()}"
            else -> formatNumber(value)
        }
        return withEvidenceMetadata("$domain $formatted", evidence)
    }

    private fun withEvidenceMetadata(text: String, evidence: PerformanceEvidence): String = getString(
        R.string.performance_evidence_sample_scope,
        text,
        getString(
            when (evidence.scope) {
                EvidenceScope.SYSTEM_WIDE -> R.string.performance_evidence_scope_system
                EvidenceScope.GPU_TELEMETRY -> R.string.performance_evidence_scope_gpu
                EvidenceScope.THERMAL_ZONE -> R.string.performance_evidence_scope_thermal_zone
                EvidenceScope.SYSTEM_NETWORK -> R.string.performance_evidence_scope_system_network
                EvidenceScope.TCP_PROBE -> R.string.performance_evidence_scope_tcp
                EvidenceScope.DISPLAY_MODE -> R.string.performance_evidence_scope_display
                EvidenceScope.GFXINFO -> R.string.performance_evidence_scope_gfxinfo
            }
        ),
        evidence.sampleCount.coerceAtLeast(0)
    )

    private fun formatNumber(value: Double): String = String.format(
        requireContext().resources.configuration.locales[0], "%.1f", value
    )

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
            statCpu?.text = if (snap.cpuSampleAvailable) "${snap.cpuPct}%" else "--"
            statRam?.text = if (snap.ramSampleAvailable) "${snap.ramUsedMb / 1024}GB" else "--"
            statTemp?.text = snap.tempC?.let { "${Math.round(it)}\u00B0" } ?: "--"
            statFps?.text = fpsDisplayCache.value(
                snap.fps, snap.gamePackage, android.os.SystemClock.elapsedRealtime()
            )?.toString() ?: "--"
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
