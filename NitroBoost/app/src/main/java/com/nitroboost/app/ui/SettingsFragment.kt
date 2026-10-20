package com.nitroboost.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.SessionLog
import com.nitroboost.app.core.ShellInput
import com.nitroboost.app.core.SystemRendererController
import com.nitroboost.app.platform.AndroidExecutor
import com.nitroboost.app.platform.RootShell
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab (v1.5 redesign): Shizuku/Root guidance, floating monitor,
 * adaptive engine, system & RAM (top processes + protection), the live
 * modification journal, preferences and about — one scrollable page.
 */
class SettingsFragment : Fragment() {

    private lateinit var procAdapter: ProcessAdapter
    private var systemRefreshJob: Job? = null
    private var shizukuRefreshJob: Job? = null
    private var rendererRefreshJob: Job? = null
    private var shizukuActionPending = false
    private var rendererActionPending = false

    private data class RendererUiState(
        val apiSupported: Boolean,
        val vulkanHardware: Boolean,
        val privileged: Boolean,
        val currentProperty: String?,
        val hasSavedOriginal: Boolean,
        val journalBlocked: Boolean
    )

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

            // Experimental Android HWUI renderer control (manual and opt-in).
            root.findViewById<MaterialButton>(R.id.btn_renderer_vulkan).setOnClickListener {
                confirmRendererChange(SystemRendererController.Target.VULKAN)
            }
            root.findViewById<MaterialButton>(R.id.btn_renderer_opengl).setOnClickListener {
                confirmRendererChange(SystemRendererController.Target.OPENGL)
            }
            root.findViewById<MaterialButton>(R.id.btn_renderer_restore).setOnClickListener {
                confirmRendererRestore()
            }

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

