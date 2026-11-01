# NitroBoost 1.24.0 — Test Results

**Test date:** 2026-11-01
**Branch:** `arena/4bed7c6c-s-mvtoer-m3vlk4tprqp-n32Awxa18CTD_NeLCjN2kQ-1`

## Completed in workspace

| Gate | Command | Result |
|---|---|---|
| Resource/Kotlin reference verifier | `python3 tools/verify.py` | PASS — 44 XML files, 145 Kotlin files, 112 IDs, 0 errors |
| Security regression checks | `python3 tools/security_verify.py` | PASS — 12 groups, 0 findings; one existing QUERY_ALL_PACKAGES policy warning |
| Python tool tests | `python3 -m unittest discover -s tools -p 'test_*.py' -v` | PASS — 25 tests |
| Diff whitespace | `git diff --check` | PASS at audit time |

## GitHub Actions result

Run: [41348861768](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/actions/runs/41348861768)
Commit: `e229b489ee70ff24720fe59d1d50fb5b6957c491`
Job: `verify-build` — **PASS** on 2026-11-01.

The run completed the JDK 17 / Gradle 8.14.3 setup, static/security gates, `test`, `lintDebug`, `assembleDebug`, APK metadata checks for `com.nitroboost.app` version `31` / `1.24.0`, `minSdk=24`, `targetSdk=36`, and `apksigner verify --verbose`. The JVM task reported 278 tests completed with no failures after the fail-closed probe regression was fixed.

The debug artifact is available from the workflow as `nitroboost-debug-apk`. The workflow creates `NitroBoost-v1.24.0-debug.apk`, `NitroBoost-v1.24.0-source.zip`, and `SHA256SUMS`. Checksums recorded by CI are:

```text
29331c2e6754c867df2e05a8b1fc860b507d2c226baf58e6f87fc1461dfba51d  NitroBoost-v1.24.0-debug.apk
317a13ed38d23c518b2faa1fa2a3245805c70f8083854760ef144ee140009a3e  NitroBoost-v1.24.0-source.zip
```

The sandbox could not copy the Actions blob into the workspace because the artifact download redirect is outside the permitted outbound hosts; the GitHub Actions artifact is the authoritative binary hand-off.

The workflow's release-only jobs were correctly skipped on this feature branch. No signed release APK was produced.

## Not tested here

No Android device or repeatable benchmark was available. Therefore this report does not establish:

- FPS, GPU utilization, frame time, energy, or thermal improvement;
- behavior across OEM ROMs/API 24–36;
- compatibility with a v1.23.0 release certificate;
- Play Store policy compliance.
