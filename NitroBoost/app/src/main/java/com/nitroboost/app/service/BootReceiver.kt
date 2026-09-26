package com.nitroboost.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nitroboost.app.data.Prefs

/**
 * After a reboot:
 *  - restart the overlay if it was enabled;
 *  - start a boost session if auto-start is enabled.
 * Both honour the user's previous choices — nothing is forced.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (Prefs.getBool(ctx, Prefs.KEY_OVERLAY_ON, false)) {
                FpsOverlayService.start(ctx)
            }
            if (Prefs.getBool(ctx, Prefs.KEY_START_ON_BOOT, false)) {
                BoosterService.start(ctx, Prefs.activeProfile(ctx))
            }
        } catch (e: Exception) {
            // boot receivers must never throw
        }
    }
}
