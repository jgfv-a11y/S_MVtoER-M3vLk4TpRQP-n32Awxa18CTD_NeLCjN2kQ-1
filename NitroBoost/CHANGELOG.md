# Changelog
## v1.16.1 — stability maintenance release
- No product behavior changes; this release records the completed rollback, thermal-safety, cancellation, and security regression review.
- Re-ran static, security, and Python regression gates before the hosted Android build.
- No performance claim is made without a real-device benchmark.

## v1.16.0 — measured frame pacing intelligence
- Added a quality-gated 1% Low FPS metric computed only from valid measured FPS samples.
- Kept the metric null below five samples to avoid presenting unstable evidence as a performance result.
- No synthetic FPS, frame generation, or automatic privileged tuning was added.

## v1.15.2 — thermal fail-closed safety fix
- Treat a missing raw temperature as critical while the thermal override journal entry is active, preventing loss of the independent thermal backstop.
- Preserve nominal handling for missing temperature when no override is active.
- Added regression coverage; no new optimization behavior.

## v1.15.1 — rollback verification fix
- Verify system/secure/global setting deletion after the delete operation before marking a journal entry restored.
- Added regression coverage for a platform delete that reports success while leaving the changed value behind.
- No new optimization behavior.


## v1.15.0 — evidence-based diagnostics and release hardening (2026-10-06)
- **Version code 19.** Published by GitHub Actions after static/security checks, JVM tests, lint, debug APK assembly, and unsigned release APK build. The GitHub Release contains the debug APK for testing; no signed APK is attached.
- No physical-device A/B benchmark was run; no gameplay FPS gain is claimed.

### Competitive guidance and security regression gate
- Added bilingual, evidence-aware next-step guidance for all nine performance states. Advice is manual only; it does not change CPU/GPU/network/display/thermal settings or promise an FPS gain.
- Added dependency-free security regression checks for manifest permissions/exported components, backup/cleartext policy, pinned dependencies/actions, obvious source secrets, and least-privilege release workflow; added Python unit tests for those checks.
- Split release into a secret-free read-only unsigned build, an `apksigner`-only signing job, and a main-only publish job. Keystore secrets are isolated from Gradle; only the publish job has `contents: write`. Action refs are pinned to verified full commit SHAs and Dependabot updates GitHub Actions weekly.
- Added `docs/COMPETITIVE_ROADMAP.md`; GitHub Actions passed the security gates, tests, lint, and APK assembly. Device-specific gains still require physical A/B testing.
- Added task-scoped write-ahead journaling for supported reversible mutations, including adaptive KEEP reapplication; mutations fail closed if the reversal cannot be persisted. One-way trim/force-stop operations remain explicitly non-reversible.

### Phase 1 performance-state diagnostics
- Added a bounded, evidence-based `PerformanceStateEngine` over existing snapshots, with confidence, source freshness/data quality, timestamps, and the nine requested states. GPU remains unknown without direct telemetry; packet loss remains unavailable; TCP probe scope is explicit.
- Connected the result to the existing adaptive status panel without changing boost policy. Removed the legacy GPU-by-elimination bottleneck from live UI and new session-report generation; retained old report parsing for compatibility.
- Added 14 deterministic JVM tests and corrected the Journal rotation test to preserve active rollback entries; CI passed JVM tests, lint, and APK assembly.
- Corrected Game API/network/report wording so platform requests and system/TCP measurements are not described as guaranteed game-performance outcomes.

## v1.14.1 — stability maintenance release
- No new features. Revalidated monitoring lifecycle, telemetry freshness boundaries, and the existing BoostFragment correctness fix.
- CI remains the build gate for tests, lint, and debug APK publication.
- Version code 18.

## v1.14.0 — gfxinfo epoch recovery
- **Frame telemetry recovery:** detects backward `FrameCompleted` timestamps when a game process or gfxinfo clock epoch restarts without resetting the cumulative frame counter.
- **Vsync tracker recovery:** starts a clean IntendedVsync epoch after backward timestamps instead of silently discarding all later intervals.
- **Regression coverage:** added parser and tracker tests for timestamp-reset behavior.
- **Version code 17**.


