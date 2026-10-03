package com.nitroboost.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService
import com.nitroboost.app.core.MonitorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var autoBoostJob: Job? = null
    private var selectedTabId: Int = R.id.nav_home
    private var onboardingShownThisLaunch = false
    private var onboardingDialogOpen = false
    private var awaitingOnboardingPermissionReturn = false

    private data class OnboardingStatus(
        val missingItems: List<PermissionGuide.Item>,
        val notificationsPending: Boolean,
        val complete: Boolean
    )

    private data class PermissionRow(val label: String, val launch: () -> Unit)

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            handleOnboardingPermissionReturn()
        }

    private companion object {
        const val STATE_SELECTED_TAB = "main.selected_tab"
        const val STATE_ONBOARDING_SHOWN = "main.onboarding_shown"
        const val STATE_ONBOARDING_DIALOG_OPEN = "main.onboarding_dialog_open"
        const val STATE_ONBOARDING_PERMISSION_PENDING = "main.onboarding_permission_pending"

        const val TAG_HOME = "main.tab.home"
        const val TAG_BOOST = "main.tab.boost"
        const val TAG_PROFILES = "main.tab.profiles"
        const val TAG_SETTINGS = "main.tab.settings"
    }

    /** Shizuku is opened at most once per launch — the poller keeps the rest automatic. */
    private var shizukuOpenedThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedTabId = savedInstanceState?.getInt(STATE_SELECTED_TAB)
            ?.takeIf(::isNavigationItem) ?: R.id.nav_home
        onboardingShownThisLaunch = savedInstanceState?.getBoolean(STATE_ONBOARDING_SHOWN) ?: false
        onboardingDialogOpen = savedInstanceState?.getBoolean(STATE_ONBOARDING_DIALOG_OPEN) ?: false
        awaitingOnboardingPermissionReturn =
            savedInstanceState?.getBoolean(STATE_ONBOARDING_PERMISSION_PENDING) ?: false

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.selectedItemId = selectedTabId
        nav.setOnItemSelectedListener { item -> showTab(item.itemId) }
        // FragmentManager restores tagged fragments (and their view hierarchy) after
        // configuration/process recreation. Reuse them instead of replacing them.
        showTab(selectedTabId)

        // Launched from the home widget with a boost hint.
        if (intent?.getBooleanExtra("boost", false) == true) {
            onBoostTap()
        }

        maybeShowOnboarding()
        autoShizuku()

        // The overlay can be auto-started by a session; if the permission is
        // missing we explain exactly where to enable it.
        AppStore.overlayPermNeeded.observe(this, Observer { needed ->
            if (needed != true) return@Observer
            AppStore.overlayPermNeeded.value = null
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_perm_title)
                .setMessage(R.string.overlay_perm_msg)
                .setPositiveButton(R.string.overlay_perm_open) { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        } catch (e2: Exception) {
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }

    private fun isNavigationItem(itemId: Int): Boolean =
        itemId == R.id.nav_home || itemId == R.id.nav_boost ||
            itemId == R.id.nav_profiles || itemId == R.id.nav_settings

    private fun tabTag(itemId: Int): String = when (itemId) {
        R.id.nav_boost -> TAG_BOOST
        R.id.nav_profiles -> TAG_PROFILES
        R.id.nav_settings -> TAG_SETTINGS
        else -> TAG_HOME
    }

    private fun tabClass(itemId: Int): Class<out Fragment> = when (itemId) {
        R.id.nav_boost -> BoostFragment::class.java
        R.id.nav_profiles -> ProfilesFragment::class.java
        R.id.nav_settings -> SettingsFragment::class.java
        else -> HomeFragment::class.java
    }

    private fun newTabFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_boost -> BoostFragment()
        R.id.nav_profiles -> ProfilesFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun isTabFragment(fragment: Fragment): Boolean =
        fragment.id == R.id.container &&
            (fragment is HomeFragment || fragment is BoostFragment ||
                fragment is ProfilesFragment || fragment is SettingsFragment)

    /** Adds each tab once, then only hides/shows the retained instances. */
    private fun showTab(itemId: Int): Boolean {
        if (supportFragmentManager.isStateSaved) return false
        val safeItemId = if (isNavigationItem(itemId)) itemId else R.id.nav_home
        val manager = supportFragmentManager
        val expectedClass = tabClass(safeItemId)
        val target = manager.findFragmentByTag(tabTag(safeItemId))
            ?.takeIf { expectedClass.isInstance(it) }
            ?: manager.fragments.firstOrNull {
                expectedClass.isInstance(it) && it.id == R.id.container && !it.isRemoving
            }
            // The class lookup also reuses an untagged Fragment restored from
            // versions that used replace(container, fragment) without a tag.
            ?: newTabFragment(safeItemId)

        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.filter(::isTabFragment).forEach { fragment ->
            if (fragment !== target && !fragment.isHidden) transaction.hide(fragment)
        }
        if (!target.isAdded) {
            transaction.add(R.id.container, target, tabTag(safeItemId))
        } else if (target.isHidden) {
            transaction.show(target)
        }
        transaction.setPrimaryNavigationFragment(target)
        transaction.commitNow()
        selectedTabId = safeItemId
        return true
    }

    override fun onStart() {
        super.onStart()
        AppStore.setMonitorClient(MonitorClient.UI, true)
    }

    override fun onStop() {
        AppStore.setMonitorClient(MonitorClient.UI, false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingOnboardingPermissionReturn) {
            handleOnboardingPermissionReturn()
        } else {
            updateOnboardingCompletion()
        }
        AppStore.refreshTaskStates()
        maybeAutoBoost()
        autoShizuku()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTabId)
        outState.putBoolean(STATE_ONBOARDING_SHOWN, onboardingShownThisLaunch)
        outState.putBoolean(STATE_ONBOARDING_DIALOG_OPEN, onboardingDialogOpen)
        outState.putBoolean(
            STATE_ONBOARDING_PERMISSION_PENDING,
            awaitingOnboardingPermissionReturn
        )
        super.onSaveInstanceState(outState)
    }

    /**
     * Automatic Shizuku flow:
     *  - not installed          -> nothing to do (root fallback may still work);
     *  - installed, not running -> open it ONCE (the user presses Start there,
     *                              the poller picks up the binder afterwards);
     *  - running, not granted   -> dispatch Shizuku's grant dialog;
     *  - granted                -> bind the user service (auto retry).
     */
    private fun autoShizuku() {
        Thread {
            try {
                if (!ShizukuShell.isInstalled(this)) return@Thread
                if (!ShizukuShell.isReady()) {
                    if (!shizukuOpenedThisLaunch) {
                        shizukuOpenedThisLaunch = true
                        runOnUiThread {
                            try {
                                ShizukuShell.openShizukuApp(this)
                            } catch (e: Exception) {
                            }
                        }
                    }
                    return@Thread
                }
                if (!ShizukuShell.isPermissionGranted()) {
                    runOnUiThread {
                        ShizukuShell.requestPermission()
                    }
                    return@Thread
                }
                ShizukuShell.ensureBound(applicationContext)
            } catch (e: Exception) {
                // never crash for Shizuku
            }
        }.start()
    }

    /** Rechecks permissions even when an older app version incorrectly marked onboarding done. */
    private fun updateOnboardingCompletion(): OnboardingStatus {
        val missing = PermissionGuide.items(this).filterNot { it.granted }
        val notificationsEnabled =
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true
        val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationsPending = Build.VERSION.SDK_INT >= 33 &&
            (!notificationPermissionGranted || !notificationsEnabled)
        val complete = missing.isEmpty() && !notificationsPending
        if (Prefs.getBool(this, Prefs.KEY_ONBOARDING_DONE, false) != complete) {
            Prefs.setBool(this, Prefs.KEY_ONBOARDING_DONE, complete)
        }
        return OnboardingStatus(missing, notificationsPending, complete)
    }

    private fun maybeShowOnboarding() {
        val status = updateOnboardingCompletion()
        if (status.complete || awaitingOnboardingPermissionReturn) {
            onboardingDialogOpen = false
            return
        }
        if (onboardingShownThisLaunch && !onboardingDialogOpen) return
        onboardingShownThisLaunch = true
        showOnboardingDialog(status)
    }

    private fun showOnboardingDialog(status: OnboardingStatus) {
        val rows = mutableListOf<PermissionRow>()
        if (status.notificationsPending) {
            rows += PermissionRow(getString(R.string.perm_notify_title)) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        status.missingItems
            .filterNot { status.notificationsPending && it.titleRes == R.string.perm_notify_title }
            .forEach { item ->
                rows += PermissionRow(getString(item.titleRes)) { item.launch(this) }
            }
        if (rows.isEmpty()) {
            onboardingDialogOpen = false
            updateOnboardingCompletion()
            return
        }

        onboardingDialogOpen = true
        var showMissingNoticeAfterDismiss = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_msg)
            .setItems(rows.map { it.label }.toTypedArray()) { _, which ->
                val row = rows.getOrNull(which) ?: return@setItems
                onboardingDialogOpen = false
                awaitingOnboardingPermissionReturn = true
                try {
                    row.launch()
                } catch (_: Exception) {
                    awaitingOnboardingPermissionReturn = false
                    showMissingNoticeAfterDismiss = true
                }
            }
            .setNeutralButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                onboardingDialogOpen = false
                showMissingNoticeAfterDismiss = true
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener {
            onboardingDialogOpen = false
            showMissingNoticeAfterDismiss = true
        }
        dialog.setOnDismissListener {
            if (showMissingNoticeAfterDismiss) {
                showMissingNoticeAfterDismiss = false
                window?.decorView?.post { showIncompleteOnboardingNotice() }
            }
        }
        dialog.show()
    }

    /** Called by the runtime-permission result or when Settings returns to this activity. */
    private fun handleOnboardingPermissionReturn() {
        if (!awaitingOnboardingPermissionReturn) {
            updateOnboardingCompletion()
            return
        }
        awaitingOnboardingPermissionReturn = false
        val status = updateOnboardingCompletion()
        if (!status.complete) showIncompleteOnboardingNotice(status)
    }

    private fun showIncompleteOnboardingNotice(
        status: OnboardingStatus = updateOnboardingCompletion()
    ) {
        if (status.complete) return
        val missingNames = buildList {
            status.missingItems.forEach { add(getString(it.titleRes)) }
            if (status.notificationsPending) add(getString(R.string.perm_notify_title))
        }.distinct().joinToString(", ")
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_incomplete_title)
            .setMessage(getString(R.string.onboarding_incomplete_message, missingNames))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Auto-boost: when "boost on game start" is on and the foreground app
     * matches a profile, start a session automatically.
     */
    private fun maybeAutoBoost() {
        if (!Prefs.getBool(this, Prefs.KEY_AUTO_BOOST, false) || BoosterService.active) return
        if (autoBoostJob?.isActive == true) return
        autoBoostJob = lifecycleScope.launch {
            val profilePkg = withContext(Dispatchers.IO) {
                val foreground = AppStore.foregroundPackage() ?: return@withContext null
                ProfileStore(applicationContext).all()
                    .firstOrNull { it.packageName == foreground }
                    ?.packageName
            }
            if (profilePkg == null || isFinishing ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !Prefs.getBool(this@MainActivity, Prefs.KEY_AUTO_BOOST, false) ||
                BoosterService.active
            ) return@launch
            try {
                BoosterService.start(this@MainActivity, profilePkg)
            } catch (_: Exception) {
                // Auto-start must not crash the foreground UI if Android rejects a service start.
            }
        }
    }

    private fun onBoostTap() {
        if (BoosterService.active) {
            BoosterService.stop(this)
        } else {
            BoosterService.start(this, Prefs.activeProfile(this))
        }
    }
}
