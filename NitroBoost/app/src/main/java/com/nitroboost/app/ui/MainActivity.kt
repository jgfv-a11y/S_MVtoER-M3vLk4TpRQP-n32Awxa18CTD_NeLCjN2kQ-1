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
import androidx.lifecycle.Observer
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService

class MainActivity : AppCompatActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Shizuku is opened at most once per launch — the poller keeps the rest automatic. */
    private var shizukuOpenedThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val fragment: androidx.fragment.app.Fragment = when (item.itemId) {
                R.id.nav_boost -> BoostFragment()
                R.id.nav_profiles -> ProfilesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_home
        }

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

    override fun onResume() {
        super.onResume()
        AppStore.refreshTaskStates()
        maybeAutoBoost()
        autoShizuku()
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

    /** One-time popup that asks for the permissions the app can actually use. */
    private fun maybeShowOnboarding() {
        if (Prefs.getBool(this, Prefs.KEY_ONBOARDING_DONE, false)) return
        Prefs.setBool(this, Prefs.KEY_ONBOARDING_DONE, true)
        val items = PermissionGuide.items(this)
        val missing = items.filter { !it.granted }
        val notifPending = Build.VERSION.SDK_INT >= 33 &&
            !(getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled())
        if (missing.isEmpty() && !notifPending) return
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (notifPending) {
            rows += getString(R.string.perm_notify_title) to {
                try {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                }
            }
        }
        missing.forEach { rows += getString(it.titleRes) to { it.launch(this) } }
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_msg)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which ->
                rows[which].second.invoke()
            }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Auto-boost: when "boost on game start" is on and the foreground app
     * matches a profile, start a session automatically.
     */
    private fun maybeAutoBoost() {
        if (!Prefs.getBool(this, Prefs.KEY_AUTO_BOOST, false)) return
        if (BoosterService.active) return
        val fg = AppStore.foregroundPackage() ?: return
        val profile = ProfileStore(this).all().firstOrNull { it.packageName == fg } ?: return
        BoosterService.start(this, profile.packageName)
    }

    private fun onBoostTap() {
        if (BoosterService.active) {
            BoosterService.stop(this)
        } else {
            BoosterService.start(this, Prefs.activeProfile(this))
        }
    }
}
