---
name: android-architecture
description: Owns Android/Kotlin architecture for NitroBoost: layering, design of systems and layers, and compatibility across Android versions (minSdk 24 / API 24 to targetSdk 36 / API 36), Java 17, Material 3, AR+EN resources. Invoke at PLAN and IMPLEMENT.
---

# Skill: android-architecture

## Mission and Scope

Design and evolve NitroBoost's Android/Kotlin architecture: clean layering,
testable pure-Kotlin cores, and **compatibility from Android 7.0 (API 24) up to
Android 16 (API 36)** while targeting API 36. Build constraints: Java 17 /
Kotlin (AGP), `compileSdk 36`, `minSdk 24`, `targetSdk 36`, Material 3, resources
in `values/` + `values-ar/`.

## When to Invoke (Triggers)

- PLAN phase for any structural change; IMPLEMENT for any UI/core/platform code.
- Any change touching AndroidManifest, resources, API-gated behavior.

## Inputs and Outputs

- Inputs: the plan from `engineering-director`, existing layers (`core`,
  `platform`, `data`, `ui`, `service`), `AndroidManifest.xml`, `res/`.
- Outputs: design decision (with alternatives) recorded in the daily log; code
  that respects the layering; resources added in both languages when user-facing.

## Execution Procedure

1. Respect the layering: `ui/` and `service/` depend on `core/` + `data/` +
   `platform/`; `core/` models stay Android-free and JVM-testable
   (`core/telemetry`, math classes) so they run in `app/src/test`.
2. API-gate everything version-specific: notification channels, SPECIAL_USE
   foreground service types, `UsageEvents`, display APIs. Default path must work
   on API 24; newer APIs only behind `Build.VERSION.SDK_INT` checks with tested
   fallbacks. **Never use `java.nio.file` (API 26+) on storage paths** — use
   `AtomicFileOps`.
3. Keep commands/resources consistent: every new `R.string` in `values/strings.xml`
   must have an Arabic twin in `values-ar/strings.xml`.
4. Manifest discipline: every declared component must exist as a class
   (checked by `tools/verify.py`); permissions must be justified and minimal.
5. No dependency/Gradle/Kotlin upgrades without written justification (RULE-07).
6. Prefer immutable data classes + unidirectional state (`AppStore`) over
   scattered mutable state.

## Acceptance Criteria

- [ ] New/changed code fits the layering; core logic is unit-testable on JVM.
- [ ] All `Build.VERSION` gates have an API-24 fallback; no API-26+ API on
  minSdk-24 paths.
- [ ] AR+EN resources both updated for user-facing strings.
- [ ] `tools/verify.py` passes; CI `lintDebug`/`assembleDebug` pass.

## Testing and Verification

- `python3 NitroBoost/tools/verify.py` (resources, manifest, balance).
- JVM unit tests for any new pure logic (`app/src/test/...`), run via
  `./gradlew test` in CI.
- Read the final APK badging expectations: `package: name='com.nitroboost.app'`,
  `targetSdkVersion:'36'`, `min-sdk=24` (asserted in CI).

## Non-Negotiable Constraints

- minSdk 24 is a hard compatibility floor; targetSdk 36 must stay.
- RULE-07 (no unjustified upgrades), RULE-10 (tests for new behavior).
- Never weaken manifest permissions silently; any permission change is called
  out in the review.

## Repository References

- `NitroBoost/app/build.gradle.kts` (SDK levels, deps, Java 17)
- `NitroBoost/app/src/main/AndroidManifest.xml`
- `NitroBoost/app/src/main/res/values*/`, `NitroBoost/app/src/main/java/com/nitroboost/app/`
- `NitroBoost/app/src/test/java/...` (JVM tests), `NitroBoost/core` layering docs in `NitroBoost/docs/`
- `.github/workflows/build-apk.yml` (badging assertions)
