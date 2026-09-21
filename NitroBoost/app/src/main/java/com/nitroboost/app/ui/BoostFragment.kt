package com.nitroboost.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.TaskState
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.service.BoosterService

/**
 * The "Optimizations" tab: every task with its live state, plus
 * boost-all / restore-all.
 */
class BoostFragment : Fragment() {

    private lateinit var adapter: TaskAdapter
    private var lastModule: Module? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val c = requireContext()
        return inflater.inflate(R.layout.fragment_boost, container, false).also { root ->
            val rec = root.findViewById<RecyclerView>(R.id.rec_tasks)
            rec.layoutManager = LinearLayoutManager(c)
            adapter = TaskAdapter(
                emptyList(),
                onToggle = { task, enabled ->
                    Prefs.setTaskEnabled(c, task.id, enabled)
                    if (BoosterService.active) {
                        // apply the change to the live session immediately
                        AppStore.boost(Prefs.activeProfile(c))
                    }
                },
                langAr = isArabic()
            )
            rec.adapter = adapter

            root.findViewById<MaterialButton>(R.id.btn_boost_all).setOnClickListener {
                if (BoosterService.active) {
                    BoosterService.stop(c)
                } else {
                    BoosterService.start(c, Prefs.activeProfile(c))
                }
            }
            root.findViewById<MaterialButton>(R.id.btn_restore_all).setOnClickListener {
                AppStore.restoreAll()
                BoosterService.stop(c)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppStore.tasks.observe(viewLifecycleOwner, Observer { states ->
            adapter.submit(states)
            lastModule = null
        })
    }

    private fun isArabic(): Boolean =
        java.util.Locale.getDefault().language == "ar" || Prefs.lang(requireContext()) == "ar"
}

/** Row adapter for the task list. */
class TaskAdapter(
    private var items: List<TaskState>,
    private val langAr: Boolean,
    private val onToggle: (TaskState, Boolean) -> Unit
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.task_title)
        val desc: TextView = root.findViewById(R.id.task_desc)
        val module: TextView = root.findViewById(R.id.task_module)
        val status: TextView = root.findViewById(R.id.task_status)
        val sw: com.google.android.material.materialswitch.MaterialSwitch =
            root.findViewById(R.id.task_switch)
    }

    fun submit(newItems: List<TaskState>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val c = holder.itemView.context
        holder.title.text = if (langAr) t.titleAr else t.titleEn
        holder.desc.text = if (langAr) t.descAr else t.descEn
        holder.module.text = t.module.name
        holder.status.text = when {
            !t.supported -> c.getString(R.string.task_state_unsupported)
            t.applied -> c.getString(R.string.task_state_applied)
            else -> c.getString(R.string.task_state_off)
        }
        val enabled = c.getSharedPreferences("nitroboost_prefs", 0)
            .getBoolean("task_enabled_" + t.id, true)
        holder.sw.setOnCheckedChangeListener(null)
        holder.sw.isChecked = enabled
        holder.sw.isEnabled = t.supported
        holder.sw.setOnCheckedChangeListener { _, isChecked ->
            onToggle(t, isChecked)
        }
    }
}
