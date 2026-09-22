package com.nitroboost.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.platform.ShizukuShell
import com.nitroboost.app.service.BoosterService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PKG = "moe.shizuku.manager"
    }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val fragment: androidx.fragment.app.Fragment = when (item.itemId) {
                R.id.nav_boost -> BoostFragment()
                R.id.nav_profiles -> ProfilesFragment()
                R.id.nav_system -> SystemFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> DashboardFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_dashboard
        }

        // Launched from the home widget with a boost hint.
        if (intent?.getBooleanExtra("boost", false) == true) {
            onBoostTap()
        }

        // First run: ask for the permissions that unlock the app (popup).
        maybeShowOnboarding()
        // Shizuku: fully automatic — the app drives the whole flow, the
        // user never has to hunt for a button.
        autoShizuku()
    }

    override fun onResume() {
        super.onResume()
        AppStore.refreshTaskStates()
        maybeAutoBoost()
        // Shizuku may have (re)started in the meantime — re-check & rebind.
        autoShizuku()
    }

    /**
     * Automatic Shizuku flow:
     *  - Shizuku app missing          -> nothing to do (app degrades);
     *  - Shizuku not running          -> open it (it then auto-starts on boot);
     *  - running, permission not yet  -> dispatch Shizuku's grant dialog;
     *  - granted                      -> bind the user service (auto retry).
     */
    private fun autoShizuku() {
        Thread {
            try {
                val installed = try {
                    packageManager.getPackageInfo(SHIZUKU_PKG, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
                if (!installed) return@Thread
                if (!ShizukuShell.isReady()) {
                    runOnUiThread {
                        try {
                            ShizukuShell.openShizukuApp(this)
                        } catch (e: Exception) {
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
