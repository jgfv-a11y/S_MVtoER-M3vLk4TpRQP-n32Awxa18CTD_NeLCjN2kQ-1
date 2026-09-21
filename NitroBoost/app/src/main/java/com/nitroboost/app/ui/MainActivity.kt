package com.nitroboost.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs
import com.nitroboost.app.data.ProfileStore
import com.nitroboost.app.service.BoosterService

class MainActivity : AppCompatActivity() {

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
    }

    override fun onResume() {
        super.onResume()
        AppStore.refreshTaskStates()
        maybeAutoBoost()
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
