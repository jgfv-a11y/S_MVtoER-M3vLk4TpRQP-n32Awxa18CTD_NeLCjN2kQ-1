# Changelog

## v1.6.0 — Release Candidate: Stability, thermal safety, and recovery hardening

### Highlights
- Fixed monitor/session race conditions that could lead to duplicated or inconsistent boost state.
- Hardened Journal persistence against corruption and oversized growth.
- Added automatic Shizuku reconnect and safe fallback when the privileged channel is temporarily unavailable.
- Added predictive thermal escalation before the device reaches throttling.
- Added JVM unit tests covering journal corruption, rotation, and basic restore flows.

### Stability and safety
- `AppStore`: synchronized session state updates and safer monitor restarts.
- `Journal`: atomic-ish safe write sequence, backup retention, archive rotation, and corruption protection.
- `ShizukuShell`: retry/backoff reconnect logic with graceful degradation.
- `ThermalGuard`: predictive thermal escalation to de-escalate early before frames collapse.

### Validation
- Added `JournalTest.kt` for reliability checks around persistence, recovery, and rotation.

### Notes
- This is a release-candidate milestone prepared for local / CI packaging.
- Signed APK production still requires Android SDK + keystore configuration in CI or local environment.
