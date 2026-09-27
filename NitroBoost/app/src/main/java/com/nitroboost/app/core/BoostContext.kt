package com.nitroboost.app.core

/** Everything a task may touch while applying. Never a raw Context — keeps the core JVM-testable. */
data class BoostContext(
    val profile: AppProfile,
    val executor: SystemExecutor,
    val journal: Journal,
    val log: (String) -> Unit = {}
)
