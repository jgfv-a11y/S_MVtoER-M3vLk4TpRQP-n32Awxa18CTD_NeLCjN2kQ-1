package com.nitroboost.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Versioned, deterministic profile import/export envelope.
 *
 * The checksum is not a security signature; it detects truncation and casual
 * corruption before a profile is applied. Package names and all numeric fields
 * still pass the normal strict validation path.
 */
object ProfileExchange {
    const val SCHEMA_VERSION = 1
    const val MAX_PROFILES = 100
    const val MAX_BYTES = 512 * 1024

    sealed class ImportResult {
        data class Accepted(
            val profiles: List<AppProfile>,
            val replacedPackages: Set<String> = emptySet()
        ) : ImportResult()

        data class Conflict(val packages: List<String>) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
    }

    fun encode(profiles: List<AppProfile>, exportedAtMs: Long = System.currentTimeMillis()): String {
        require(profiles.size <= MAX_PROFILES) { "too many profiles" }
        val normalized = profiles
            .map { ProfileValidation.validatedCopy(it) ?: error("invalid profile: ${it.packageName}") }
            .sortedBy { it.packageName }
        val payload = profilesJson(normalized).toString()
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAtMs", exportedAtMs.coerceAtLeast(0L))
            put("profiles", JSONArray(payload))
            put("sha256", sha256(payload))
        }.toString(2)
    }

    fun decode(text: String): ImportResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ImportResult.Rejected("profile export is too large")
        }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return ImportResult.Rejected("profile export is not valid JSON")
        }
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            return ImportResult.Rejected("unsupported profile schema version")
        }
        val array = root.optJSONArray("profiles")
            ?: return ImportResult.Rejected("profiles array is missing")
        if (array.length() > MAX_PROFILES) {
            return ImportResult.Rejected("profile export contains too many profiles")
        }
        val parsed = mutableListOf<AppProfile>()
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index)
                ?: return ImportResult.Rejected("profile $index is not an object")
            val profile = ProfileValidation.validatedCopy(AppProfile.fromJson(raw))
                ?: return ImportResult.Rejected("profile ${index + 1} failed strict validation")
            parsed += profile
        }
        val packages = parsed.map { it.packageName }
        if (packages.size != packages.toSet().size) {
            return ImportResult.Rejected("duplicate package names in export")
        }
        val canonical = profilesJson(parsed.sortedBy { it.packageName }).toString()
        val declared = root.optString("sha256")
        if (!declared.equals(sha256(canonical), ignoreCase = true)) {
            return ImportResult.Rejected("profile checksum mismatch")
        }
        return ImportResult.Accepted(parsed.sortedBy { it.packageName })
    }

    private fun profilesJson(profiles: List<AppProfile>): JSONArray = JSONArray().apply {
        profiles.forEach { put(AppProfile.toJson(it)) }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
