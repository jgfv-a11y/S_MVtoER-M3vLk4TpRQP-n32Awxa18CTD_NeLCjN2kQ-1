package com.nitroboost.app.data

import android.content.Context
import com.nitroboost.app.core.AppProfile
import com.nitroboost.app.core.AtomicFileOps
import com.nitroboost.app.core.ProfileExchange
import com.nitroboost.app.core.ProfileValidation
import com.nitroboost.app.core.ShellInput
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Profiles: built-ins shipped in assets (reference-compatible schema) plus
 * user-created profiles persisted in app files.
 */
class ProfileStore(private val ctx: Context) {

    private fun customFile(): File = File(ctx.filesDir, "profiles_custom.json")

    /** Non-null when the persisted custom file was rejected; callers must not overwrite it blindly. */
    @Volatile
    var lastLoadIssue: String? = null
        private set

    /** Built-ins never change during a process lifetime; cache the parsed asset. */
    fun builtins(): List<AppProfile> {
        builtinsCache?.let { return it.map(AppProfile::copyProfile) }
        synchronized(BUILTIN_LOCK) {
            builtinsCache?.let { return it.map(AppProfile::copyProfile) }
            val loaded = try {
                val text = ctx.assets.open("game_profiles.json").bufferedReader().use { it.readText() }
                parseArray(text, strict = false)
            } catch (_: Exception) {
                emptyList()
            }
            builtinsCache = loaded.map(AppProfile::copyProfile)
            return loaded.map(AppProfile::copyProfile)
        }
    }

    fun customs(): List<AppProfile> {
        val f = customFile()
        lastLoadIssue = null
        if (!f.exists()) return emptyList()
        if (f.length() > ProfileExchange.MAX_BYTES) {
            return rejectCustomFile(f, "custom profile file is too large")
        }
        return try {
            parseArray(f.readText(), strict = true)
        } catch (e: Exception) {
            rejectCustomFile(f, "custom profile file rejected: ${e.message ?: "invalid JSON"}")
        }
    }

    private fun rejectCustomFile(file: File, reason: String): List<AppProfile> {
        lastLoadIssue = reason
        runCatching {
            File(file.parentFile, file.name + ".corrupt").let { file.copyTo(it, overwrite = true) }
        }
        return emptyList()
    }

    /**
     * Built-ins + custom profiles. A custom profile SHADOWS a built-in with
     * the same package name (the user's settings win) — the list never shows
     * the same game twice.
     */
    fun all(): List<AppProfile> = mergeProfiles(builtins(), customs())

    companion object {
        private val PERSISTENCE_LOCK = Any()
        private val BUILTIN_LOCK = Any()
        @Volatile
        private var builtinsCache: List<AppProfile>? = null

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
        synchronized(PERSISTENCE_LOCK) {
            val list = customs().toMutableList()
            if (lastLoadIssue != null) return false
            list.removeAll { it.packageName == safe.packageName }
            list.add(safe)
            return saveLocked(list)
        }
    }

    fun removeCustom(pkg: String): Boolean {
        if (!ShellInput.isPackageName(pkg)) return false
        synchronized(PERSISTENCE_LOCK) {
            val list = customs()
            if (lastLoadIssue != null) return false
            return saveLocked(list.filterNot { it.packageName == pkg })
        }
    }

    /**
     * Export only user profiles. Built-ins remain app-owned and are recreated
     * from the APK, which prevents a stale export from replacing the catalog.
     */
    fun exportCustomProfiles(exportedAtMs: Long = System.currentTimeMillis()): String {
        val profiles = customs()
        lastLoadIssue?.let { error(it) }
        return ProfileExchange.encode(profiles, exportedAtMs)
    }

    /**
     * Import is validate-then-commit. Conflicts are reported without writing
     * anything unless the caller explicitly opts into replacement.
     */
    fun importCustomProfiles(
        text: String,
        replaceConflicts: Boolean = false
    ): ProfileExchange.ImportResult {
        val decoded = ProfileExchange.decode(text)
        if (decoded !is ProfileExchange.ImportResult.Accepted) return decoded
        synchronized(PERSISTENCE_LOCK) {
            val existing = customs().associateBy { it.packageName }
            if (lastLoadIssue != null) {
                return ProfileExchange.ImportResult.Rejected(lastLoadIssue!!)
            }
            val conflicts = decoded.profiles.filter {
                existing[it.packageName] != null && existing[it.packageName] != it
            }.map { it.packageName }
            if (conflicts.isNotEmpty() && !replaceConflicts) {
                return ProfileExchange.ImportResult.Conflict(conflicts.sorted())
            }
            val merged = existing.toMutableMap()
            decoded.profiles.forEach { merged[it.packageName] = it }
            if (!saveLocked(merged.values.sortedBy { it.packageName })) {
                return ProfileExchange.ImportResult.Rejected("could not atomically save imported profiles")
            }
            return ProfileExchange.ImportResult.Accepted(
                profiles = decoded.profiles,
                replacedPackages = conflicts.toSet()
            )
        }
    }

    /** Atomic, fsync-before-rename write; false means the caller should retain the old UI state. */
    fun save(list: List<AppProfile>): Boolean = synchronized(PERSISTENCE_LOCK) {
        if (customFile().exists()) {
            customs()
            if (lastLoadIssue != null) return@synchronized false
        }
        saveLocked(list)
    }

    private fun saveLocked(list: List<AppProfile>): Boolean {
        val safeProfiles = list.map { ProfileValidation.validatedCopy(it) ?: return false }
        val target = customFile()
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            val arr = JSONArray()
            safeProfiles.sortedBy { it.packageName }.forEach { arr.put(AppProfile.toJson(it)) }
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
        // Same-directory rename remains available on Android 7/API 24.
        AtomicFileOps.replace(source, destination)
    }

    private fun parseArray(text: String, strict: Boolean): List<AppProfile> {
        val arr = JSONArray(text)
        if (arr.length() > ProfileExchange.MAX_PROFILES) {
            error("too many profiles")
        }
        val out = mutableListOf<AppProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                if (strict) error("profile $i is not an object")
                continue
            }
            val p = ProfileValidation.sanitizedStoredCopy(AppProfile.fromJson(o))
            if (p == null) {
                if (strict) error("profile $i failed validation")
                continue
            }
            out.add(p)
        }
        if (strict && out.map { it.packageName }.toSet().size != out.size) {
            error("duplicate package names")
        }
        return out
    }
}
