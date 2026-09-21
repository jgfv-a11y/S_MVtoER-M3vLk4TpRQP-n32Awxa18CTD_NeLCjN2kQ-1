package com.nitroboost.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.nitroboost.app.R

/**
 * Human-friendly checklist of every special permission the app can use.
 * Everything is optional; the app degrades gracefully without each one.
 */
object PermissionGuide {

    data class Item(
        val titleRes: Int,
        val descRes: Int,
        val granted: Boolean,
        val launch: (Context) -> Unit
    )

    fun items(ctx: Context): List<Item> = mutableListOf(
        Item(
            R.string.perm_notify_title, R.string.perm_notify_desc,
            Build.VERSION.SDK_INT < 33 ||
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
        ) { c ->
            try {
                c.startActivity(
                    Intent(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        Uri.parse("package:${c.packageName}")
                    )
                )
            } catch (e: Exception) {
            }
        },
        Item(
            R.string.perm_overlay_title, R.string.perm_overlay_desc,
            Settings.canDrawOverlays(ctx)
        ) { c ->
            c.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${c.packageName}")
                )
            )
        },
        Item(
            R.string.perm_dnd_title, R.string.perm_dnd_desc,
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted
        ) { c ->
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
        Item(
            R.string.perm_write_title, R.string.perm_write_desc,
            Settings.System.canWrite(ctx)
        ) { c ->
            c.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${c.packageName}")
                )
            )
        },
        Item(
            R.string.perm_usage_title, R.string.perm_usage_desc,
            android.provider.Settings.canDrawOverlays(ctx) && usageGranted(ctx)
        ) { c ->
            c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    )

    private fun usageGranted(ctx: Context): Boolean {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600 * 1000, now
            )
            stats.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun show(ctx: AppCompatActivity) {
        val items = items(ctx)
        val rows = items.map {
            if (it.granted) {
                "\u2713  ${ctx.getString(it.titleRes)}"
            } else {
                "\u25CB  ${ctx.getString(it.titleRes)}"
            }
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.perm_title)
            .setItems(rows) { _, which ->
                items[which].launch(ctx)
            }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }
}
