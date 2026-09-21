package com.nitroboost.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.Module
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import androidx.appcompat.app.AlertDialog

/**
 * Profiles tab: pick the active game profile, edit its modules and
 * create new profiles for any installed app.
 */
class ProfilesFragment : Fragment() {

    private lateinit var adapter: ProfileAdapter
    private lateinit var store: ProfileStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        store = ProfileStore(requireContext())
        val c = requireContext()
        return inflater.inflate(R.layout.fragment_profiles, container, false).also { root ->
            val rec = root.findViewById<RecyclerView>(R.id.rec_profiles)
            rec.layoutManager = LinearLayoutManager(c)
            adapter = ProfileAdapter(
                store.all(),
                Prefs.activeProfile(c) ?: "",
                langAr = c.resources.configuration.locales[0].language == "ar",
                onSelect = { p ->
                    Prefs.setActiveProfile(c, p.packageName)
                    if (com.nitroboost.app.service.BoosterService.active) {
                        AppStore.boost(p.packageName)
                    }
                    adapter.active = p.packageName
                    adapter.notifyDataSetChanged()
                },
                onEdit = { p -> showEditDialog(p) },
                onDelete = { p ->
                    AlertDialog.Builder(c)
                        .setTitle(R.string.profile_delete_title)
                        .setMessage(p.name)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            store.removeCustom(p.packageName)
                            adapter.items = store.all()
                            adapter.notifyDataSetChanged()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                isCustom = { p -> store.customs().any { it.packageName == p.packageName } }
            )
            rec.adapter = adapter

            root.findViewById<MaterialButton>(R.id.btn_add_profile).setOnClickListener {
                showAddDialog()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    private fun showAddDialog() {
        val c = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_profile, null)
        val spinner = view.findViewById<Spinner>(R.id.dialog_game_picker)
        val games = AppStore.installedGames()
        val names = games.map { "${it.name}  (${it.pkg})" }
        spinner.adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, names)

        val dialog = AlertDialog.Builder(c)
            .setTitle(R.string.profile_add_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val idx = spinner.selectedItemPosition
                if (idx in games.indices) {
                    val g = games[idx]
                    val p = AppProfile(packageName = g.pkg, name = g.name).copyProfile()
                    store.upsertCustom(p)
                    Prefs.setActiveProfile(c, p.packageName)
                    adapter.items = store.all()
                    adapter.active = p.packageName
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun showEditDialog(p: AppProfile) {
        val c = requireContext()
        val profile = p.copyProfile()
        val view = layoutInflater.inflate(R.layout.dialog_profile_edit, null)

        val moduleChecks = Map<Module, CheckBox>(
            Module.CPU to view.findViewById(R.id.chk_cpu),
            Module.RAM to view.findViewById(R.id.chk_ram),
            Module.DISPLAY to view.findViewById(R.id.chk_display),
            Module.DND to view.findViewById(R.id.chk_dnd),
            Module.POWER to view.findViewById(R.id.chk_power),
            Module.NETWORK to view.findViewById(R.id.chk_network),
            Module.TWEAKS to view.findViewById(R.id.chk_tweaks),
            Module.GPU to view.findViewById(R.id.chk_gpu),
            Module.THERMAL to view.findViewById(R.id.chk_thermal)
        )
        for ((m, cb) in moduleChecks) {
            cb.isChecked = m in profile.enabledModules
        }
        val swDnd = view.findViewById<MaterialSwitch>(R.id.sw_dnd)
        val swAnim = view.findViewById<MaterialSwitch>(R.id.sw_anim)
        val swRamKill = view.findViewById<MaterialSwitch>(R.id.sw_ram_kill)
        val swThermal = view.findViewById<MaterialSwitch>(R.id.sw_thermal)
        swDnd.isChecked = profile.dnd
        swAnim.isChecked = profile.killAnimations
        swRamKill.isChecked = profile.aggressiveRamClean
        swThermal.isChecked = profile.thermalOverride

        val editDpi = view.findViewById<EditText>(R.id.edit_dpi)
        val editRefresh = view.findViewById<EditText>(R.id.edit_refresh)
        val editGameMode = view.findViewById<EditText>(R.id.edit_game_mode)
        editDpi.setText(profile.dpi.toString())
        editRefresh.setText(profile.refreshRate.toString())
        editGameMode.setText(profile.gameMode.toString())

        AlertDialog.Builder(c)
            .setTitle(profile.name)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                profile.enabledModules.clear()
                for ((m, cb) in moduleChecks) {
                    if (cb.isChecked) profile.enabledModules.add(m)
                }
                profile.dnd = swDnd.isChecked
                profile.killAnimations = swAnim.isChecked
                profile.aggressiveRamClean = swRamKill.isChecked
                profile.thermalOverride = swThermal.isChecked
                profile.dpi = editDpi.text.toString().toIntOrNull() ?: 0
                profile.refreshRate = editRefresh.text.toString().toIntOrNull() ?: 0
                profile.gameMode = editGameMode.text.toString().toIntOrNull() ?: 0
                store.upsertCustom(profile)
                Prefs.setActiveProfile(c, profile.packageName)
                adapter.items = store.all()
                adapter.active = profile.packageName
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** Row adapter for the profile list. */
class ProfileAdapter(
    var items: List<AppProfile>,
    var active: String,
    private val langAr: Boolean,
    private val onSelect: (AppProfile) -> Unit,
    private val onEdit: (AppProfile) -> Unit,
    private val onDelete: (AppProfile) -> Unit,
    private val isCustom: (AppProfile) -> Boolean
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val name: TextView = root.findViewById(R.id.profile_name)
        val pkg: TextView = root.findViewById(R.id.profile_pkg)
        val modules: TextView = root.findViewById(R.id.profile_modules)
        val badge: TextView = root.findViewById(R.id.profile_active)
        val btnEdit: Button = root.findViewById(R.id.btn_edit)
        val btnDelete: Button = root.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.pkg.text = p.packageName
        holder.modules.text = p.enabledModules.joinToString(", ") { it.key }
        val isActive = p.packageName == active
        holder.badge.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.btnDelete.visibility = if (isCustom(p)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onSelect(p) }
        holder.btnEdit.setOnClickListener { onEdit(p) }
        holder.btnDelete.setOnClickListener { onDelete(p) }
    }
}
