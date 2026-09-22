package com.nitroboost.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.SessionLog
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService

/**
 * Settings tab: language, automation, overlay content, Shizuku status,
 * safety center (journal + restore) and about.
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val c = requireContext()
        return inflater.inflate(R.layout.fragment_settings, container, false).also { root ->

            // Language
            root.findViewById<Button>(R.id.btn_lang).setOnClickListener {
                val next = if (Prefs.lang(c) == "ar" || c.resources.configuration.locales[0].language == "ar") "en" else "ar"
                Prefs.setLang(c, next)
                applyLocale(next)
            }

            // Automation
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

            // Shizuku
            root.findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
                val act = activity ?: return@setOnClickListener
                Thread {
                    if (!ShizukuShell.isReady()) {
                        // Shizuku app not running — open it
                        ShizukuShell.openShizukuApp(c)
                    } else if (!ShizukuShell.isPermissionGranted()) {
                        ShizukuShell.requestPermission()
                    }
                    ShizukuShell.ensureBound(c)
                    act.runOnUiThread { refreshShizuku() }
                }.start()
            }
            refreshShizuku()

            // Safety center
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
            updateJournal()
        }
    }

    override fun onResume() {
        super.onResume()
        // The journal changes when sessions run/restore — refresh on every
        // tab visit so the numbers and the entry list are always live.
        updateJournal()
        refreshShizuku()
    }

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

    private fun refreshShizuku() {
        val tv = view?.findViewById<TextView>(R.id.shizuku_status) ?: return
        val c = context ?: return
        val usable = try {
            ShizukuShell.isUsable(c)
        } catch (e: Exception) {
            false
        }
        tv.text = if (usable) {
            getString(R.string.shizuku_ready)
        } else {
            getString(R.string.shizuku_missing)
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
