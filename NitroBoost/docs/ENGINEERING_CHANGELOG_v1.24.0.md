# NitroBoost 1.24.0 — Engineering Changelog

**Release metadata:** `versionName=1.24.0`, `versionCode=31`, `applicationId=com.nitroboost.app`, `minSdk=24`, `targetSdk=36`.

## Scope

This release turns the v1.23.0 audit into implementation rather than a plan. It prioritizes reversible changes, capability truthfulness, thermal safety, and evidence provenance over aggressive tuning claims.

## Implemented

### Adaptive Engine 2.0 safety

- Trial apply/restore remains serialized through the per-task engine lock and durable Journal.
- Cancellation is propagated through measurement windows; cleanup is retained in the non-cancellable restore path.
- A mandatory `TrialConfig.cooldownMs` quiet period separates candidate trials and is included in the ETA.
- Paired observations retain order, timestamps, monitor freshness, game package/process epoch, thermal context, and quality decisions. Invalid or incomplete windows produce `MORE_DATA`, not a performance claim.
- A KEEP decision is never described as proof of FPS improvement; only repeatable measured observations can support a local decision.

### Capability Registry

`CapabilityRegistry` is now used by `BoostEngine.states()` and the Boost UI. It differentiates:

- `SUPPORTED`
- `UNSUPPORTED`
- `PERMISSION_REQUIRED`
- `TEMPORARILY_UNAVAILABLE`
- `FAILED`
- `APPLIED`
- `REJECTED`

Each status carries a reason, source, and observation time. The cache is short-lived and invalidated by relevant permission, journal, profile, and thermal-sensor state.

### Thermal policy

The legacy `thermal_override` task is retained only for data compatibility and legacy journal cleanup. Its `apply()` path is rejected. Profile validation normalizes the old `thermalOverride` field to `false`, the UI switch is disabled, and `JournaledSystemExecutor` rejects the new override command. The allow-listed legacy restore path remains separate and is still ownership-checked.

NitroBoost does **not** use `cmd thermalservice override-status 0`, does not disable Android thermal throttling, and does not present a thermal override as an optimization.

### Profile exchange

Custom profiles use a versioned JSON envelope with deterministic ordering and SHA-256 corruption detection. Import validates byte size, profile count, package names, numeric ranges, duplicate packages, schema version, and checksum before any write. Conflicts are reported and require an explicit replacement choice. Writes use temp file, fsync, and same-directory replacement.

### Measurement provenance

The dashboard now identifies source and freshness (`MEASURED`, `STALE`, or `UNAVAILABLE`). No FPS, GPU utilization, frame time, energy, or thermal value is synthesized when its real source is missing or stale.

### UI and lifecycle

- Home disk/Binder reads use lifecycle-scoped coroutines and are cancelled with the view.
- Profiles has import/export actions, explicit conflict messaging, and a locked thermal control.
- Boost task rows show capability state and reason.
- Material 3 Day/Night colors replace the dark-only base palette.
- The widget periodic refresh is conservative; a user tap remains the authoritative update action.

## Deliberate non-changes

- No package/application identity change.
- No new privileged permission.
- No claim of benchmark improvement.
- No release-signing key was generated or assumed.
- No thermal bypass or blind rollback was added.