## v1.13.0 — Unified telemetry and frame pacing
- **Version code 16**; GitHub Actions is the release gate and publishes the debug APK after verification, JVM tests, lint, and assemble succeed. A signed release APK still requires the configured signing secrets.
- Added the pure-Kotlin `core/telemetry/` snapshot model and `SensorFusion`; readings retain explicit null/availability instead of turning missing CPU, GPU, RAM, thermal, battery, network, or frame sources into measurements.
- Integrated best-effort read-only CPU policy frequency, system memory pressure, battery temperature/current, and public device/display metadata. GPU utilization/model/renderer and energy remain null where no trustworthy source is available; no privileged telemetry write or cloud collection was added.
- Extended `gfxinfo` parsing to retain real IntendedVsync timestamps across polls. `FramePacingAnalyzer` now reports measured FPS mean/median, frame-time mean/median/P95/P99/variance, the existing >2x-budget jank rate, and a clearly labeled estimated missed-vsync rate when target FPS and timestamp gaps exist.
- Added deterministic stability/smoothness diagnostics (0–100) only for windows with at least five measured frame-time samples. The A/B score weights, candidate decisions, task roster, Journal/rollback, Shizuku flow, and thermal policy are unchanged; extra frame-pacing metrics are persisted compatibly with old ledger files.
- Added JVM tests for telemetry normalization, unavailable values, vsync intervals, frame pacing, and ledger round trips. No device benchmark or FPS improvement is claimed; validation must pass CI and device-specific measurements remain separate.
- Architecture audit: `docs/ARCHITECTURE_AUDIT_v1.13.0.md`.

## v1.12.0 — Safety, display policy, and session efficiency
- **Version code 15** (up from 14); release APK publication remains gated on GitHub Actions.
- Hardened profile and preference handling: validate package names and numeric ranges, sanitize legacy profile data, bound protected-package lists, and atomically replace custom-profile JSON after fsync.
- RAM cleanup now excludes the selected game, the booster, protected packages, invalid package names, and all packages with currently resumed activities when Usage Access is available; app-process RSS rows are validated and aggregated by package. Automatic force-stop still requires an explicit aggressive profile option and privileged access.
- Display settings now preserve the device refresh policy by default (`0` means no refresh override), reject a requested rate above the reported panel peak, and journal precise prior refresh/density state—including resetting density overrides and deleting previously unset refresh keys on restore.
- Maximum screen brightness moved to boost level 3 because it can materially increase battery use and heat; it is no longer part of the standard level.
- Bounded shell output capture is connected to root and Shizuku process execution; validated sysfs, settings, governor, and package inputs; persisted shell reversals are allowlisted before execution.
- Session summaries use constant-memory accumulators, FPS presentation briefly retains a fresh measurement between polls, and monitoring runs only while a UI, session, or overlay client needs samples.
- Moved process/storage queries and force-stop work off the Settings UI thread; profile package-list lookups no longer reread the custom-profile file for every row.
- Fixed the static verifier's Kotlin `R.type.name` matching and added missing English/Arabic strings it exposed.
- Added JVM coverage for display restoration and refresh caps, RAM kill exclusions, CPU sysfs path validation, foreground protection, privilege gates, and tampered journal commands. GitHub Actions must still pass before release; no device benchmark or install test is claimed.

## v1.11.0 — Adaptive experiment hardening
- **Version code 14**; this revision changes the adaptive objective identity so decisions scored under different policies are not reused.
- Replaced full-run baseline-then-candidate sampling with quality-gated paired windows in deterministic alternating order (baseline→candidate, then candidate→baseline); persist each attempt before measuring it, with pair order, duration, time distance, and invalid-attempt metadata.
- Added an explicit bounded, finite, normalized objective-weight resolver with safe default fallback. `balanced-v1` (0.60/0.15/0.15/0.10) remains the default; the documented `thermal-cautious-v1` (0.50/0.20/0.20/0.10) policy is selected only in verified LIGHT-or-higher thermal context after at least eight valid prior pairs (for a sweep, per required arm). This is declared policy, not learning.
- FPS coefficient-of-variation is computed only from actual samples and gates unstable windows; invalid, incomplete, stale, or thermally contaminated blocks do not enter evidence or decisions.
- Reworked multi-variant sweeps to use the same paired-block pipeline while retaining per-arm evidence and family-wise correction. Existing ledger records remain readable with safe legacy defaults and are not reused across objective identities.
- Added behavior tests for weight validation/context selection, absent measurements, workload-quality rejection, persisted order alternation, sweep order, contaminated evidence, and legacy ledger defaults.
- Interleaving reduces temporal/workload confounding, but cannot prove the game executed identical internal workload in the two arms; no device benchmarks are claimed.
- **Session longevity fixes**: game-exit detection now follows UsageEvents lifecycle transitions instead of treating `lastTimeUsed` as a live-process heartbeat; unavailable usage state cannot falsely stop a session. Transient adaptive-step failures retry after backoff, and failed initial boosts attempt a journal rollback before ending the service.
- **Bounded privileged commands**: both root and Shizuku shell paths drain stdout/stderr concurrently and terminate a command that exceeds the 10s limit, including when a pipe would otherwise fill.
- Added JVM tests for UsageEvents state transitions and adaptive-loop transient-error retry; Android build/device behavior remains unverified in this environment.

