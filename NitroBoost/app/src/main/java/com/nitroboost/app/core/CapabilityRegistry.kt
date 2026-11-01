package com.nitroboost.app.core

import java.util.concurrent.ConcurrentHashMap

/**
 * One vocabulary for capability and task availability.
 *
 * A false boolean is not enough for a safe UI: a feature can be unsupported,
 * require a permission, be temporarily unavailable while a service reconnects,
 * fail while probing, be rejected by policy, or already be applied. Keeping
 * those states together prevents the presentation layer from guessing.
 */
enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    TEMPORARILY_UNAVAILABLE,
    FAILED,
    APPLIED,
    REJECTED
}

data class CapabilityStatus(
    val state: CapabilityState,
    val reason: String,
    val observedAtMs: Long,
    val source: String = "task-probe"
) {
    val usable: Boolean
        get() = state == CapabilityState.SUPPORTED || state == CapabilityState.APPLIED
}

/**
 * Short-lived, per-profile capability cache. Probes are deliberately not
 * cached forever: Shizuku/root can disconnect and a ROM can change state.
 * The cache only avoids hammering Binder/shell while a screen is rendering.
 */
class CapabilityRegistry(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private data class Key(
        val taskId: String,
        val packageName: String,
        val profileFingerprint: String,
        val privileged: Boolean,
        val thermalSensor: Boolean,
        val recoveryBlocked: Boolean,
        val journaled: Boolean
    )

    private val cache = ConcurrentHashMap<Key, CapabilityStatus>()

    fun status(task: BoostTask, ctx: BoostContext, nowMs: Long = System.currentTimeMillis()): CapabilityStatus {
        val key = Key(
            taskId = task.id,
            packageName = ctx.profile.packageName,
            profileFingerprint = profileFingerprint(ctx.profile),
            privileged = ctx.executor.privileged,
            thermalSensor = ctx.executor.thermalSafetyAvailable,
            recoveryBlocked = ctx.journal.recoveryBlocked,
            journaled = ctx.journal.containsTask(task.id)
        )
        cache[key]?.takeIf { nowMs - it.observedAtMs in 0..ttlMs }?.let { return it }

        val result = probe(task, ctx, nowMs)
        cache[key] = result
        // Keep the cache bounded even if a caller rotates through many game
        // profiles in one process. This is a cache, never durable state.
        if (cache.size > MAX_ENTRIES) {
            cache.entries.removeIf { nowMs - it.value.observedAtMs > ttlMs }
            if (cache.size > MAX_ENTRIES) cache.clear()
        }
        return result
    }

    fun invalidate() {
        cache.clear()
    }

    private fun probe(task: BoostTask, ctx: BoostContext, nowMs: Long): CapabilityStatus {
        if (ctx.journal.recoveryBlocked) {
            return CapabilityStatus(
                CapabilityState.FAILED,
                "journal recovery is blocked; no new mutation is safe",
                nowMs,
                "journal"
            )
        }
        if (task.id == "thermal_override") {
            // Kept as a named task only so a legacy journal can be restored.
            // New builds must never disable or override Android thermal policy.
            return CapabilityStatus(
                CapabilityState.REJECTED,
                "thermal protection is immutable in NitroBoost 1.24",
                nowMs,
                "safety-policy"
            )
        }
        if (ctx.journal.containsTask(task.id)) {
            return CapabilityStatus(CapabilityState.APPLIED, "journaled by this session", nowMs, "journal")
        }
        if (task.requiresPrivilege && !ctx.executor.privileged) {
            return CapabilityStatus(
                CapabilityState.PERMISSION_REQUIRED,
                "Shizuku or root permission is required",
                nowMs,
                "privilege-check"
            )
        }
        return try {
            if (task.isSupported(ctx)) {
                CapabilityStatus(CapabilityState.SUPPORTED, "device and permission checks passed", nowMs)
            } else {
                CapabilityStatus(
                    CapabilityState.UNSUPPORTED,
                    "the device or ROM did not confirm this capability",
                    nowMs,
                    "task-probe"
                )
            }
        } catch (e: Exception) {
            CapabilityStatus(
                CapabilityState.FAILED,
                "capability probe failed safely: ${e.message ?: e.javaClass.simpleName}",
                nowMs,
                "task-probe"
            )
        }
    }

    private fun profileFingerprint(profile: AppProfile): String = listOf(
        profile.packageName,
        profile.enabledModules.map { it.key }.sorted().joinToString(","),
        profile.dpi,
        profile.refreshRate,
        profile.fpsCap,
        profile.gameMode,
        profile.dnd,
        profile.killAnimations,
        profile.aggressiveRamClean,
        profile.thermalOverride,
        profile.extraProtected.sorted().joinToString(",")
    ).joinToString("|")

    companion object {
        const val DEFAULT_TTL_MS = 2_000L
        private const val MAX_ENTRIES = 256
    }
}
