# GitHub Actions — Build APK

The workflow is `.github/workflows/build-apk.yml`.

## Triggers and jobs

- `push` to `main` or `arena/**`, `pull_request` targeting `main`, and manual dispatch run the read-only `verify-build` job.
- The workflow reads `versionName`/`versionCode`, runs `tools/verify.py`, the security regression gate and all offline Python regression tests (including the stress-test parsers), then runs Gradle JVM tests, `lintDebug`, and `assembleDebug`.
- The verified debug APK is uploaded as a workflow artifact.
- Release work is split by privilege: `prepare-release` builds an unsigned APK with read-only access and no secrets; `sign-release` only runs `apksigner` with the signing secrets (no Gradle/build scripts); `publish-release` runs only on `refs/heads/main` after verification and has `contents: write`, but no signing secrets or build steps. It attaches the debug APK and, when all signing secrets are present, the signed release APK to `nitroboost-v{versionName}`.
- No APKs or failure logs are pushed to secondary branches. On failure, the build log is summarized and uploaded as `build-fail-log`.
- Actions are pinned to full commit SHAs. `.github/dependabot.yml` checks GitHub Action updates weekly.

The workflow security checks are narrow regression guards; they are not a vulnerability scanner, MASVS certification, or proof of device safety. Device-specific game performance has not been benchmarked by CI and no FPS gain is implied.

## Signing secrets (optional)

`Settings → Secrets and variables → Actions`:

| Secret | Value |
|---|---|
| `NITRO_KEYSTORE_BASE64` | `base64 -w0 nitroboost-release.jks` |
| `NITRO_KEYSTORE_PASSWORD` | keystore password |
| `NITRO_KEY_ALIAS` | `nitroboost` |
| `NITRO_KEY_PASSWORD` | key password |

If all four are absent, CI publishes only the debug-signed APK. A partially populated secret set fails the release job instead of silently creating an unsigned/misconfigured release APK. See `NitroBoost/docs/DISTRIBUTION.md` for keystore generation.

## Local build

```bash
cd NitroBoost
gradle wrapper --gradle-version 8.7   # once, if gradlew is missing
./gradlew test lintDebug assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

- **SDK not found** — the workflow installs `platforms;android-34` and `build-tools;34.0.0` itself. The project currently compiles/targets API 34; review `NitroBoost/docs/COMPETITIVE_ROADMAP.md` before Google Play distribution, since the Play target-API requirement may require API 36.
- **Release APK not produced** — signing secrets are absent; the verified debug artifact remains available.
- **Unit test/build failure** — inspect the job summary and download the `build-fail-log` workflow artifact.