            // About
            root.findViewById<TextView>(R.id.about_version).text =
                getString(R.string.about_version_format, versionString())
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        updateJournal()
        refreshShizuku()
        refreshRenderer()
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
        if (shizukuActionPending) return
        shizukuActionPending = true
        view?.findViewById<Button>(R.id.btn_shizuku)?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    try {
                        ShizukuShell.state(c)
                    } catch (_: Exception) {
                        ShizukuShell.ShizukuState.NOT_STARTED
                    }
                }
                when (state) {
                    ShizukuShell.ShizukuState.NOT_INSTALLED,
                    ShizukuShell.ShizukuState.NOT_STARTED -> ShizukuShell.openShizukuApp(c)
                    ShizukuShell.ShizukuState.PENDING_PERMISSION -> ShizukuShell.requestPermission()
                    else -> withContext(Dispatchers.IO) { ShizukuShell.ensureBound(c) }
                }
                if (isResumed) {
                    refreshShizuku()
                    refreshRenderer()
                    AppStore.refreshTaskStates()
                }
            } finally {
                shizukuActionPending = false
                view?.findViewById<Button>(R.id.btn_shizuku)?.isEnabled = true
            }
        }
    }

    private fun refreshShizuku() {
        if (shizukuRefreshJob?.isActive == true) return
        val c = context?.applicationContext ?: return
        shizukuRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                val rootAvail = try {
                    RootShell.isAvailable()
                } catch (_: Exception) {
                    false
                }
                val state = try {
                    ShizukuShell.state(c)
                } catch (_: Exception) {
                    if (rootAvail) ShizukuShell.ShizukuState.READY
                    else ShizukuShell.ShizukuState.NOT_STARTED
                }
                rootAvail to state
            }
            val (rootAvail, state) = status
            val tv = view?.findViewById<TextView>(R.id.shizuku_status) ?: return@launch
            tv.text = when {
                state == ShizukuShell.ShizukuState.READY -> getString(R.string.shizuku_ready)
                rootAvail -> getString(R.string.shizuku_via_root)
                state == ShizukuShell.ShizukuState.NOT_INSTALLED ->
                    getString(R.string.shizuku_state_not_installed)
                state == ShizukuShell.ShizukuState.NOT_STARTED ->
                    getString(R.string.shizuku_state_not_started)
                state == ShizukuShell.ShizukuState.PENDING_PERMISSION ->
                    getString(R.string.shizuku_state_pending)
                else -> getString(R.string.shizuku_state_not_bound)
            }
            tv.setTextColor(
                if (state == ShizukuShell.ShizukuState.READY || rootAvail)
                    0xFF00E676.toInt() else 0xFFFFB74D.toInt()
            )
        }
    }

    // ---------------- Experimental Android HWUI renderer ----------------

    private fun confirmRendererChange(target: SystemRendererController.Target) {
        val title = if (target == SystemRendererController.Target.VULKAN) {
            R.string.btn_renderer_vulkan
        } else {
            R.string.btn_renderer_opengl
        }
        val message = if (target == SystemRendererController.Target.VULKAN) {
            R.string.renderer_confirm_vulkan
        } else {
            R.string.renderer_confirm_opengl
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.renderer_confirm_title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ -> startRendererAction(target, restore = false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRendererRestore() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.btn_renderer_restore)
            .setMessage(R.string.renderer_confirm_restore)
            .setPositiveButton(R.string.btn_renderer_restore) { _, _ ->
                startRendererAction(target = null, restore = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startRendererAction(target: SystemRendererController.Target?, restore: Boolean) {
        if (rendererActionPending) return
        val appContext = requireContext().applicationContext
        rendererActionPending = true
        setRendererButtonsEnabled(false, false, false)
        view?.findViewById<TextView>(R.id.renderer_status)
            ?.setText(R.string.renderer_status_loading)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val executor = AndroidExecutor(appContext)
                    val journal = AppStore.rendererJournal()
                    if (restore) {
                        SystemRendererController.restoreOriginal(executor, journal)
                    } else {
                        SystemRendererController.setRenderer(
                            executor = executor,
                            journal = journal,
                            target = target ?: SystemRendererController.Target.OPENGL,
                            androidApi = Build.VERSION.SDK_INT,
                            hasVulkanHardwareFeature = hasVulkanHardwareFeature(appContext)
                        )
                    }
                }
                view?.let { root ->
                    com.google.android.material.snackbar.Snackbar
                        .make(root, rendererResultMessage(result),
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                view?.let { root ->
                    com.google.android.material.snackbar.Snackbar
                        .make(root, R.string.renderer_result_apply_failed,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show()
                }
            } finally {
                rendererActionPending = false
                if (view != null && isAdded) refreshRenderer()
            }
        }
    }

    private fun rendererResultMessage(result: SystemRendererController.Result): String {
        val value = result.current?.ifEmpty { "default" } ?: "?"
        return when (result.code) {
            SystemRendererController.Code.APPLIED -> getString(R.string.renderer_result_applied, value)
            SystemRendererController.Code.NO_CHANGE -> getString(R.string.renderer_result_no_change, value)
            SystemRendererController.Code.RESTORED -> getString(R.string.renderer_result_restored, value)
            SystemRendererController.Code.NO_SAVED_CHANGE -> getString(R.string.renderer_result_no_saved)
            SystemRendererController.Code.UNSUPPORTED_ANDROID -> getString(R.string.renderer_result_api)
            SystemRendererController.Code.VULKAN_UNAVAILABLE -> getString(R.string.renderer_result_vulkan_feature)
            SystemRendererController.Code.PRIVILEGE_REQUIRED -> getString(R.string.renderer_result_privilege)
            SystemRendererController.Code.PROPERTY_UNREADABLE -> getString(R.string.renderer_result_unreadable)
            SystemRendererController.Code.UNKNOWN_PROPERTY_VALUE -> getString(R.string.renderer_result_unknown)
            SystemRendererController.Code.JOURNAL_BLOCKED -> getString(R.string.renderer_result_journal)
            SystemRendererController.Code.APPLY_FAILED -> getString(R.string.renderer_result_apply_failed)
            SystemRendererController.Code.RESTORE_FAILED -> getString(R.string.renderer_result_restore_failed)
        }
    }

    private fun refreshRenderer() {
        if (rendererActionPending || rendererRefreshJob?.isActive == true) return
        val appContext = context?.applicationContext ?: return
        rendererRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val executor = AndroidExecutor(appContext)
                val journal = AppStore.rendererJournal()
                val privileged = try {
                    executor.privileged
                } catch (_: Exception) {
                    false
                }
                val current = if (privileged) {
                    try {
                        executor.systemPropertyGet(SystemRendererController.PROPERTY)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                val hasSaved = journal.snapshot().any {
                    it.taskId == SystemRendererController.TASK_ID &&
                        it.kind == com.nitroboost.app.core.JournalEntry.Kind.SYSTEM_PROPERTY &&
                        it.key == SystemRendererController.PROPERTY
                }
                RendererUiState(
                    apiSupported = Build.VERSION.SDK_INT >= SystemRendererController.MIN_ANDROID_API,
                    vulkanHardware = hasVulkanHardwareFeature(appContext),
                    privileged = privileged,
                    currentProperty = current,
                    hasSavedOriginal = hasSaved,
                    journalBlocked = journal.recoveryBlocked
                )
            }
            val status = view?.findViewById<TextView>(R.id.renderer_status) ?: return@launch
            val lines = mutableListOf<String>()
            lines += when {
                !state.apiSupported -> getString(R.string.renderer_status_api)
                !state.privileged -> getString(R.string.renderer_status_privilege)
                state.currentProperty == null -> getString(R.string.renderer_status_unreadable)
                SystemRendererController.describe(state.currentProperty) == "default" ->
                    getString(R.string.renderer_status_default)
                SystemRendererController.describe(state.currentProperty) == "vulkan-requested" ->
                    getString(R.string.renderer_status_vulkan)
                SystemRendererController.describe(state.currentProperty) == "opengl-requested" ->
                    getString(R.string.renderer_status_opengl)
                else -> getString(R.string.renderer_status_custom, state.currentProperty.orEmpty())
            }
            if (state.apiSupported && !state.vulkanHardware) {
                lines += getString(R.string.renderer_status_vulkan_feature)
            }
            if (state.hasSavedOriginal) lines += getString(R.string.renderer_status_backup)
            if (state.journalBlocked) lines += getString(R.string.renderer_status_journal_blocked)
            status.text = lines.joinToString("\n")

            val propertyKnown = state.currentProperty?.let { ShellInput.isHwuiRendererValue(it) } == true
            val canChange = !rendererActionPending && state.apiSupported && state.privileged &&
                propertyKnown && !state.journalBlocked
            setRendererButtonsEnabled(
                vulkan = canChange && state.vulkanHardware,
                openGl = canChange,
                restore = !rendererActionPending && state.privileged && state.hasSavedOriginal &&
                    !state.journalBlocked
            )
        }
    }

    private fun setRendererButtonsEnabled(vulkan: Boolean, openGl: Boolean, restore: Boolean) {
        view?.findViewById<MaterialButton>(R.id.btn_renderer_vulkan)?.isEnabled = vulkan
        view?.findViewById<MaterialButton>(R.id.btn_renderer_opengl)?.isEnabled = openGl
        view?.findViewById<MaterialButton>(R.id.btn_renderer_restore)?.isEnabled = restore
    }

    private fun hasVulkanHardwareFeature(context: android.content.Context): Boolean = try {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    } catch (_: Exception) {
        false
    }

    // ---------------- System & RAM ----------------

    private fun refreshSystem() {
        if (systemRefreshJob?.isActive == true) return
        val c = context?.applicationContext ?: return
        val self = c.packageName
        systemRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val procs = AppStore.topProcesses(15)
                val (free, total) = try {
                    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                    (stat.availableBlocksLong * stat.blockSizeLong) to
                        (stat.blockCountLong * stat.blockSizeLong)
                } catch (_: Exception) {
                    0L to 0L
                }
                Triple(procs, free, total)
            }
            val v = view ?: return@launch
            val protectedList = Prefs.protectedList(c).toSet()
            procAdapter.items = snapshot.first.filter {
                it.pkg != self && it.pkg !in protectedList && ShellInput.isPackageName(it.pkg)
            }
            procAdapter.notifyDataSetChanged()
            v.findViewById<TextView>(R.id.storage_info).text =
                getString(R.string.storage_line, formatBytes(snapshot.second), formatBytes(snapshot.third))
            v.findViewById<TextView>(R.id.protected_list).text =
                protectedList.joinToString(", ").ifEmpty { getString(R.string.protected_none) }
        }
    }

    private fun confirmKill(info: AppStore.ProcessInfo) {
        val c = requireContext()
        if (!ShellInput.isPackageName(info.pkg) || info.pkg == c.packageName ||
            info.pkg in Prefs.protectedList(c)
        ) {
            android.widget.Toast.makeText(c, R.string.invalid_package_name, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(c)
            .setTitle(R.string.kill_confirm_title)
            .setMessage("${info.name}\n${info.pkg}")
            .setPositiveButton(R.string.kill) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        AndroidExecutor(c.applicationContext)
                            .shell("am force-stop \"${info.pkg}\"").ok
                    }
                    if (!ok && view != null) {
                        com.google.android.material.snackbar.Snackbar
                            .make(requireView(), R.string.kill_needs_shizuku,
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .show()
                    }
                    refreshSystem()
                }
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
        val dialog = AlertDialog.Builder(c)
            .setTitle(R.string.protected_title)
            .setMessage(R.string.protected_hint)
            .setView(edit)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val list = edit.text.toString()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (list.any { !ShellInput.isPackageName(it) }) {
                edit.error = getString(R.string.invalid_package_name)
                return@setOnClickListener
            }
            edit.error = null
            Prefs.setProtectedList(c, list)
            dialog.dismiss()
            refreshSystem()
        }
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
