package com.nitroboost.app.core

/**
 * Explicit Shizuku connection state machine (v1.24.1 UI reliability).
 *
 * The UI must never show "connected" merely because a request was sent:
 * connection state is derived from facts (binder alive, permission granted,
 * user service bound), and command state is derived from the executed
 * command's exit code — never from "the call returned".
 */
enum class ShizukuConnection {
    /** Shizuku app missing or binder dead and we never had a session. */
    DISCONNECTED,

    /** Binder alive, but this app has not been granted permission. */
    CONNECTED_NO_PERMISSION,

    /** Binder alive + permission granted + user service bound: fully usable. */
    CONNECTED_GRANTED,

    /** We were fully ready before and the binder/service died since. */
    SESSION_EXPIRED
}

/** Outcome of the last privileged command, from its real exit code. */
enum class CommandStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED
}

/** UI-facing snapshot: connection state + last command outcome. */
data class ShizukuUiState(
    val connection: ShizukuConnection,
    val command: CommandStatus,
    val commandMessage: String? = null
) {
    companion object {
        val IDLE = ShizukuUiState(ShizukuConnection.DISCONNECTED, CommandStatus.IDLE, null)
    }
}

object ShizukuConnectionResolver {

    /**
     * Pure resolution of the connection state from observed facts.
     * [everReady] distinguishes SESSION_EXPIRED (we had a session that died)
     * from a plain DISCONNECTED (we never connected).
     */
    fun resolve(
        installed: Boolean,
        binderAlive: Boolean,
        permissionGranted: Boolean,
        bound: Boolean,
        everReady: Boolean
    ): ShizukuConnection {
        if (!installed || !binderAlive) {
            return if (everReady) ShizukuConnection.SESSION_EXPIRED
            else ShizukuConnection.DISCONNECTED
        }
        if (!permissionGranted) return ShizukuConnection.CONNECTED_NO_PERMISSION
        return if (bound) ShizukuConnection.CONNECTED_GRANTED
        else ShizukuConnection.CONNECTED_NO_PERMISSION
    }

    /** Command status transition: only a real exit code moves RUNNING → terminal. */
    fun commandResult(previous: CommandStatus, exitCode: Int, message: String?): CommandStatus {
        if (previous != CommandStatus.RUNNING) return previous
        return if (exitCode == 0) CommandStatus.SUCCEEDED else CommandStatus.FAILED
    }
}
