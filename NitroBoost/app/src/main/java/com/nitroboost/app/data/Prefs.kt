package com.nitroboost.app.data

import android.content.Context
import android.content.SharedPreferences

/** Central preferences. All keys live here — no stringly-typed sprinkling. */
object Prefs {

    const val FILE = "nitroboost_prefs"

    const val KEY_ACTIVE_PROFILE = "active_profile"
    const val KEY_AUTO_BOOST = "auto_boost_on_game_start"
    const val KEY_AUTO_RESTORE = "auto_restore_on_game_exit"
    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_OVERLAY_ON = "overlay_on"
    const val KEY_OV_FPS = "overlay_fps"
    const val KEY_OV_CPU = "overlay_cpu"
    const val KEY_OV_RAM = "overlay_ram"
    const val KEY_OV_TEMP = "overlay_temp"
    const val KEY_OV_PING = "overlay_ping"
    const val KEY_LANG = "lang" // "ar" | "en"
    const val KEY_PROTECTED = "protected_list" // comma separated
    const val KEY_TASK_PREFIX = "task_enabled_"

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun activeProfile(ctx: Context): String? = sp(ctx).getString(KEY_ACTIVE_PROFILE, null)

    fun setActiveProfile(ctx: Context, pkg: String?) {
        sp(ctx).edit().apply {
            if (pkg == null) remove(KEY_ACTIVE_PROFILE) else putString(KEY_ACTIVE_PROFILE, pkg)
        }.apply()
    }

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean = sp(ctx).getBoolean(key, def)

    fun setBool(ctx: Context, key: String, value: Boolean) {
        sp(ctx).edit().putBoolean(key, value).apply()
    }

    fun taskEnabled(ctx: Context, taskId: String, def: Boolean): Boolean =
        sp(ctx).getBoolean(KEY_TASK_PREFIX + taskId, def)

    fun setTaskEnabled(ctx: Context, taskId: String, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TASK_PREFIX + taskId, value).apply()
    }

    fun protectedList(ctx: Context): List<String> =
        sp(ctx).getString(KEY_PROTECTED, "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setProtectedList(ctx: Context, list: List<String>) {
        sp(ctx).edit().putString(KEY_PROTECTED, list.joinToString(",")).apply()
    }

    fun lang(ctx: Context): String = sp(ctx).getString(KEY_LANG, "auto") ?: "auto"

    fun setLang(ctx: Context, lang: String) {
        sp(ctx).edit().putString(KEY_LANG, lang).apply()
    }
}