## v1.10.0 — Adaptive evidence and restoration safety
- **Version code 13**; release workflow will publish APK artifacts after CI tests and build succeed.
- Every baseline/candidate arm retains paired block observations and its own confidence interval; multi-arm decisions use Bonferroni family-wise correction before ranking eligible winners.
- Documented a bounded normalized performance/thermal score; unavailable frame-time, energy, and other measurements are omitted rather than estimated.
- Added real `gfxinfo framestats` frame-time summaries, adaptive-window quality gates, explicit `MORE_DATA` handling, and nominal-state predictive thermal escalation/invalidation.
- Bound local ledger decisions to device/app/profile/boost/thermal context with TTL and known-good backup recovery.
- Serialized task mutations and session teardown; failed restores remain journaled, are reported, and keep the foreground service retrying instead of silently declaring the session stopped.
- Added deterministic JVM coverage for scoring, statistical correction, quality gates, cancellation/restore failure, ledger recovery, frame-time parsing, and task-lock serialization.

## v1.9.0 — Adaptive ledger and profile safety
- **Atomic Adaptive ledger writes** with fsync-before-rename to protect trial decisions from interruption.
- **Safe active-profile deletion**: deleting the selected custom profile automatically selects the first remaining profile.
- **Release documentation corrected** so the CI notes describe the actual version.
- **Version code 12**.

## v1.8.0 — Recovery, permissions, and atomic profile storage
- **Persistent stale-journal guard**: recovery continues polling after an empty journal, so it can also protect later sessions that die unexpectedly.
- **Correct Usage Stats status**: the permission checklist no longer depends on overlay permission.
- **Exact Doze package matching**: similar package names cannot be mistaken for the active game.
- **Atomic custom profile writes** with fsync-before-rename, reducing corruption after interruption.
- **Version code 11**.

## v1.7.0 — Correct settings fallback and release hardening
- **Fixed settings writes without direct permissions**: System, Secure, and Global settings now fall back to Shizuku/root when the public Android API returns `false`, instead of incorrectly stopping or reporting failure after a partial API attempt.
- **Avoided redundant privileged commands**: successful public API writes return immediately, preventing unnecessary shell calls and reducing journal/task latency.
- **Version code 10** with the existing recovery and thermal-safety guarantees from v1.6.0.


## v1.6.0 — Stability, thermal safety, and recovery hardening

First stable 1.6 release. Unblocks CI (red since the journal-rotation test)
and ships the recovery work that had been sitting as a release candidate.

### Highlights
- **CI is green again**: journal rotation now archives as `{filename}_{timestamp}`,
  matching the unit test and the files-dir audit convention.
- **Crash/kill rollback**: a dead session (reboot, process kill, service destroy)
  reverts leftover journal entries — `onDestroy` restore + stale-journal guard.
- **Shizuku reconnect is actually used**: `AndroidExecutor` retries the user
  service once before falling back to root / safe mode.
- **Monitor self-heal on stale samples**: the hub restarts if snapshots stop
  arriving, not only when the last value was empty.
- **Session samples are race-free**: FPS/temp/ping/RAM collection shares the
  session lock with begin/finish, so a stop cannot interleave with a tick.
- **Journal durability**: fsync-before-rename, `.bak` of last known-good,
  skip unknown kinds instead of wiping the whole file.
- **Hard thermal floor + predictive slope**: 44/48/52 °C floors plus an early
  warning slope so boosts back off before the OS flips to throttling.
- **More game profiles**: Genshin Global, Star Rail, COD Mobile Global,
  Free Fire MAX, Wild Rift, Roblox, Minecraft.
- **Least privilege**: `allowBackup=false` so a backup restore cannot replay
  a journal that no longer matches the device.

### Stability and safety
- `Journal`: atomic write + fsync, backup retention, `{filename}_{timestamp}`
  archive rotation, per-entry parse resilience.
- `AppStore`: synchronized session samples; monitor restart on stale ts (>20s).
- `BoosterService`: restore leftover tweaks in `onDestroy`.
- `AndroidExecutor`: Shizuku reconnect/backoff before degrading.
- `MonitorHub`: skip a broken tick instead of posting EMPTY (which looked like
  a dead hub).
- `ThermalGuard`: predictive escalation unit-tested.

### Validation
- Journal tests cover round-trip, `.bak`, corruption, rotation naming,
  unknown-kind skip, empty/missing files.
- Thermal guard tests cover raw floors + predictive slope.
- 118 JVM unit tests in the CI line (was 113, 1 failing).

### Notes
- Debug APK is published on every green build. A signed release APK is
  produced when the `NITRO_*` keystore secrets are configured — see
  `docs/DISTRIBUTION.md`.
