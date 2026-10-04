package com.nitroboost.app.data

import android.content.Context
import android.content.SharedPreferences
import com.nitroboost.app.core.ProfileValidation
import com.nitroboost.app.core.ShellInput

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
    const val KEY_LAST_REPORT = "last_session_report" // JSON
    const val KEY_PREV_FPS = "prev_session_avg_fps"
    const val KEY_ADAPTIVE_ON = "adaptive_engine_on"
    const val KEY_ONBOARDING_DONE = "onboarding_shown"
    const val KEY_BOOST_LEVEL = "boost_level"

    private fun edit(ctx: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = sp(ctx).edit()
        block(editor)
        editor.apply()
    }

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun activeProfile(ctx: Context): String? = try {
        val pkg = sp(ctx).getString(KEY_ACTIVE_PROFILE, null)
        if (pkg.isNullOrBlank()) null else pkg.takeIf(ShellInput::isPackageName)
    } catch (_: Exception) {
        null
    }

    fun setActiveProfile(ctx: Context, pkg: String?) {
        edit(ctx) {
            if (pkg == null || !ShellInput.isPackageName(pkg)) remove(KEY_ACTIVE_PROFILE)
            else putString(KEY_ACTIVE_PROFILE, pkg)
        }
    }

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean = try {
        sp(ctx).getBoolean(key, def)
    } catch (_: Exception) {
        def
    }

    fun setBool(ctx: Context, key: String, value: Boolean) {
        edit(ctx) { putBoolean(key, value) }
    }

    fun getInt(ctx: Context, key: String, def: Int): Int = try {
        sp(ctx).getInt(key, def)
    } catch (_: Exception) {
        def
    }

    /** Persisted/UI values are clamped to the supported three-level scale. */
    fun boostLevel(ctx: Context): Int =
        getInt(ctx, KEY_BOOST_LEVEL, 2).coerceIn(1, 3)

    fun putInt(ctx: Context, key: String, value: Int) {
        edit(ctx) { putInt(key, value) }
    }

    fun putString(ctx: Context, key: String, value: String) {
        if (key.isBlank()) return
        edit(ctx) { putString(key, value) }
    }

    fun taskEnabled(ctx: Context, taskId: String, def: Boolean): Boolean = try {
        if (!taskId.matches(Regex("[A-Za-z0-9_]{1,80}"))) def
        else sp(ctx).getBoolean(KEY_TASK_PREFIX + taskId, def)
    } catch (_: Exception) {
        def
    }

    fun setTaskEnabled(ctx: Context, taskId: String, value: Boolean) {
        if (!taskId.matches(Regex("[A-Za-z0-9_]{1,80}"))) return
        edit(ctx) { putBoolean(KEY_TASK_PREFIX + taskId, value) }
    }

    fun protectedList(ctx: Context): List<String> {
        val raw = try {
            sp(ctx).getString(KEY_PROTECTED, "").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return raw.split(',', '\n')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && ShellInput.isPackageName(it) }
            .distinct()
            .take(ProfileValidation.MAX_PROTECTED_PACKAGES)
            .toList()
    }

    fun setProtectedList(ctx: Context, list: List<String>) {
        val safe = list.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && ShellInput.isPackageName(it) }
            .distinct()
            .take(ProfileValidation.MAX_PROTECTED_PACKAGES)
            .toList()
        edit(ctx) { putString(KEY_PROTECTED, safe.joinToString(",")) }
    }

    fun lang(ctx: Context): String = try {
        sp(ctx).getString(KEY_LANG, "auto")?.takeIf { it in setOf("auto", "ar", "en") } ?: "auto"
    } catch (_: Exception) {
        "auto"
    }

    fun setLang(ctx: Context, lang: String) {
        if (lang !in setOf("ar", "en")) return
        edit(ctx) { putString(KEY_LANG, lang) }
    }
}
