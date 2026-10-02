# Changelog
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
