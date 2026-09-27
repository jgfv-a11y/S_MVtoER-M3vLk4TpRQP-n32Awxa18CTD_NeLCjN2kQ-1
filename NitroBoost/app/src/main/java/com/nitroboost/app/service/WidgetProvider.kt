package com.nitroboost.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nitroboost.app.AppStore
import com.nitroboost.app.R
import com.nitroboost.app.ui.MainActivity

/**
 * Home-screen widget: live score + session status. Tapping it opens the app
 * with a "boost" hint.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun update(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
                if (ids.isEmpty()) return
                mgr.updateAppWidget(ids, buildViews(ctx))
            } catch (e: Exception) {
                // widgets are cosmetic — never crash
            }
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_nitro)
            val score = try {
                AppStore.score.value ?: 0
            } catch (e: Exception) {
                0
            }
            rv.setTextViewText(R.id.widget_score, score.toString())
            rv.setTextViewText(
                R.id.widget_status,
                if (BoosterService.active) {
                    ctx.getString(R.string.session_active)
                } else {
                    ctx.getString(R.string.session_idle)
                }
            )
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            return rv
        }
    }
}
