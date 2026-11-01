package com.nitroboost.app.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.Module
import com.nitroboost.app.core.ProfileValidation
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profiles tab: pick the active game profile, edit its modules and
 * create new profiles for any installed app.
 */
class ProfilesFragment : Fragment() {

    private lateinit var adapter: ProfileAdapter
    private lateinit var store: ProfileStore
    private var installedGamesJob: Job? = null
    private var customProfilePackages: Set<String> = emptySet()
    private var pendingImportText: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val c = context ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    c.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(store.exportCustomProfiles().toByteArray(Charsets.UTF_8))
                    } ?: error("output unavailable")
                    true
                }.getOrDefault(false)
            }
            if (isAdded) {
                android.widget.Toast.makeText(
                    c,
                    if (ok) R.string.profile_exported else R.string.profile_exchange_failed,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val c = context ?: return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                        ?: error("input unavailable")
                }.getOrNull()
            }
            if (!isAdded || text == null) {
                if (isAdded) android.widget.Toast.makeText(c, R.string.profile_exchange_failed,
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            handleImport(text)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        store = ProfileStore(requireContext())
        val c = requireContext()
        val customProfiles = store.customs()
        customProfilePackages = customProfiles.mapTo(HashSet()) { it.packageName }
        val profiles = ProfileStore.mergeProfiles(store.builtins(), customProfiles)
        return inflater.inflate(R.layout.fragment_profiles, container, false).also { root ->
            store.lastLoadIssue?.let { issue ->
                android.widget.Toast.makeText(c, issue, android.widget.Toast.LENGTH_LONG).show()
            }
            val rec = root.findViewById<RecyclerView>(R.id.rec_profiles)
            rec.layoutManager = LinearLayoutManager(c)
            adapter = ProfileAdapter(
                profiles,
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
                            if (!store.removeCustom(p.packageName)) {
                                android.widget.Toast.makeText(c, R.string.profile_save_failed,
                                    android.widget.Toast.LENGTH_LONG).show()
                                return@setPositiveButton
                            }
                            val customProfiles = store.customs()
                            customProfilePackages = customProfiles.mapTo(HashSet()) { it.packageName }
                            val remaining = ProfileStore.mergeProfiles(store.builtins(), customProfiles)
                            if (Prefs.activeProfile(c) == p.packageName) {
                                val fallback = remaining.firstOrNull()?.packageName
                                Prefs.setActiveProfile(c, fallback)
                                adapter.active = fallback ?: ""
                            }
                            adapter.items = remaining
                            adapter.notifyDataSetChanged()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                isCustom = { p -> p.packageName in customProfilePackages }
            )
            rec.adapter = adapter

            root.findViewById<MaterialButton>(R.id.btn_add_profile).setOnClickListener {
                showAddDialog()
            }
            root.findViewById<MaterialButton>(R.id.btn_export_profiles).setOnClickListener {
                exportLauncher.launch("nitroboost-profiles-v1.json")
            }
            root.findViewById<MaterialButton>(R.id.btn_import_profiles).setOnClickListener {
                importLauncher.launch(arrayOf("application/json", "text/*"))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    private fun handleImport(text: String, replaceConflicts: Boolean = false) {
        val c = context ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                store.importCustomProfiles(text, replaceConflicts)
            }
            if (!isAdded || view == null) return@launch
            when (result) {
                is com.nitroboost.app.core.ProfileExchange.ImportResult.Accepted -> {
                    refreshProfileList(c)
                    val suffix = if (result.replacedPackages.isEmpty()) "" else
                        " (${result.replacedPackages.size} conflicts replaced)"
                    android.widget.Toast.makeText(
                        c,
                        getString(R.string.profile_imported, result.profiles.size) + suffix,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                is com.nitroboost.app.core.ProfileExchange.ImportResult.Conflict -> {
                    pendingImportText = text
                    AlertDialog.Builder(c)
                        .setTitle(R.string.profile_conflict_title)
                        .setMessage(getString(R.string.profile_conflict_message,
                            result.packages.joinToString(", ")))
                        .setPositiveButton(R.string.profile_replace) { _, _ ->
                            pendingImportText?.let { handleImport(it, replaceConflicts = true) }
                            pendingImportText = null
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                is com.nitroboost.app.core.ProfileExchange.ImportResult.Rejected ->
                    android.widget.Toast.makeText(
                        c,
                        getString(R.string.profile_import_rejected, result.reason),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    private fun refreshProfileList(c: android.content.Context) {
        customProfilePackages = store.customs().mapTo(HashSet()) { it.packageName }
        adapter.items = ProfileStore.mergeProfiles(store.builtins(), store.customs())
        adapter.active = Prefs.activeProfile(c) ?: ""
        adapter.notifyDataSetChanged()
    }

    private fun showAddDialog() {
        if (installedGamesJob?.isActive == true) return
        val c = context?.applicationContext ?: return
        installedGamesJob = viewLifecycleOwner.lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) { AppStore.installedGames() }
            if (!isAdded || !isResumed) return@launch
            if (games.isEmpty()) {
                android.widget.Toast.makeText(c, R.string.no_installed_apps, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val dialogView = layoutInflater.inflate(R.layout.dialog_profile, null)
            val spinner = dialogView.findViewById<Spinner>(R.id.dialog_game_picker)
            val names = games.map { "${it.name}  (${it.pkg})" }
            spinner.adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, names)
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_add_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val idx = spinner.selectedItemPosition
                if (idx !in games.indices) return@setOnClickListener
                val game = games[idx]
                val profile = ProfileValidation.validatedCopy(
                    AppProfile(packageName = game.pkg, name = game.name)
                ) ?: run {
                    android.widget.Toast.makeText(c, R.string.invalid_package_name,
                        android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!store.upsertCustom(profile)) {
                    android.widget.Toast.makeText(c, R.string.profile_save_failed,
                        android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Prefs.setActiveProfile(c, profile.packageName)
                val customProfiles = store.customs()
                customProfilePackages = customProfiles.mapTo(HashSet()) { it.packageName }
                adapter.items = ProfileStore.mergeProfiles(store.builtins(), customProfiles)
                adapter.active = profile.packageName
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
    }

    private fun showEditDialog(p: AppProfile) {
        val c = requireContext()
        val profile = p.copyProfile()
        val view = layoutInflater.inflate(R.layout.dialog_profile_edit, null)

        val moduleChecks = mapOf<Module, CheckBox>(
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
        // The field is retained only to migrate old JSON. Android thermal
        // protection is never user-disableable in 1.24.
        swThermal.isChecked = false
        swThermal.isEnabled = false
        swThermal.contentDescription = getString(R.string.thermal_protection_locked)

        val editDpi = view.findViewById<EditText>(R.id.edit_dpi)
        val editRefresh = view.findViewById<EditText>(R.id.edit_refresh)
        val editGameMode = view.findViewById<EditText>(R.id.edit_game_mode)
        editDpi.setText(profile.dpi.toString())
        editRefresh.setText(profile.refreshRate.toString())
        editGameMode.setText(profile.gameMode.toString())

        val dialog = AlertDialog.Builder(c)
            .setTitle(profile.name)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val dpi = editDpi.text.toString().toIntOrNull()
            val refresh = editRefresh.text.toString().toIntOrNull()
            val gameMode = editGameMode.text.toString().toIntOrNull()
            val dpiValid = dpi != null && ProfileValidation.isValidDpi(dpi)
            val refreshValid = refresh != null && ProfileValidation.isValidRefreshRate(refresh)
            val gameModeValid = gameMode != null && ProfileValidation.isValidGameMode(gameMode)
            editDpi.error = if (dpiValid) null else getString(R.string.profile_dpi_range)
            editRefresh.error = if (refreshValid) null else getString(R.string.profile_refresh_range)
            editGameMode.error = if (gameModeValid) null else getString(R.string.profile_game_mode_range)
            if (!dpiValid || !refreshValid || !gameModeValid) return@setOnClickListener

            profile.enabledModules.clear()
            for ((m, cb) in moduleChecks) {
                if (cb.isChecked) profile.enabledModules.add(m)
            }
            profile.dnd = swDnd.isChecked
            profile.killAnimations = swAnim.isChecked
            profile.aggressiveRamClean = swRamKill.isChecked
            profile.thermalOverride = false
            profile.dpi = dpi!!
            profile.refreshRate = refresh!!
            profile.gameMode = gameMode!!
            val validated = ProfileValidation.validatedCopy(profile)
            if (validated == null || !store.upsertCustom(validated)) {
                android.widget.Toast.makeText(c, R.string.profile_save_failed,
                    android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Prefs.setActiveProfile(c, validated.packageName)
            val customProfiles = store.customs()
            customProfilePackages = customProfiles.mapTo(HashSet()) { it.packageName }
            adapter.items = ProfileStore.mergeProfiles(store.builtins(), customProfiles)
            adapter.active = validated.packageName
            adapter.notifyDataSetChanged()
            dialog.dismiss()
        }
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
        val btnEdit: android.widget.ImageButton = root.findViewById(R.id.btn_edit)
        val btnDelete: android.widget.ImageButton = root.findViewById(R.id.btn_delete)
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
