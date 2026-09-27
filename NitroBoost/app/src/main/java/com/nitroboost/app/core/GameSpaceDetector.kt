package com.nitroboost.app.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * OEM game-space / game-assistant apps that own their own performance
 * stack (OPlus Game, MI Game Turbo, Samsung Game Booster, …).
 *
 * Detection only — we never launch or configure them. When one is present
 * the UI advises the user that enabling its game mode too gives the best
 * result (two boosters can silently fight; the user decides).
 */
object GameSpaceDetector {

    val KNOWN: List<Pair<String, String>> = listOf(
        "com.oplus.games" to "OPlus Game",
        "com.oplus.gamespace" to "OPlus GameSpace",
        "com.coloros.gamespace" to "ColorOS GameSpace",
        "com.oneplus.gamespace" to "OnePlus GameSpace",
        "com.hihonor.gameassistant" to "HONOR Game Assistant",
        "com.huawei.gameassistant" to "Huawei Game Assistant",
        "com.miui.securitycenter" to "MI Game Turbo",
        "com.samsung.android.game.gos" to "Samsung Game Booster",
        "com.samsung.android.game.gamelab" to "Samsung Game Launcher",
        "com.vivo.game" to "vivo Game",
        "com.heytap.game" to "HeyTap Game"
    )

    /** Pure matching — unit-testable without a Context. */
    fun match(installed: Set<String>): List<Pair<String, String>> =
        KNOWN.filter { it.first in installed }

    fun detect(ctx: Context): List<Pair<String, String>> = try {
        val pm = ctx.packageManager
        val installed = KNOWN.mapNotNull { (pkg, _) ->
            try {
                pm.getPackageInfo(pkg, 0)
                pkg
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }.toSet()
        match(installed)
    } catch (e: Exception) {
        emptyList()
    }
}
