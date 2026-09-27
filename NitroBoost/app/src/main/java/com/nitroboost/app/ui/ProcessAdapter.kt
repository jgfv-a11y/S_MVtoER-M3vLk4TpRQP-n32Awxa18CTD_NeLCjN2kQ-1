package com.nitroboost.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.R

/** Row adapter for the top-RAM process list (Settings → System & RAM). */
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
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.pkg.text = p.pkg
        holder.ram.text = formatMb(p.ramBytes / (1024 * 1024))
        holder.btnKill.setOnClickListener { onKill(p) }
    }

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else "$mb MB"
}
