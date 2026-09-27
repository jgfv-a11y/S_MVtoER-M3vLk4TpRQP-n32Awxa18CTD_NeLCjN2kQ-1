package com.nitroboost.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.SessionLog
import com.nitroboost.app.platform.AndroidExecutor
import com.nitroboost.app.platform.RootShell
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService

/**
 * Settings tab (v1.5 redesign): Shizuku/Root guidance, floating monitor,
 * adaptive engine, system & RAM (top processes + protection), the live
 * modification journal, preferences and about — one scrollable page.
 */
class SettingsFragment : Fragment() {

    private lateinit var procAdapter: ProcessAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val c = requireContext()
        return inflater.inflate(R.layout.fragment_settings, container, false).also { root ->
            // Language
            root.findViewById<Button>(R.id.btn_lang).setOnClickListener {
                val next = if (Prefs.lang(c) == "ar" ||
                    c.resources.configuration.locales[0].language == "ar"
                ) "en" else "ar"
                Prefs.setLang(c, next)
                applyLocale(next)
            }

            // Automation + engine
            bindSwitch(root, R.id.sw_auto_boost, Prefs.KEY_AUTO_BOOST, false)
            bindSwitch(root, R.id.sw_auto_restore, Prefs.KEY_AUTO_RESTORE, true)
            bindSwitch(root, R.id.sw_boot, Prefs.KEY_START_ON_BOOT, false)
            bindSwitch(root, R.id.sw_adaptive, Prefs.KEY_ADAPTIVE_ON, true)

            // Overlay
            bindSwitch(root, R.id.sw_overlay, Prefs.KEY_OVERLAY_ON, false) { v ->
                if (v) {
                    if (Settings.canDrawOverlays(c)) {
                        com.nitroboost.app.service.FpsOverlayService.start(c)
                    } else {
                        PermissionGuide.show(requireActivity())
                    }
                } else {
                    com.nitroboost.app.service.FpsOverlayService.stop(c)
                }
            }
            bindSwitch(root, R.id.sw_ov_fps, Prefs.KEY_OV_FPS, true)
            bindSwitch(root, R.id.sw_ov_cpu, Prefs.KEY_OV_CPU, true)
            bindSwitch(root, R.id.sw_ov_ram, Prefs.KEY_OV_RAM, true)
            bindSwitch(root, R.id.sw_ov_temp, Prefs.KEY_OV_TEMP, true)
            bindSwitch(root, R.id.sw_ov_ping, Prefs.KEY_OV_PING, true)

            // Shizuku / root
            root.findViewById<Button>(R.id.btn_shizuku).setOnClickListener { onShizukuTap(c) }
            refreshShizuku()

            // Journal
            root.findViewById<MaterialButton>(R.id.btn_restore_all).setOnClickListener {
                AlertDialog.Builder(c)
                    .setTitle(R.string.restore_confirm_title)
                    .setMessage(R.string.restore_confirm_msg)
                    .setPositiveButton(R.string.restore) { _, _ ->
                        AppStore.restoreAll()
                        BoosterService.stop(c)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            root.findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
                SessionLog.clear(c)
                AppStore.loadLogs()
                updateJournal()
            }

            // System & RAM
            val rec = root.findViewById<RecyclerView>(R.id.rec_processes)
            rec.layoutManager = LinearLayoutManager(c)
            procAdapter = ProcessAdapter(emptyList()) { info -> confirmKill(info) }
            rec.adapter = procAdapter
            root.findViewById<Button>(R.id.btn_refresh_procs).setOnClickListener { refreshSystem() }
            root.findViewById<Button>(R.id.btn_edit_protected).setOnClickListener { showProtectedDialog() }
            refreshSystem()

            // About
            root.findViewById<TextView>(R.id.about_version).text =
                getString(R.string.about_version_format, versionString())

            updateJournal()
        }
    }

    override fun onResume() {
        super.onResume()
        updateJournal()
        refreshShizuku()
        refreshSystem()
    }

    private fun versionString(): String =
        try {
            val pm = requireContext().packageManager
            val ai = pm.getPackageInfo(requireContext().packageName, 0)
            "${ai.versionName} (${ai.versionCode})"
        } catch (e: Exception) {
            "?"
        }

