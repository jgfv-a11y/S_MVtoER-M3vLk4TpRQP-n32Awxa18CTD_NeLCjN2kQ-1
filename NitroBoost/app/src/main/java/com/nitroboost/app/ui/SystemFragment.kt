package com.nitroboost.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.platform.AndroidExecutor
import com.nitroboost.app.service.BoosterService
import java.io.File

/**
 * System tab: top RAM consumers (kill with protection), storage info and
 * the app protection list used by the RAM killer.
 */
class SystemFragment : Fragment() {

    private lateinit var adapter: ProcessAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val c = requireContext()
        return inflater.inflate(R.layout.fragment_system, container, false).also { root ->
            val rec = root.findViewById<RecyclerView>(R.id.rec_processes)
            rec.layoutManager = LinearLayoutManager(c)
            adapter = ProcessAdapter(
                emptyList(),
                onKill = { info -> confirmKill(info) }
            )
            rec.adapter = adapter

            root.findViewById<MaterialButton>(R.id.btn_refresh_procs).setOnClickListener {
                refresh()
            }
            root.findViewById<Button>(R.id.btn_edit_protected).setOnClickListener {
                showProtectedDialog()
            }
            refresh()
        }
    }

    private fun refresh() {
        val c = requireContext()
        val protectedList = Prefs.protectedList(c).toSet()
        val self = c.packageName

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val procs = AppStore.topProcesses(25)
            val filtered = procs.filter { it.pkg != self && it.pkg !in protectedList }
            adapter.items = filtered
            adapter.notifyDataSetChanged()
        }

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
        val tvFree = view?.findViewById<TextView>(R.id.storage_free)
        val tvTotal = view?.findViewById<TextView>(R.id.storage_total)
        tvFree?.text = formatBytes(free)
        tvTotal?.text = formatBytes(total)

        val tvProt = view?.findViewById<TextView>(R.id.protected_list)
        tvProt?.text = Prefs.protectedList(c).joinToString(", ").ifEmpty {
            getString(R.string.protected_none)
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
                    // Shizuku missing: explain instead of failing silently
                    com.google.android.material.snackbar.Snackbar
                        .make(requireView(), R.string.kill_needs_shizuku, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show()
                }
                refresh()
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
                refresh()
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
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}

/** Row adapter for the process list. */
class ProcessAdapter(
    var items: List<AppStore.ProcessInfo>,
    private val onKill: (AppStore.ProcessInfo) -> Unit
) : RecyclerView.Adapter<ProcessAdapter.VH>() {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val name: TextView = root.findViewById(R.id.proc_name)
        val pkg: TextView = root.findViewById(R.id.proc_pkg)
        val ram: TextView = root.findViewById(R.id.proc_ram)
        val btnKill: MaterialButton = root.findViewById(R.id.btn_kill)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_process, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val c = holder.itemView.context
        holder.name.text = p.name
        holder.pkg.text = p.pkg
        holder.ram.text = formatMb(p.ramBytes / (1024 * 1024))
        holder.btnKill.setOnClickListener { onKill(p) }
    }

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else "$mb MB"
}
