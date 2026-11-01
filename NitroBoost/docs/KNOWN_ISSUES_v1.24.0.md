# NitroBoost 1.24.0 — Known Issues and Limits

## Verification limits

- No physical Android device, OEM ROM matrix, or repeatable game benchmark was available in the development workspace.
- CI can compile, run JVM tests, lint, inspect APK metadata, and verify APK structure. It cannot prove FPS, frame time, battery, thermal, or game-workload improvement.
- No confirmed release keystore from v1.23.0 is present. The debug APK must not be described as a signed update.

## Platform variability

- Root/Shizuku availability, Binder permissions, `cmd game`, governors, sysfs nodes, DND, and settings behavior vary by device and OEM.
- Some devices expose no trustworthy GPU utilization, frame-time, energy, or thermal-zone source. NitroBoost shows those measurements as unavailable rather than estimating them.
- `gfxinfo` counters can reset when the game process restarts or changes epoch. Cross-session numbers are not an A/B benchmark.
- API 24–25 devices need physical testing for command timeouts, service lifecycle behavior, and vendor-specific shells.

## Product behavior

- A capability may change from Supported to Permission Required or Temporarily Unavailable after Shizuku/root disconnects; the registry cache is intentionally short-lived.
- Profile imports containing an existing package require explicit replacement. Built-in profiles cannot be overwritten by import.
- A corrupt or unrecoverable Journal blocks new mutations until the user resolves/restores the durable state. This is fail-closed behavior.
- The adaptive engine can return `MORE_DATA` for unstable or thermally contaminated sessions. It is safer than converting weak evidence into KEEP/DROP.
- The widget's periodic refresh is not a real-time monitor. Tap it to refresh immediately.

## Not supported by design

- Disabling or overriding Android thermal throttling.
- Presenting CPU frequency as proof of performance.
- Synthetic FPS/GPU/frame-time/energy values.
- Silent replacement of externally changed system values.
- Claiming a universal benchmark or game FPS gain from a local adaptive decision.