    private fun onShizukuTap(c: android.content.Context) {
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
                if (isResumed) {
                    refreshShizuku()
                    AppStore.refreshTaskStates()
                }
            }
        }.start()
    }

    private fun refreshShizuku() {
        val tv = view?.findViewById<TextView>(R.id.shizuku_status) ?: return
        val c = context ?: return
        val rootAvail = try {
            RootShell.isAvailable()
        } catch (e: Exception) {
            false
        }
        val state = try {
            ShizukuShell.state(c)
        } catch (e: Exception) {
            if (rootAvail) ShizukuShell.ShizukuState.READY else ShizukuShell.ShizukuState.NOT_STARTED
        }
        tv.text = when {
            state == ShizukuShell.ShizukuState.READY ->
                getString(R.string.shizuku_ready)
            rootAvail ->
                getString(R.string.shizuku_via_root)
            state == ShizukuShell.ShizukuState.NOT_INSTALLED ->
                getString(R.string.shizuku_state_not_installed)
            state == ShizukuShell.ShizukuState.NOT_STARTED ->
                getString(R.string.shizuku_state_not_started)
            state == ShizukuShell.ShizukuState.PENDING_PERMISSION ->
                getString(R.string.shizuku_state_pending)
            else ->
                getString(R.string.shizuku_state_not_bound)
        }
        tv.setTextColor(
            if (state == ShizukuShell.ShizukuState.READY || rootAvail)
                0xFF00E676.toInt() else 0xFFFFB74D.toInt()
        )
    }

    // ---------------- System & RAM ----------------

    private fun refreshSystem() {
        val c = requireContext()
        val protectedList = Prefs.protectedList(c).toSet()
        val self = c.packageName

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val v = view ?: return@post
            val procs = AppStore.topProcesses(15)
            procAdapter.items = procs.filter { it.pkg != self && it.pkg !in protectedList }
            procAdapter.notifyDataSetChanged()

            val free = try {
                val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                st.availableBlocksLong * st.blockSizeLong
            } catch (e: Exception) {
                0L
            }
            val total = try {
                val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                st.blockCountLong * st.blockSizeLong
            } catch (e: Exception) {
                0L
            }
            v.findViewById<TextView>(R.id.storage_info).text =
                getString(R.string.storage_line, formatBytes(free), formatBytes(total))

            v.findViewById<TextView>(R.id.protected_list).text =
                Prefs.protectedList(c).joinToString(", ").ifEmpty {
                    getString(R.string.protected_none)
                }
        }
    }

    private fun confirmKill(info: AppStore.ProcessInfo) {
        val c = requireContext()
        AlertDialog.Builder(c)
            .setTitle(R.string.kill_confirm_title)
            .setMessage("${info.name}\n${info.pkg}")
            .setPositiveButton(R.string.kill) { _, _ ->
                val ex = AndroidExecutor(c)
                val r = ex.shell("am force-stop \"${info.pkg}\"")
                if (!r.ok) {
                    com.google.android.material.snackbar.Snackbar
                        .make(requireView(), R.string.kill_needs_shizuku,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show()
                }
                refreshSystem()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProtectedDialog() {
        val c = requireContext()
        val edit = EditText(c).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Prefs.protectedList(c).joinToString("\n"))
        }
        AlertDialog.Builder(c)
            .setTitle(R.string.protected_title)
            .setMessage(R.string.protected_hint)
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                val list = edit.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                Prefs.setProtectedList(c, list)
                refreshSystem()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatBytes(b: Long): String {
        if (b <= 0) return "0 GB"
        var v = b.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format("%.1f %s", v, units[i])
    }

    // ---------------- Journal ----------------

    private fun updateJournal() {
        val tv = view?.findViewById<TextView>(R.id.journal_count) ?: return
        val list = view?.findViewById<TextView>(R.id.journal_list) ?: return
        val entries = AppStore.journal().entries
        tv.text = getString(R.string.journal_entries, entries.size)
        if (entries.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        val sb = StringBuilder()
        entries.takeLast(14).asReversed().forEach { e ->
            sb.append("\u2022 ")
            sb.append(e.taskId)
            sb.append("  ")
            val key = e.key.substringAfterLast('/').substringAfterLast(':')
            sb.append(if (key.length > 26) e.key.substringAfterLast('/') else key)
            if (!e.oldValue.isNullOrEmpty() && e.oldValue != "none" && e.oldValue != "absent") {
                sb.append(": ")
                sb.append(shorten(e.oldValue))
                sb.append(" \u2192 ")
                sb.append(shorten(e.newValue ?: ""))
            }
            sb.append('\n')
        }
        list.text = sb.toString()
        list.visibility = View.VISIBLE
    }

    private fun shorten(v: String): String =
        if (v.length > 18) v.take(15) + "\u2026" else v

    // ---------------- Misc ----------------

    private fun bindSwitch(
        root: View,
        id: Int,
        key: String,
        def: Boolean,
        onChange: ((Boolean) -> Unit)? = null
    ) {
        val c = requireContext()
        val sw = root.findViewById<MaterialSwitch>(id)
        sw.isChecked = Prefs.getBool(c, key, def)
        sw.setOnCheckedChangeListener { _, v ->
            Prefs.setBool(c, key, v)
            onChange?.invoke(v)
        }
    }

    private fun applyLocale(lang: String) {
        val locale = if (lang == "ar") java.util.Locale("ar") else java.util.Locale.ENGLISH
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        val metrics = resources.displayMetrics
        config.setLocale(locale)
        resources.updateConfiguration(config, metrics)
        val intent = Intent(activity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        activity?.finish()
    }
}
