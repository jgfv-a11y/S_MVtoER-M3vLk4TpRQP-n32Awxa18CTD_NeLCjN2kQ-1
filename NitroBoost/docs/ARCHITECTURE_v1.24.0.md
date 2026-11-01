# NitroBoost 1.24.0 — Architecture and Safety Notes

## Data flow

```text
ProfileStore / Prefs
        │ validated AppProfile
        ▼
   BoostEngine ───────────────► CapabilityRegistry ───► TaskState/UI
        │                                  │
        │ serialized task lock             └─ short-lived probe cache
        ▼
 JournaledSystemExecutor ───► SystemExecutor (Root/Shizuku/limited APIs)
        │
        └─ Journal: atomic write, ownership-aware restore, recovery block

BoosterService / AppStore
        ├─ MonitorSnapshot + MeasurementProvenance ───► Dashboard
        └─ AdaptiveLoop ── paired windows ── DecisionLedger
              │
              ├─ thermal guard / cancellation / cooldown
              └─ apply → measure → KEEP or restore
```

## Capability semantics

A task's boolean `supported` is retained for compatibility, but UI truth comes from `CapabilityStatus`. `APPLIED` is derived from an owned Journal entry; `PERMISSION_REQUIRED` is not the same as `UNSUPPORTED`; `REJECTED` is a policy decision, not a hardware probe failure.

The registry key includes task, profile, privilege, thermal sensor, recovery-blocked state, and Journal ownership. Entries expire quickly because Shizuku/root and thermal sources can change while the app is open.

## Mutation and rollback contract

1. Validate profile and task capability.
2. Acquire the per-task lock.
3. Read the current value.
4. Persist the original value and new value before/with the mutation.
5. Verify where the platform exposes a readable value.
6. On restore, write only when the current value is still the Journal's `newValue`; an external value is not overwritten.
7. Remove a Journal entry only after successful, verified restoration. Corrupt or unrecoverable Journal state blocks new mutations.

Legacy thermal entries are restored only through the existing allow-listed Journal restore rules. New thermal override mutation is rejected before reaching the delegate executor.

## Adaptive evidence model

Adaptive trials compare nearby baseline/candidate windows. They retain timestamps, package/process epoch, thermal tier, monitor age, sample counts, and quality reason. No observation is promoted to evidence when the window is stale, incomplete, thermally contaminated, or from an unexpected game process. A cooldown separates trials to reduce carry-over and heat accumulation.

`DecisionLedger` is local decision history, not a benchmark database. Its result is a reversible preference for the current device/profile/context and may expire. The UI must not translate it into a universal FPS claim.

## Profile isolation

Built-in profiles are read from app assets and never overwritten. Custom profiles are stored separately and shadow built-ins only by exact package name. Import writes only validated custom profiles; package names are treated as data and pass package validation before persistence or shell use.

## Threading

- Android UI updates run on the main thread.
- Asset/file/Binder probes use `Dispatchers.IO` under `viewLifecycleOwner.lifecycleScope`.
- Adaptive measurement is cancellable and checks coroutine activity during windows.
- Apply/restore operations are serialized per task and session lifecycle.

## Compatibility boundary

The design preserves `com.nitroboost.app`, API 24 minimum, profile/Journal JSON compatibility, and existing core APIs where possible. Actual OEM behavior still requires devices across API 24–36; JVM and CI checks cannot prove it.
