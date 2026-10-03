package com.nitroboost.app.core

/** Validation and normalization shared by profile storage, UI, and task execution. */
object ProfileValidation {

    enum class Field { PACKAGE_NAME, NAME, DPI, REFRESH_RATE, GAME_MODE, FPS_CAP }

    fun isValidDpi(value: Int): Boolean = value == 0 || value in MIN_DPI..MAX_DPI
    fun isValidRefreshRate(value: Int): Boolean = value == 0 || value in MIN_REFRESH_HZ..MAX_REFRESH_HZ
    fun isValidGameMode(value: Int): Boolean = value in MIN_GAME_MODE..MAX_GAME_MODE
    fun isValidFpsCap(value: Int): Boolean = value == 0 || value in 1..MAX_FPS_CAP

    fun invalidFields(profile: AppProfile): Set<Field> = buildSet {
        if (!ShellInput.isPackageName(profile.packageName)) add(Field.PACKAGE_NAME)
        if (!isValidDpi(profile.dpi)) add(Field.DPI)
        if (!isValidRefreshRate(profile.refreshRate)) add(Field.REFRESH_RATE)
        if (!isValidGameMode(profile.gameMode)) add(Field.GAME_MODE)
        if (!isValidFpsCap(profile.fpsCap)) add(Field.FPS_CAP)
    }

    /** Returns a normalized copy, or null if the package or numeric settings are invalid. */
    fun validatedCopy(profile: AppProfile): AppProfile? {
        if (invalidFields(profile).isNotEmpty()) return null
        return copyWithSafeCollections(profile, profile.dpi, profile.refreshRate, profile.gameMode, profile.fpsCap)
    }

    /** Loads older persisted profiles safely, replacing out-of-range tweaks with neutral defaults. */
    fun sanitizedStoredCopy(profile: AppProfile): AppProfile? {
        if (!ShellInput.isPackageName(profile.packageName)) return null
        return copyWithSafeCollections(
            profile,
            profile.dpi.takeIf(::isValidDpi) ?: 0,
            profile.refreshRate.takeIf(::isValidRefreshRate) ?: 0,
            profile.gameMode.takeIf(::isValidGameMode) ?: 0,
            profile.fpsCap.takeIf(::isValidFpsCap) ?: 0
        )
    }

    private fun copyWithSafeCollections(
        profile: AppProfile,
        dpi: Int,
        refreshRate: Int,
        gameMode: Int,
        fpsCap: Int
    ): AppProfile = AppProfile(
        packageName = profile.packageName,
        name = profile.name.trim().ifEmpty { profile.packageName }.take(MAX_NAME_LENGTH),
        enabledModules = profile.enabledModules.toMutableSet(),
        dpi = dpi,
        refreshRate = refreshRate,
        fpsCap = fpsCap,
        dnd = profile.dnd,
        killAnimations = profile.killAnimations,
        aggressiveRamClean = profile.aggressiveRamClean,
        thermalOverride = profile.thermalOverride,
        gameMode = gameMode,
        extraProtected = profile.extraProtected
            .asSequence()
            .map { it.trim() }
            .filter(ShellInput::isPackageName)
            .distinct()
            .take(MAX_PROTECTED_PACKAGES)
            .toMutableList()
    )

    const val MIN_DPI = 72
    const val MAX_DPI = 1_000
    const val MIN_REFRESH_HZ = 24
    const val MAX_REFRESH_HZ = 500
    const val MIN_GAME_MODE = 0
    const val MAX_GAME_MODE = 5
    const val MAX_FPS_CAP = 500
    const val MAX_NAME_LENGTH = 80
    const val MAX_PROTECTED_PACKAGES = 100
}
