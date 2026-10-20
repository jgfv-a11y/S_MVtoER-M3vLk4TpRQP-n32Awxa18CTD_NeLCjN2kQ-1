package com.nitroboost.app.core

/**
 * Opt-in controller for Android's HWUI renderer property. This is deliberately
 * separate from game-boost sessions: `debug.hwui.renderer` configures Android's
 * HWUI/Skia path, not the renderer selected inside an arbitrary game engine.
 */
object SystemRendererController {
    const val TASK_ID = "system_renderer"
    const val PROPERTY = ShellInput.HWUI_RENDERER_PROPERTY
    const val MIN_ANDROID_API = 29

    private val switchLock = Any()

    enum class Target(val propertyValue: String) {
        VULKAN("skiavk"),
        OPENGL("opengl")
    }

    enum class Code {
        APPLIED,
        NO_CHANGE,
        RESTORED,
        NO_SAVED_CHANGE,
        UNSUPPORTED_ANDROID,
        VULKAN_UNAVAILABLE,
        PRIVILEGE_REQUIRED,
        PROPERTY_UNREADABLE,
        UNKNOWN_PROPERTY_VALUE,
        JOURNAL_BLOCKED,
        APPLY_FAILED,
        RESTORE_FAILED
    }

    data class Result(
        val code: Code,
        val previous: String? = null,
        val current: String? = null,
        val phase: RendererSwitchStateMachine.Phase? = null
    ) {
        val success: Boolean
            get() = code == Code.APPLIED || code == Code.NO_CHANGE || code == Code.RESTORED ||
                code == Code.NO_SAVED_CHANGE
    }

    /**
     * Set the global HWUI property only after a user-triggered request. The
     * caller supplies platform capability data so this controller stays JVM-testable.
     */
    fun setRenderer(
        executor: SystemExecutor,
        journal: Journal,
        target: Target,
        androidApi: Int,
        hasVulkanHardwareFeature: Boolean
    ): Result = synchronized(switchLock) {
        if (androidApi < MIN_ANDROID_API) return@synchronized Result(Code.UNSUPPORTED_ANDROID)
        if (target == Target.VULKAN && !hasVulkanHardwareFeature) {
            return@synchronized Result(Code.VULKAN_UNAVAILABLE)
        }
        if (!safePrivileged(executor)) return@synchronized Result(Code.PRIVILEGE_REQUIRED)

        val previous = safeRead(executor) ?: return@synchronized Result(Code.PROPERTY_UNREADABLE)
        if (!ShellInput.isHwuiRendererValue(previous)) {
            return@synchronized Result(Code.UNKNOWN_PROPERTY_VALUE, current = previous)
        }

        val transaction = RendererSwitchStateMachine(target.propertyValue, previous)
        transaction.request()
        if (journal.recoveryBlocked) {
            transaction.fail("renderer rollback journal is blocked")
            return@synchronized Result(
                Code.JOURNAL_BLOCKED, previous, previous, transaction.phase
            )
        }

        transaction.applying()
        if (matches(target, previous)) {
            transaction.verifying()
            val verified = safeRead(executor)
            if (verified == null || !matches(target, verified)) {
                transaction.fail("renderer property could not be read back")
                return@synchronized Result(
                    Code.APPLY_FAILED, previous, verified, transaction.phase
                )
            }
            transaction.verified()
            transaction.complete()
            return@synchronized Result(Code.NO_CHANGE, previous, verified, transaction.phase)
        }

        val hadPriorJournalEntry = journal.containsTaskKey(TASK_ID, PROPERTY)
        val writer = JournaledSystemExecutor(executor, journal, TASK_ID)
        val writeAccepted = try {
            writer.systemPropertyPut(PROPERTY, target.propertyValue)
        } catch (_: Exception) {
            false
        }
        transaction.verifying()
        val readBack = safeRead(executor)
        if (!writeAccepted || readBack != target.propertyValue) {
            transaction.fail("renderer property write or verification failed")
            // If an unusual backend partially changed the property, make a best
            // effort to return to this request's immediate pre-change value.
            // Keep any earlier write-ahead baseline in the dedicated journal.
            if (readBack != null && readBack != previous) {
                val rolledBack = try {
                    executor.systemPropertyPut(PROPERTY, previous) && safeRead(executor) == previous
                } catch (_: Exception) {
                    false
                }
                if (rolledBack && !hadPriorJournalEntry) {
                    val newEntries = journal.snapshot().filter {
                        it.taskId == TASK_ID && it.kind == JournalEntry.Kind.SYSTEM_PROPERTY &&
                            it.key == PROPERTY
                    }
                    if (newEntries.isNotEmpty()) journal.remove(newEntries)
                }
            }
            return@synchronized Result(Code.APPLY_FAILED, previous, safeRead(executor), transaction.phase)
        }

        transaction.verified()
        transaction.complete()
        Result(Code.APPLIED, previous, readBack, transaction.phase)
    }

    /** Restores the value captured before the first NitroBoost renderer change. */
    fun restoreOriginal(executor: SystemExecutor, journal: Journal): Result = synchronized(switchLock) {
        if (!safePrivileged(executor)) return@synchronized Result(Code.PRIVILEGE_REQUIRED)
        if (journal.recoveryBlocked) return@synchronized Result(Code.JOURNAL_BLOCKED)

        val entries = journal.snapshot().filter {
            it.taskId == TASK_ID && it.kind == JournalEntry.Kind.SYSTEM_PROPERTY && it.key == PROPERTY
        }.asReversed()
        if (entries.isEmpty()) return@synchronized Result(Code.NO_SAVED_CHANGE)

        var restoredValue: String? = null
        for (entry in entries) {
            if (!Journal.restore(entry, executor)) {
                return@synchronized Result(
                    Code.RESTORE_FAILED,
                    previous = entry.newValue,
                    current = safeRead(executor)
                )
            }
            restoredValue = entry.oldValue
            if (!journal.remove(listOf(entry))) {
                return@synchronized Result(
                    Code.RESTORE_FAILED,
                    previous = entry.newValue,
                    current = safeRead(executor)
                )
            }
        }

        val verified = safeRead(executor)
        if (verified == null || verified != restoredValue) {
            return@synchronized Result(Code.RESTORE_FAILED, restoredValue, verified)
        }
        Result(Code.RESTORED, previous = null, current = verified)
    }

    /** Maps the property value only; an unset value is deliberately not guessed. */
    fun describe(value: String?): String = when {
        value == null -> "unreadable"
        value.isEmpty() -> "default"
        value.equals("skiavk", ignoreCase = true) -> "vulkan-requested"
        value.equals("opengl", ignoreCase = true) || value.equals("skiagl", ignoreCase = true) -> "opengl-requested"
        else -> "unknown"
    }

    private fun matches(target: Target, value: String): Boolean = when (target) {
        Target.VULKAN -> value.equals(target.propertyValue, ignoreCase = true)
        Target.OPENGL -> value.equals("opengl", ignoreCase = true) ||
            value.equals("skiagl", ignoreCase = true)
    }

    private fun safePrivileged(executor: SystemExecutor): Boolean = try {
        executor.privileged
    } catch (_: Exception) {
        false
    }

    private fun safeRead(executor: SystemExecutor): String? = try {
        executor.systemPropertyGet(PROPERTY)
    } catch (_: Exception) {
        null
    }
}
