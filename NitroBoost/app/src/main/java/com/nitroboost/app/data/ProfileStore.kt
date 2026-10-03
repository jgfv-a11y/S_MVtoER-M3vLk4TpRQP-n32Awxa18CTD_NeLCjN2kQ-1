package com.nitroboost.app.data

import android.content.Context
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.ProfileValidation
import com.nitroboost.app.core.ShellInput
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Profiles: built-ins shipped in assets (reference-compatible schema) plus
 * user-created profiles persisted in app files.
 */
class ProfileStore(private val ctx: Context) {

    private fun customFile(): File = File(ctx.filesDir, "profiles_custom.json")

    fun builtins(): List<AppProfile> {
        return try {
            val text = ctx.assets.open("game_profiles.json").bufferedReader().use { it.readText() }
            parseArray(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun customs(): List<AppProfile> {
        val f = customFile()
        if (!f.exists()) return emptyList()
        return try {
            parseArray(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Built-ins + custom profiles. A custom profile SHADOWS a built-in with
     * the same package name (the user's settings win) — the list never shows
     * the same game twice.
     */
    fun all(): List<AppProfile> = mergeProfiles(builtins(), customs())

    companion object {
        /** Pure, unit-testable merge: customs shadow builtins by packageName. */
        fun mergeProfiles(builtins: List<AppProfile>, customs: List<AppProfile>): List<AppProfile> {
            val customPkgs = customs.mapTo(HashSet()) { it.packageName }
            val shadowed = builtins.filter { it.packageName !in customPkgs }
            return shadowed + customs
        }
    }

    fun resolve(pkg: String?): AppProfile {
        val all = all()
        val wanted = (if (pkg.isNullOrBlank()) Prefs.activeProfile(ctx) else pkg)
            ?.takeIf(ShellInput::isPackageName)
        return all.firstOrNull { it.packageName == wanted }
            ?: AppProfile(packageName = wanted ?: "", name = wanted ?: "General")
    }

    fun upsertCustom(p: AppProfile): Boolean {
        val safe = ProfileValidation.validatedCopy(p) ?: return false
        val list = customs().toMutableList()
        list.removeAll { it.packageName == safe.packageName }
        list.add(safe)
        return save(list)
    }

    fun removeCustom(pkg: String): Boolean {
        if (!ShellInput.isPackageName(pkg)) return false
        return save(customs().filterNot { it.packageName == pkg })
    }

    /** Atomic, fsync-before-rename write; false means the caller should retain the old UI state. */
    fun save(list: List<AppProfile>): Boolean {
        val safeProfiles = list.map { ProfileValidation.validatedCopy(it) ?: return false }
        val target = customFile()
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            val arr = JSONArray()
            safeProfiles.forEach { arr.put(AppProfile.toJson(it)) }
            target.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            atomicReplace(tmp, target)
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-directory rename fallback; never truncate-copy the live profile file.
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseArray(text: String): List<AppProfile> {
        val arr = JSONArray(text)
        val out = mutableListOf<AppProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = ProfileValidation.sanitizedStoredCopy(AppProfile.fromJson(o)) ?: continue
            out.add(p)
        }
        return out
    }
}
